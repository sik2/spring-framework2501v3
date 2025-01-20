package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.*;
import com.ll.standard.util.Ut;
import lombok.Getter;
import lombok.Setter;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class ApplicationContext {

    private Map<Class<?>, BeanDefinition> beanMap;
    private Map<String, Object> beanContainer;
    private String basePackage;

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
        this.beanMap = new HashMap<>();
        this.beanContainer = new HashMap<>();
    }

    public void init() {
        //빈 등록
        Set<Class<?>> classes = findClass(basePackage);
        for (Class<?> clazz : classes) {
            if (clazz.isAnnotationPresent(Component.class)) {
                registerBean(clazz, Component.class);
            }
            else if (clazz.isAnnotationPresent(Service.class)) {
                registerBean(clazz,Service.class);
            }
            else if (clazz.isAnnotationPresent(Repository.class)) {
                registerBean(clazz, Repository.class);
            }
            else if (clazz.isAnnotationPresent(Configuration.class)) {
                registerBean(clazz, Configuration.class);
            }
            else if (clazz.isAnnotationPresent(Bean.class)) {
                registerBean(clazz, Bean.class);
            }
        }

        //의존성 주입
        Set<BeanDefinition> remainingBeans = new HashSet<>(beanMap.values());
        while (!remainingBeans.isEmpty()){
            int preSize = remainingBeans.size();
            Iterator<BeanDefinition> it = remainingBeans.iterator();
            while (it.hasNext()){
                BeanDefinition beanDefinition = it.next();
                Object bean = injectDependence(beanDefinition);
                if (bean != null){
                    beanContainer.put(beanDefinition.getBeanName(), bean);
                    it.remove();
                }
            }
            int postSize = remainingBeans.size();
            if (preSize == postSize){
                throw new RuntimeException("의존성 주입이 불가능한 빈이 존재합니다.");
            }
        }
    }

    public void registerBean(Class<?> clazz, Class<?> annotationType) {
        try {
            if (annotationType == Configuration.class) {
                String configBeanName = Ut.str.lcfirst(clazz.getSimpleName());
                Constructor<?> constructor = clazz.getConstructor();
                beanMap.put(clazz, new BeanDefinition(configBeanName, annotationType, constructor, new Class<?>[]{}));

                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Bean.class)) {
                        String beanName = method.getName();
                        Class<?> returnType = method.getReturnType();
                        Class<?>[] parameterTypes = method.getParameterTypes();

                        BeanDefinition beanDefinition = new BeanDefinition(
                            beanName,
                            returnType,
                            method,
                            parameterTypes
                        );
                        beanMap.put(returnType, beanDefinition);
                    }
                }
            } else {
                // 기존 로직
                Class<?>[] parameterTypes = Arrays.stream(clazz.getDeclaredFields())
                    .filter(field -> Modifier.isFinal(field.getModifiers()))
                    .map(Field::getType)
                    .toArray(Class<?>[]::new);

                Constructor<?> constructor = clazz.getConstructor(parameterTypes);
                String beanName = Ut.str.lcfirst(clazz.getSimpleName());
                beanMap.put(clazz, new BeanDefinition(beanName, annotationType, constructor, parameterTypes));
            }
        } catch (Exception e) {
            throw new RuntimeException("빈 등록 중 오류 발생" + clazz.toString(), e);
        }
    }

    public Object injectDependence(BeanDefinition beanDefinition) {
        try {
            String beanName = beanDefinition.getBeanName();
            if (beanContainer.containsKey(beanName)) {
                return beanContainer.get(beanName);
            }

            Object[] parameters = new Object[beanDefinition.getParameterTypes().length];

            for (int i = 0; i < beanDefinition.getParameterTypes().length; i++) {
                Class<?> parameterType = beanDefinition.getParameterTypes()[i];

                BeanDefinition parameterBeanDefinition = null;
                for (Class<?> beanType : beanMap.keySet()) {
                    if (beanType.equals(parameterType)) {
                        parameterBeanDefinition = beanMap.get(beanType);
                        break;
                    }
                }

                if (parameterBeanDefinition == null) {
                    throw new RuntimeException(
                        String.format("의존성 %s를 찾을 수 없습니다.", parameterType.getName())
                    );
                }

                Object dependencyBean = injectDependence(parameterBeanDefinition);
                parameters[i] = dependencyBean;

                if (!beanContainer.containsKey(parameterBeanDefinition.getBeanName())) {
                    beanContainer.put(parameterBeanDefinition.getBeanName(), dependencyBean);
                }
            }

            Object bean;
            if (beanDefinition.getMethod() != null) {
                if (beanDefinition.getConfigClassInstance() == null) {
                    Class<?> configClass = beanDefinition.getMethod().getDeclaringClass();
                    Object configInstance = configClass.getDeclaredConstructor().newInstance();
                    beanDefinition.setConfigClassInstance(configInstance);
                }
                bean = beanDefinition.getMethod().invoke(beanDefinition.getConfigClassInstance(), parameters);
            } else {
                bean = beanDefinition.getConstructor().newInstance(parameters);
            }

            beanContainer.put(beanName, bean);
            return bean;

        } catch (Exception e) {
            throw new RuntimeException(
                String.format("Bean %s 생성 중 오류 발생", beanDefinition.getBeanType().getName()),
                e
            );
        }
    }

    public <T> T genBean(String beanName) {
        beanName = Ut.str.lcfirst(beanName);
        if (beanContainer.containsKey(beanName)) {
            return (T) beanContainer.get(beanName);
        }
        return null;
    }

    public Set<Class<?>> findClass(String basePackage) {
        Reflections reflections = new Reflections(basePackage,
            new SubTypesScanner(false),
            new TypeAnnotationsScanner());

        // getSubTypesOf(Object.class)를 사용하여 모든 클래스를 가져옴
        return reflections.getSubTypesOf(Object.class).stream()
            .filter(clazz -> !clazz.getPackageName().endsWith(".annotations"))
            .collect(Collectors.toSet());
    }

    @Getter
    public static class BeanDefinition {
        private String beanName;
        private Class<?> beanType;
        private Constructor<?> constructor;
        private Method method;  // @Bean 메서드 정보 추가
        private Class<?>[] parameterTypes;
        @Setter
        private Object configClassInstance;

        public BeanDefinition(
            String beanName,
            Class<?> beanType,
            Constructor constructor,
            Class<?>[] parameterTypes) {
            this.beanName = beanName;
            this.beanType = beanType;
            this.constructor = constructor;
            this.parameterTypes = parameterTypes;
        }

        public BeanDefinition(
            String beanName,
            Class<?> beanType,
            Method method,
            Class<?>[] parameterTypes) {
            this.beanName = beanName;
            this.beanType = beanType;
            this.method = method;
            this.parameterTypes = parameterTypes;
        }
    }
}
