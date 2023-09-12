package com.github.forax.framework.mapper;

public interface Generator {
    String generate(JSONWriter writer, Object bean);
}

