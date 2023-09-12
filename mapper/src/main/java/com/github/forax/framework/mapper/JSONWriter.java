package com.github.forax.framework.mapper;

import com.sun.jdi.Method;

import java.beans.BeanInfo;
import java.beans.FeatureDescriptor;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;
import java.lang.ClassValue;
import java.util.List;
public final class JSONWriter {

  private static final ClassValue<List<Generator>> CACHE = new ClassValue<>() {
    @Override
    protected List<Generator> computeValue(Class<?> type) {
      var beanInfo = Utils.beanInfo(type);
      return Arrays.stream(beanInfo.getPropertyDescriptors())
              .filter(property -> !property.getName().equals("class"))
              .<Generator>map(property ->{
                var method = property.getReadMethod();
                var annotation = method.getAnnotation(JSONProperty.class);
                var  name = annotation != null? annotation.value() : property.getName();
                var key = "\"" + name + "\": ";
                return ((writer, o) -> {
                  return key + writer.toJSON(Utils.invokeMethod(o, method));
                });
              })
              .toList();
    }
  };

  private String toJsonBean(Object o){

    return CACHE.get(o.getClass()).stream()
            .map(generator -> generator.generate(this, o))
            .collect(Collectors.joining(", ", "{", "}"));
  }

  public String toJSON(Object o) {

    return switch (o){
      case null -> "null";
      case Boolean b -> b.toString();
      case Integer i -> ""+i;
      case Double d -> ""+d;
      case String string -> "\"" +string+ "\"";
      default -> toJsonBean(o);
    };
  }

  public static void main(String [] args){
    record Person(String name, int age){

    }

    var person = new Person("Ahmed", 23);
    var jsonwriter = new JSONWriter();
    var json = jsonwriter.toJSON(person);

    System.out.println(json);
  }
}
