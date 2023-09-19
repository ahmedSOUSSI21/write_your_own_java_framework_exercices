package com.github.forax.framework.injector;

import jdk.jshell.execution.Util;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Supplier;

public final class InjectorRegistry {
    private final HashMap<Class<?>, Supplier<?>> instances = new HashMap<>();

    public <T> void registerInstance(Class<T> type, T instance){
        Objects.requireNonNull(instance);
        registerProvider(type, () -> instance);
    }

    public <T> T lookupInstance(Class<T> type){
        Objects.requireNonNull(type);
        var result = instances.get(type);

        if(result == null){
            throw new IllegalStateException("No instance registered for this class " + type.getName());
        }
        return type.cast(result.get());
    }

    public <T> void registerProvider(Class<T> type, Supplier<? extends T> supplier){
        Objects.requireNonNull(type);
        Objects.requireNonNull(supplier);
        instances.putIfAbsent(type, supplier);
    }

    public static <T>  List<PropertyDescriptor> findInjectableProperties(Class<T> type){
        Objects.requireNonNull(type);
        return Arrays.stream(Utils.beanInfo(type).getPropertyDescriptors())
                .filter(prop -> prop.getWriteMethod() != null && prop.getWriteMethod().getAnnotation(Inject.class) != null)
                .toList();
    }

    public <T> void registerProviderClass(Class<T> type, Class<? extends T> providerClass){
        Objects.requireNonNull(type);
        Objects.requireNonNull(providerClass);
        var constructor = findInjectableConstructor(providerClass).orElseGet(() -> Utils.defaultConstructor(providerClass));

        var properties = findInjectableProperties(providerClass);

        registerProvider(type, () -> {
            var parameters = Arrays.stream(constructor.getParameterTypes()).map(this::lookupInstance).toArray();
            var instance = providerClass.cast(Utils.newInstance(constructor, parameters));
            for(var property: properties) {
                Utils.invokeMethod(instance, property.getWriteMethod(), lookupInstance(property.getPropertyType()));
            }
            return instance;
        });
    }

    public <T> void registerProviderClass(Class<T> providerClass){
        Objects.requireNonNull(providerClass);
        registerProviderClass(providerClass, providerClass);
    }
    private static <T> Optional<Constructor<?>> findInjectableConstructor(Class<? extends T> providerClass) {
        var constructors = Arrays.stream(providerClass.getConstructors())
                .filter(constructor1 -> constructor1.isAnnotationPresent(Inject.class))
                .toList();
        return switch (constructors.size()){
            case 0 -> Optional.empty();
            case 1 -> Optional.of(constructors.get(0));
            default -> throw new IllegalStateException("Two many injectable constructors " + providerClass.getName());
        };
    }
}