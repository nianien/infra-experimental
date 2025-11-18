package com.ddm.chaos.utils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 类型引用，用于在运行时获取泛型类型信息，解决 Java 泛型类型擦除问题。
 * <p>
 * 由于 Java 的泛型类型擦除机制，在运行时无法直接获取泛型类型信息。
 * 例如，对于 {@code List<String>}，在运行时只能获取到 {@code List}，而无法获取到 {@code String}。
 * 通过使用 {@code TypeRef}，可以在运行时获取完整的泛型类型信息。
 *
 * <p><strong>工作原理：</strong>
 * 通过创建匿名内部类的方式，利用 Java 的泛型继承机制，从 {@link ParameterizedType} 中提取泛型类型参数。
 * 构造方法会检查类的泛型超类，如果是一个参数化类型，则提取第一个类型参数作为目标类型。
 *
 * <p><strong>使用方式：</strong>
 * 必须通过匿名内部类的方式创建实例，并指定具体的泛型类型：
 * <pre>{@code
 * // 获取 List<String> 的类型信息
 * TypeRef<List<String>> typeRef = new TypeRef<List<String>>() {};
 * Type type = typeRef.getType();
 *
 * // 获取 Map<String, Integer> 的类型信息
 * TypeRef<Map<String, Integer>> mapTypeRef = new TypeRef<Map<String, Integer>>() {};
 * Type mapType = mapTypeRef.getType();
 * }</pre>
 *
 * <p><strong>注意事项：</strong>
 * <ul>
 *   <li>必须使用匿名内部类的方式创建实例（注意末尾的 {@code {} }）</li>
 *   <li>不能直接实例化，必须指定具体的泛型类型</li>
 *   <li>如果未指定泛型类型或构造方式不正确，会抛出 {@link IllegalStateException}</li>
 * </ul>
 *
 * <p><strong>使用场景：</strong>
 * <ul>
 *   <li>在配置描述符（{@link com.ddm.chaos.defined.ConfDesc}）中指定泛型类型</li>
 *   <li>在类型转换工具（{@link Converters}）中获取泛型类型信息</li>
 *   <li>在需要运行时获取泛型类型信息的场景中使用</li>
 * </ul>
 *
 * <p><strong>示例：</strong>
 * <pre>{@code
 * // 在配置描述符中使用
 * ConfDesc desc = new ConfDesc(
 *     ref,
 *     Collections.emptyList(),
 *     new TypeRef<List<String>>() {}
 * );
 *
 * // 在类型转换中使用
 * List<String> list = Converters.cast(
 *     "abc,xyz",
 *     new TypeRef<List<String>>(){}.getType()
 * );
 * }</pre>
 *
 * @param <T> 要引用的泛型类型
 * @author liyifei
 * @see java.lang.reflect.Type
 * @see java.lang.reflect.ParameterizedType
 * @see com.ddm.chaos.defined.ConfDesc
 * @since 1.0
 */
public abstract class TypeRef<T> implements Comparable<TypeRef<T>> {

    /**
     * 缓存的类型信息，在构造时从泛型超类中提取
     */
    private final Type type;

    /**
     * 构造类型引用。
     * <p>
     * 从类的泛型超类中提取泛型类型参数。如果类的泛型超类是一个参数化类型（{@link ParameterizedType}），
     * 则提取第一个类型参数作为目标类型；否则抛出异常。
     *
     * <p><strong>构造要求：</strong>
     * 必须通过匿名内部类的方式创建实例，并指定具体的泛型类型。
     * 例如：{@code new TypeRef<List<String>>() {}}
     *
     * @throws IllegalStateException 如果未指定泛型类型或构造方式不正确
     */
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

    /**
     * 获取引用的类型信息。
     * <p>
     * 返回在构造时从泛型超类中提取的类型参数。
     *
     * @return 引用的类型信息，不会为 null
     */
    public Type getType() {
        return type;
    }

    /**
     * 返回类型引用的字符串表示。
     * <p>
     * 格式为：{@code "TypeRef<类型名称>"}
     *
     * @return 类型引用的字符串表示
     */
    @Override
    public String toString() {
        return "TypeRef<" + type.getTypeName() + ">";
    }

    /**
     * 比较两个类型引用。
     * <p>
     * 当前实现始终返回 0，表示所有类型引用在比较时都相等。
     * 此方法主要用于实现 {@link Comparable} 接口，以满足某些集合类型的要求。
     *
     * @param o 要比较的类型引用
     * @return 始终返回 0
     */
    @Override
    public int compareTo(TypeRef<T> o) {
        return 0;
    }
}