package com.ddm.chaos.utils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public abstract class TypeRef<T> {

    private final Type type;

    protected TypeRef() {
        Type generic = getClass().getGenericSuperclass();
        if (generic instanceof ParameterizedType p) {
            this.type = p.getActualTypeArguments()[0];
        } else {
            throw new IllegalStateException(
                "TypeRef must be constructed with actual generic type, e.g. new TypeRef<List<String>>() {}"
            );
        }
    }

    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        return "TypeRef<" + type.getTypeName() + ">";
    }
}