package com.github.forax.framework.mapper;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.lang.ClassValue;
import java.util.List;
public final class JSONWriter {

  private static final ClassValue<List<Generator>> CACHE = new ClassValue<>() {
    @Override
    protected List<Generator> computeValue(Class<?> type) {
      List<PropertyDescriptor> list = type.isRecord() ? recordProperties(type) : beanProperties(type);
    	
      return list.stream()
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
  
  private static List<PropertyDescriptor> beanProperties(Class<?> type) {
    return Arrays.stream(Utils.beanInfo(type).getPropertyDescriptors()).toList();
  }

  private static List<PropertyDescriptor> recordProperties(Class<?> type) {
	return Arrays.stream(type.getRecordComponents())
			.map(component -> {
			  PropertyDescriptor pd = null;
			  try {
			    pd = new PropertyDescriptor(component.getName(), component.getAccessor(), null);
			  } catch (IntrospectionException e) {
				e.printStackTrace();
			  }
			  return pd;
			})
			.toList();
  }
  
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

}
