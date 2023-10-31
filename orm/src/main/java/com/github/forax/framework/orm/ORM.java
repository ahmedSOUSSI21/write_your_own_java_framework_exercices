package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ORM {
  private ORM() {
    throw new AssertionError();
  }
  private static final ThreadLocal<Connection> THREAD_LOCAL = new ThreadLocal<>();
  @FunctionalInterface
  public interface TransactionBlock {
    void run() throws SQLException;
  }

  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
      int.class, "INTEGER",
      Integer.class, "INTEGER",
      long.class, "BIGINT",
      Long.class, "BIGINT",
      String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
        .flatMap(superInterface -> {
          if (superInterface instanceof ParameterizedType parameterizedType
              && parameterizedType.getRawType() == Repository.class) {
            return Stream.of(parameterizedType);
          }
          return null;
        })
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }

  private static class UncheckedSQLException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }

    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }

  public static void transaction(DataSource dataSource, TransactionBlock block) throws SQLException {
    Objects.requireNonNull(dataSource);
    Objects.requireNonNull(block);
    try(var connection = dataSource.getConnection()) {
      THREAD_LOCAL.set(connection);
      try {
        connection.setAutoCommit(false);
        block.run();
        connection.commit();
      } catch(SQLException e) {
        connection.rollback();
        throw e;
      }
      catch (UncheckedSQLException e){
        connection.rollback();
        throw e.getCause();
      } finally{
        THREAD_LOCAL.remove();
      }
    }
  }

  public static Connection currentConnection() {
    var connection = THREAD_LOCAL.get();
    if (connection == null) {
      throw new IllegalStateException("No active transaction.");
    }
    return connection;
  }

  public static String findTableName(Class<?> beanClass){
    var annotation = beanClass.getAnnotation(Table.class);
    var result = annotation != null ? annotation.value() : beanClass.getSimpleName();
    return result.toUpperCase(Locale.ROOT);
  }

  public static String findColumnName(PropertyDescriptor property){
    var annotation =  property.getReadMethod().getAnnotation(Column.class);
    var result = annotation != null ? annotation.value() : property.getName();
    return result.toUpperCase(Locale.ROOT);
  }

  private static String getColumn(PropertyDescriptor propertyDescriptor) {
    var name = findColumnName(propertyDescriptor);
    var type = TYPE_MAPPING.get(propertyDescriptor.getPropertyType());
    var result = name + " " + type;
    if(propertyDescriptor.getPropertyType().isPrimitive()){
      result += " NOT NULL";
    }
    if(propertyDescriptor.getReadMethod().isAnnotationPresent(GeneratedValue.class)){
      result += " AUTO_INCREMENT";
    }
    if(propertyDescriptor.getReadMethod().isAnnotationPresent(Id.class)){
      result += ", PRIMARY KEY ("+name+")";
    }
    return result;
  }

  private static String getNames(Class<?> beanClass) {
    return Arrays.stream(Utils.beanInfo(beanClass).getPropertyDescriptors())
            .filter(propertyDescriptor -> !propertyDescriptor.getName().equals("class"))
            .map(ORM::getColumn)
            .collect(Collectors.joining(", ", "(", ");"));
  }

  public static void createTable(Class<?> beanClass) throws SQLException {
    Objects.requireNonNull(beanClass);
    var connection = currentConnection();
    var className = findTableName(beanClass);
    var names = getNames(beanClass);
    var statement = connection.createStatement();
    var sqlRequest = "CREATE TABLE " + className + " " + names;
    statement.execute(sqlRequest);
    statement.close();
  }

  static PropertyDescriptor findProperty(BeanInfo beanInfo, String name){
    return Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> property.getName().equals(name))
            .findFirst()
            .orElseThrow(IllegalStateException::new);
  }

  public static <T, ID, R extends Repository<T, ID>> R createRepository(Class<? extends R> type) {
    var beanType = findBeanTypeFromRepository(type);
    var constructor = Utils.defaultConstructor(beanType);
    var beanInfo = Utils.beanInfo(beanType);
    var tableName = findTableName(beanType);
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
      var name = method.getName();
      try {
        return switch(name) {
          case "findAll" -> findAll(currentConnection(), "SELECT * FROM " +tableName, beanInfo, constructor);
          case "findById" -> findAll(currentConnection(), "SELECT * FROM "+tableName+" WHERE "+findColumnName(findId(beanInfo))+" = ?",
                  beanInfo, constructor, args[0])
                  .stream()
                  .findFirst();
          case "save" -> save(currentConnection(), tableName, beanInfo, args[0], findId(beanInfo));
          case "equals", "hashCode", "toString" -> throw new UnsupportedOperationException("" + method);
          default -> {
            var query = method.getAnnotation(Query.class);
            if(query != null){
              yield findAll(currentConnection(), query.value(), beanInfo, constructor, args);
            }
            if(name.startsWith("findBy")){
              var propertyName = Introspector.decapitalize(name.substring(6));
              var columnName = findColumnName(findProperty(beanInfo, propertyName));
              yield findAll(currentConnection(), "SELECT * FROM "+tableName+" WHERE "+ columnName +" = ?",
                      beanInfo, constructor, args[0])
                      .stream()
                      .findFirst();
            }
            throw new IllegalStateException("unknown method " + method);
          }
        };
      } catch(SQLException e) {
        throw new UncheckedSQLException(e);
      }
    }));
  }

  static Object toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
    var instance = Utils.newInstance(constructor);
    for(var property: beanInfo.getPropertyDescriptors()) {
      var propertyName = property.getName();
      if (propertyName.equals("class")) {
        continue;
      }
      var value = resultSet.getObject(propertyName);
      Utils.invokeMethod(instance, property.getWriteMethod(), value);
    }
    return instance;
  }

  static List<Object> findAll(Connection connection,String sqlQuery,BeanInfo beanInfo, Constructor<?> constructor, Object... args) throws SQLException {
    var list = new ArrayList<>();
    try(var statement = connection.prepareStatement(sqlQuery)) {
      if(args != null) {
        for (var i = 0; i < args.length; i++) {
          statement.setObject(i + 1, args[i]);
        }
      }
      try(var resultSet = statement.executeQuery()) {
        while(resultSet.next()) {
          var instance = toEntityClass(resultSet, beanInfo, constructor);
          list.add(instance);
        }
      }
    }
    return list;
  }

  static String createSaveQuery(String tableName, BeanInfo beanInfo){
    var properties = beanInfo.getPropertyDescriptors();
    return """
            MERGE INTO %s %s VALUES (%s);\
            """
            .formatted(
                    tableName,
                    Arrays.stream(properties)
                            .filter(property -> !property.getName().equals("class"))
                            .map(ORM::findColumnName)
                            .collect(Collectors.joining(", ", "(", ")")),
                    String.join(", ", Collections.nCopies(properties.length - 1, "?"))
            );

  }

  static Object save(Connection connection,String tableName,BeanInfo beanInfo, Object bean, PropertyDescriptor idProperty) throws SQLException{
    var query = createSaveQuery(tableName, beanInfo);
    try(var statement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
      int index = 1;
      for(var property: beanInfo.getPropertyDescriptors()){
        if(property.getName().equals("class")){
          continue;
        }
        var value = Utils.invokeMethod(bean, property.getReadMethod());
        statement.setObject(index++, value);
      }
      statement.executeUpdate();
      if(idProperty != null) {
        try (ResultSet resultSet = statement.getGeneratedKeys()) {
          if (resultSet.next()) {
            var idValue = resultSet.getObject(1);
            Utils.invokeMethod(bean, idProperty.getWriteMethod(), idValue);
          }
        }
      }
      return bean;
    }
  }

  static PropertyDescriptor findId(BeanInfo beanInfo){
    var propertyIds = Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> property.getReadMethod().isAnnotationPresent(Id.class))
            .toList();
    return switch (propertyIds.size()){
      case 0 -> throw new IllegalStateException("NO @Id defined on any getter");
      case 1 -> propertyIds.getFirst();
      default -> throw new IllegalStateException();
    };
  }
}

