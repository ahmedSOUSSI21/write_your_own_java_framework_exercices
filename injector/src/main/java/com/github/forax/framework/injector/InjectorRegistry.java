package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class InjectorRegistry {
    private final HashMap<Class<?>, Supplier<?>> instances = new HashMap<>();

    /**
     * Registers the class type with the instance instance
     * @param type the class to register
     * @param instance the instance to re
     * @param <T> t
     */
    public <T> void registerInstance(Class<T> type, T instance){
        Objects.requireNonNull(instance);
        registerProvider(type, () -> instance);
    }

    /**
     *
     * @param type
     * @return
     * @param <T>
     */
    public <T> T lookupInstance(Class<T> type){
        Objects.requireNonNull(type);
        var result = instances.get(type);

        if(result == null){
            throw new IllegalStateException("No instance registered for this class " + type.getName());
        }
        return type.cast(result.get());
    }

    /**
     *
     * @param type
     * @param supplier
     * @param <T>
     */
    public <T> void registerProvider(Class<T> type, Supplier<? extends T> supplier){
        Objects.requireNonNull(type);
        Objects.requireNonNull(supplier);
        instances.putIfAbsent(type, supplier);
    }

    /**
     *
     * @param type
     * @return
     * @param <T>
     */
    public static <T>  List<PropertyDescriptor> findInjectableProperties(Class<T> type){
        Objects.requireNonNull(type);
        return Arrays.stream(Utils.beanInfo(type).getPropertyDescriptors())
                .filter(prop -> prop.getWriteMethod() != null && prop.getWriteMethod().getAnnotation(Inject.class) != null)
                .toList();
    }

    /**
     *
     * @param type
     * @param providerClass
     * @param <T>
     */
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

    /**
     *
     * @param providerClass
     * @param <T>
     */
    public void registerProviderClass(Class<?> providerClass){
        Objects.requireNonNull(providerClass);
        registerProviderClassImpl(providerClass);
    }

    private  <T> void registerProviderClassImpl(Class<T> providerClass){
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