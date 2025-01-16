package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.*;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ApplicationContext {
    private final String basePackage;
    private final Map<String, Object> beanFactory=new HashMap<>();

    public ApplicationContext(String basePackage) {
        this.basePackage=basePackage;
    }

    public void init() {
        Reflections reflections=
                new Reflections(basePackage, Scanners.TypesAnnotated,Scanners.MethodsAnnotated);

        Set<Class<?>> repositories=reflections.getTypesAnnotatedWith(Repository.class);
        Set<Class<?>> services=reflections.getTypesAnnotatedWith(Service.class);
        Set<Class<?>> components=reflections.getTypesAnnotatedWith(Component.class);
        Set<Class<?>> configurations=reflections.getTypesAnnotatedWith(Configuration.class);
        Set<Method> beanMethods= reflections.getMethodsAnnotatedWith(Bean.class);


        try {
            registerBeansFromClasses(repositories);
            registerBeansFromClasses(services);
            registerBeansFromClasses(components);
            registerBeansFromClasses(configurations);
            registerBeansFromMethods(beanMethods);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

    }

    private void registerBeansFromMethods(Set<Method> methods) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        for (Method method : methods){
            String resultBeanName=extractBeanName(method.getName());

            //메서드 이름으로 등록된 빈이 있다면 스킵한다.
            if(beanFactory.containsKey(resultBeanName)) continue;

            method.setAccessible(true);

            //invoke하는 데에는 호출하는 객체가 필요하다. 빈에서 꺼내올 수 있으면 꺼내오고, 아니면 빈에 등록한 뒤에 꺼낸다.
            Class<?> classOfMethodCaller = method.getDeclaringClass();

            String callerBeanName=classOfMethodCaller.getName();
            Object methodCaller;
            if(beanFactory.containsKey(callerBeanName)) methodCaller=beanFactory.get(callerBeanName);
            else{
                methodCaller = generateObjectFromConstructor(classOfMethodCaller, classOfMethodCaller.getDeclaredConstructors());
            }

            //메서드에 필요한 인자들이 있다면 그 인자들도 빈에 등록한 뒤에 꺼내는 방식으로 객체로 만든다. 그 후 메서드 인자로 준다.
            Class<?>[] params = method.getParameterTypes();

            Object instance;
            if(params.length ==0 ){
                instance = method.invoke(methodCaller);
            }else{
                Object[] args=new Object[params.length];

                for (int i=0;i<params.length;i++){
                    args[i]=generateObjectFromConstructor(params[i],params[i].getDeclaredConstructors());
                }

                instance=method.invoke(methodCaller, args);
            }

            //이미 존재하는지 따지는 로직은 처음 부분에 있기 때문에 생략
            beanFactory.put(resultBeanName,instance);
        }

    }

    private void registerBeansFromClasses(Set<Class<?>> classes) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        if(classes.isEmpty()) return;

        for(Class<?> _class : classes){

            if(_class.isAnnotation() || beanFactory.containsKey(extractBeanName(_class.getName()))) continue; //어노테이션이거나 이미 있다면 스킵

            Constructor<?>[] constructors = _class.getDeclaredConstructors();

            generateObjectFromConstructor(_class,constructors);
        }
    }

    private Object generateObjectFromConstructor(Class<?> _class, Constructor<?>[] args) throws InvocationTargetException, InstantiationException, IllegalAccessException {

        String className = extractBeanName(_class.getName());

        //빈 팩토리에 있는 경우 그대로 반환해준다. 어느 실행 흐름을 타더라도 결국 빈 팩토리에 등록된 것을 반환하게 된다.
        if (beanFactory.containsKey(className)) {
            return beanFactory.get(className);
        }

        for (Constructor<?> constructor : args) {
            //public이 아닌 생성자를 위해서
            constructor.setAccessible(true);

            Class<?>[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length == 0 && args.length == 1) {
                Object result=constructor.newInstance();
                beanFactory.put(className,result); //기본 생성자인 경우 등록 즉시 put. 위에서 빈 팩토리에 있는지 여부를 미리 검사하므로 상관 없다.
                return result;
            }

            if (paramTypes.length > 0) { //생성자의 인자가 하나 이상인 경우
                Object[] params = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) { //각 파라미터를 빈 팩토리에서 받아온 것으로 등록할 수 있도록 한다.
                    params[i] = generateObjectFromConstructor(paramTypes[i], paramTypes[i].getConstructors());
                }
                Object result =constructor.newInstance(params);
                beanFactory.put(className,result); //위 과정을 완료한 후 즉시 put.
                return result;
            }
        }

        throw new RuntimeException("스캔되지 않은 파일이 있습니다."); //
    }

    //빈에 저장될 때 이름 형식으로 텍스트 가공
    private String extractBeanName(String fullName) {
        int lastSeparator = fullName.lastIndexOf(".");
        return Ut.str.lcfirst(fullName.substring(lastSeparator + 1));
    }

    public <T> T genBean(String beanName) {
        return (T) beanFactory.get(beanName);
    }

}
