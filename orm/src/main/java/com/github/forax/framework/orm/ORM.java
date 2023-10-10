package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
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
      connection.setAutoCommit(false);
      THREAD_LOCAL.set(connection);
      try {
        block.run();
        connection.commit();
      } catch(RuntimeException | SQLException e) {
        try {
          connection.rollback();
        } catch(SQLException suppressed) {
          e.addSuppressed(suppressed);
        }
        throw Utils.rethrow(e);
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

  public static void createTable(Class<?> beanClass) throws SQLException {
    Objects.requireNonNull(beanClass);

    var connection = currentConnection();

    var className = findTableName(beanClass);
    var names = Arrays.stream(Utils.beanInfo(beanClass).getPropertyDescriptors())
            .filter(propertyDescriptor -> !propertyDescriptor.getName().equals("class"))
            .map(propertyDescriptor -> {
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
                result += ",\nPRIMARY KEY ("+name+")";
              }
              return result;
            })
            .collect(Collectors.joining(", ", "(", ");"));

    var statement = connection.createStatement();
    var sqlRequest = "CREATE TABLE " + className + " " + names;
    statement.execute(sqlRequest);
    statement.close();
  }

}
