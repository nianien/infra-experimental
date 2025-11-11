package com.ddm.chaos.defined;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * 配置缓存键，用于唯一标识同一配置项在某种类型下的缓存槽位。
 * <p>
 * 缓存键由配置引用（{@code ConfRef}）和目标类型（{@code Type}）组成，
 * 忽略默认值（{@code defaultValue}），因此相同配置项和类型的描述符会共享同一个缓存槽位。
 *
 * @author liyifei
 */
public record ConfSlot(ConfRef ref, Type type) {
    public ConfSlot(ConfRef ref, Type type) {
        this.ref = Objects.requireNonNull(ref, "ref cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConfSlot k)) return false;
        return Objects.equals(ref, k.ref)
                && Objects.equals(typeName(type), typeName(k.type));
    }

    @Override
    public int hashCode() {
        return Objects.hash(ref, typeName(type));
    }

    private static String typeName(Type t) {
        return (t == null) ? "null" : t.getTypeName();
    }

    @Override
    public String toString() {
        return ref + " <" + typeName(type) + ">";
    }
}

