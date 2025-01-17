package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.*;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

public class ApplicationContext {

    private final Map<String, Object> dependencyMap = new HashMap<>();
    private final String basePackage;
    private final Set<Class<?>> components = new HashSet<>();
    private final Set<Method> beanMethods = new HashSet<>();

    public ApplicationContext(String basePackage) {
        this.basePackage = basePackage;
    }

    public void init() {
        try {
            scan();
            createComponents();
            createBeans();

        } catch (Exception e) {
            System.out.println("객체 준비 중 오류 발생!");
        }
    }

    public <T> T genBean(String beanName) {
        return (T) dependencyMap.get(beanName);
    }

    private void scan() {
        Reflections reflections = new Reflections(
            new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage(basePackage))
                .setScanners(
                    new SubTypesScanner(false),
                    new TypeAnnotationsScanner(),
                    new MethodAnnotationsScanner()));

        components.addAll(reflections.getTypesAnnotatedWith(Component.class));
        beanMethods.addAll(reflections.getMethodsAnnotatedWith(Bean.class));
        components.removeIf(Class::isAnnotation);

        components.forEach(clazz -> System.out.println("찾은 컴포넌트 : " + clazz.getSimpleName()));
        beanMethods.forEach(method -> System.out.println("찾은 빈 생성 메서드 : " + method.getName()));
    }
    
    private void createComponents() throws Exception {
        for (Class<?> clazz : components) {
            createBeanByType(clazz);
        }
    }

    private void createBeans() throws Exception {
        for (Method method : beanMethods) {
            createBeanByMethod(method);
        }
    }

    private <T> T createBeanByType(Class<?> clazz) throws Exception {
        String className = getClassName(clazz);
        if (dependencyMap.containsKey(className)) {
            return (T) dependencyMap.get(className);
        }

        Constructor<?> constructor = clazz.getConstructors()[0];
        Object[] args = getDependencies(constructor.getParameterTypes());

        T bean = (T) constructor.newInstance(args);
        dependencyMap.put(className, bean);
        System.out.println("빈 생성 : " + className);
        return bean;
    }

    private <T> T createBeanByMethod(Method method) throws Exception {
        if (dependencyMap.containsKey(method.getName())) {
            return (T) dependencyMap.get(method.getName());
        }

        Object configClass = getConfigInstance(method.getDeclaringClass());
        Object[] args = getDependencies(method.getParameterTypes());

        Object bean = method.invoke(configClass, args);
        dependencyMap.put(method.getName(), bean);
        System.out.println("빈 생성 : " + method.getName());
        return (T) bean;
    }

    private Object[] getDependencies(Class<?>[] paramTypes) throws Exception {
        Object[] dependencies = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Method factoryMethod = findFactoryMethod(paramTypes[i]);
            if (factoryMethod != null) {

                if (dependencyMap.containsKey(factoryMethod.getName())) {
                    dependencies[i] = dependencyMap.get(factoryMethod.getName());
                    continue;
                }

                Object configInstance = getConfigInstance(factoryMethod.getDeclaringClass());
                Object[] args = getDependencies(factoryMethod.getParameterTypes());

                Object bean = factoryMethod.invoke(configInstance, args);
                dependencyMap.put(factoryMethod.getName(), bean);
                System.out.println("빈 생성 : " + factoryMethod.getName());
                dependencies[i] = bean;

            } else {
                dependencies[i] = createBeanByType(paramTypes[i]);
            }
        }
        return dependencies;
    }

    private String getClassName(Class<?> clazz) {
        return clazz.getSimpleName().substring(0, 1).toLowerCase() + clazz.getSimpleName().substring(1);
    }

    private Method findFactoryMethod(Class<?> type) {
        for (Method factoryMethod : beanMethods) {
            if (factoryMethod.getReturnType().equals(type)) {
                return factoryMethod;
            }
        }
        return null;
    }

    private Object getConfigInstance(Class<?> configClass) throws Exception {
        String configClassName = getClassName(configClass);
        if (!dependencyMap.containsKey(configClassName)) {
            dependencyMap.put(configClassName, createBeanByType(configClass));
        }
        return dependencyMap.get(configClassName);
    }

}
