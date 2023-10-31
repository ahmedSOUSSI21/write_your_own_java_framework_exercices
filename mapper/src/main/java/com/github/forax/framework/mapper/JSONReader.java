package com.github.forax.framework.mapper;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.lang.reflect.Type;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class JSONReader {
  private record BeanData(Constructor<?> constructor, Map<String, PropertyDescriptor> propertyMap) {
    PropertyDescriptor findProperty(String key) {
      var property = propertyMap.get(key);
      if (property == null) {
        throw new IllegalStateException("unknown key " + key + " for bean " + constructor.getDeclaringClass().getName());
      }
      return property;
    }
  }

  public record ObjectBuilder<T>(Function<? super String, ? extends Type> typeProvider,
                                 Supplier<? extends T> supplier,
                                 Populater<? super T> populater,
                                 Function<? super T, ?> finisher) {
    public interface Populater<T> {
      void populate(T instance, String key, Object value);
    }

    public static ObjectBuilder<Object> bean(Class<?> beanClass){
      var beanData = BEAN_DATA_CLASS_VALUE.get(beanClass);
      var constructor = beanData.constructor;
      return new ObjectBuilder<>(
              key -> beanData.findProperty(key).getWriteMethod().getGenericParameterTypes()[0],
              () -> Utils.newInstance(constructor),
              (instance, key, value) -> {
                var setter = beanData.findProperty(key).getWriteMethod();
                Utils.invokeMethod(instance, setter, value);
              },
              Function.identity()
      );
    }

    public static ObjectBuilder<List<Object>> list(Type componentType){
      Objects.requireNonNull(componentType);
      return new ObjectBuilder<List<Object>>(
              key -> componentType,
              ArrayList::new,
              (instance, key, value) -> instance.add(value),
              List::copyOf
      );
    }

    public static ObjectBuilder<Object[]> record(Class<?> recordClass){
      var array = recordClass.getRecordComponents();
      var map = IntStream.range(0, array.length)
              .boxed()
              .collect(Collectors.toMap(i -> array[i].getName(), Function.identity()));
      var constructor = Utils.canonicalConstructor(recordClass, array);
      return new ObjectBuilder<Object[]>(
              key -> array[map.get(key)].getGenericType(),
              () -> new Object[array.length],
              (instance, key, value) -> instance[map.get(key)] = value,
              instance -> Utils.newInstance(constructor, instance)
      );
    }
  }

  @FunctionalInterface
  public interface TypeMatcher {
    Optional<ObjectBuilder<?>> match(Type type);
  }

  public interface TypeReference<T>{

  }
  private final ArrayList<TypeMatcher> typeMatchers = new ArrayList<>();

  public void addTypeMatcher(TypeMatcher typeMatcher){
    Objects.requireNonNull(typeMatcher);
    typeMatchers.add(typeMatcher);
  }

  private ObjectBuilder<?> findObjectBuilder(Type type){
    return typeMatchers.reversed().stream()
            .flatMap(typeMatcher -> typeMatcher.match(type).stream())
            .findFirst()
            .orElseGet(() -> ObjectBuilder.bean(Utils.erase(type)));
  }

  private record Context<T>(ObjectBuilder<T> objectBuilder, T result){
    void populate(String key, Object value){
      objectBuilder.populater.populate(result, key, value);
    }
    static <E> Context<E> createContext(ObjectBuilder<E> objectBuilder){
      var instance = objectBuilder.supplier.get();
      return new Context<E>(objectBuilder, instance);
    }

    Object finish(){
      return objectBuilder.finisher.apply(result);
    }
  }

  private static final ClassValue<BeanData> BEAN_DATA_CLASS_VALUE = new ClassValue<>() {
    @Override
    protected BeanData computeValue(Class<?> type) {
      var constructor = Utils.defaultConstructor(type);
      var map = Arrays.stream(Utils.beanInfo(type).getPropertyDescriptors())
              .filter(property -> !property.getName().equals("class"))
              .collect(Collectors.toMap(PropertyDescriptor::getName, Function.identity()));
      return new BeanData(constructor, map);
    }
  };

  public Object parseJSON(String text, Type type) {
    Objects.requireNonNull(text);
    Objects.requireNonNull(type);
    var stack = new ArrayDeque<Context<?>>();
    var visitor = new ToyJSONParser.JSONVisitor() {
      private Object result;

      @Override
      public void value(String key, Object value) {
        // call the corresponding setter on result
        var currentContext = stack.peek();
        currentContext.populate(key, value);
      }

      @Override
      public void startObject(String key) {
        // get the beanData
        var currentContext = stack.peek();
        var theType = currentContext == null ?
                type:
                currentContext.objectBuilder.typeProvider().apply(key);
        var objectBuilder = findObjectBuilder(theType);
        stack.push(Context.createContext(objectBuilder));
      }

      @Override
      public void endObject(String key) {
        var previousContext = stack.pop();
        var result = previousContext.finish();
        if(stack.isEmpty()){
          this.result = result;
        } else{
          var currentContext = stack.peek();
          currentContext.populate(key, result);
        }
      }

      @Override
      public void startArray(String key) {
        startObject(key);
      }

      @Override
      public void endArray(String key) {
        endObject(key);
      }
    };
    ToyJSONParser.parse(text, visitor);
    return visitor.result;
  }

  public <T> T parseJSON(String text, Class<T> beanClass) {
    return beanClass.cast(
            parseJSON(text, (Type) beanClass)
    );
  }

  /*
  public <T> T parseJSON(String text, TypeReference<T> typeReference) {
    return parseJSON(text, giveMeTheTypeOfTheTypeReference(typeReference));
  }

   */
}