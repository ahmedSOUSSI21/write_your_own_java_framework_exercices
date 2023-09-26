package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class InterceptorRegistry {
    //private final HashMap<Class<? extends Annotation>, List<AroundAdvice>> annotationMap = new HashMap<>();
    private final HashMap<Class<? extends Annotation>, List<Interceptor>> interceptorMap = new HashMap<>();
    private final HashMap<Method, Invocation>  cache = new HashMap<>();

    public void addAroundAdvice(Class<? extends Annotation> annotationClass, AroundAdvice aroundAdvice){
        /*

        annotationMap.computeIfAbsent(annotationClass, __ -> new ArrayList<>()).add(aroundAdvice);
         */
        Objects.requireNonNull(annotationClass);
        Objects.requireNonNull(aroundAdvice);
        addInterceptor(annotationClass, (instance, method, args, invocation) -> {
            aroundAdvice.before(instance, method, args);
            Object result = null;
            try {
                result = invocation.proceed(instance, method, args);
                return result;
            }
            finally {
                aroundAdvice.after(instance, method, args, result);
            }
        });
    }

    /*
    List<AroundAdvice> findAdvices(Method method){
        return Arrays.stream(method.getAnnotations())
                .flatMap(annotation -> annotationMap.getOrDefault(annotation.annotationType(), List.of()).stream())
                .toList();
    }
     */

    public void addInterceptor(Class<? extends Annotation> annotationClass, Interceptor interceptor){
        Objects.requireNonNull(annotationClass);
        Objects.requireNonNull(interceptor);
        interceptorMap.computeIfAbsent(annotationClass, __ -> new ArrayList<>()).add(interceptor);
        cache.clear(); // invalidate cache
    }

    static Invocation getInvocation(List<Interceptor> interceptors){
        Invocation invocation = Utils::invokeMethod;
        for(var interceptor: interceptors.reversed()){
            Invocation finalInvocation = invocation;
            invocation = ((instance, method, args) -> interceptor.intercept(instance, method, args, finalInvocation));
        }
        return  invocation;
    }
    List<Interceptor> findInterceptors(Method method){
        return Stream.of(
                        Arrays.stream(method.getDeclaringClass().getAnnotations()),
                        Arrays.stream(method.getAnnotations()),
                        Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream)
                )
                .flatMap(s -> s)
                .map(Annotation::annotationType)
                .distinct()
                .flatMap(annotationType -> interceptorMap.getOrDefault(annotationType, List.of()).stream())
                .toList();
    }

    private Invocation computeInvocation(Method method){
        return cache.computeIfAbsent(method, method1 ->
            getInvocation(findInterceptors(method1))
        );
    }
    public  <T> T createProxy(Class<T> interfaceType, T instance) {
        Objects.requireNonNull(interfaceType);
        Objects.requireNonNull(instance);
        return interfaceType.cast(Proxy.newProxyInstance(interfaceType.getClassLoader(),
              new Class<?>[]{interfaceType},
              (proxy, method, args) -> {
                /*
                var advices = findAdvices(method);
                for(var advice: advices){
                    advice.before(instance, method, args);
                }
                Object result = null;
                try {
                  result = Utils.invokeMethod(instance, method, args);
                  return result;
                }
                finally {
                    for(var advice: advices.reversed()){
                        advice.after(instance, method, args, result);
                    }
                }

                 */
                  var invocation = computeInvocation(method);
                  return invocation.proceed(instance, method, args);
            }));
    }
}
