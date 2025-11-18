package com.ddm.chaos.resolver;

import com.ddm.chaos.factory.ConfData;
import com.ddm.chaos.utils.TypeRef;

import java.lang.reflect.Type;

/**
 * 配置描述符，表示开发者声明的配置注入信息。
 * <p>
 * 配置描述符包含三个核心信息：
 * <ul>
 *   <li><strong>ref</strong>：配置引用（{@link ConfRef}），标识要注入的配置项</li>
 *   <li><strong>type</strong>：目标类型（{@link Type}），配置值需要转换的目标类型</li>
 *   <li><strong>defaultValue</strong>：默认值，当配置不存在或转换失败时使用的默认值</li>
 * </ul>
 *
 * <p><strong>使用场景：</strong>
 * 配置描述符通常从 {@code @Conf} 注解中提取，用于描述需要注入到字段或方法参数的配置信息。
 * 通过配置描述符，可以从配置中心获取配置数据并转换为目标类型。
 *
 * <p><strong>类型转换：</strong>
 * 配置值（字符串形式）会根据 type 字段指定的目标类型进行转换。
 * 如果转换失败或配置不存在，则使用 defaultValue 作为返回值。
 *
 * @param ref          配置引用，标识要注入的配置项，不能为 null
 * @param defaultValue 默认值，当配置不存在或转换失败时使用，可以为 null
 * @param type         目标类型，配置值需要转换的目标类型，不能为 null
 * @author liyifei
 * @see ConfRef
 * @see ConfData
 * @since 1.0
 */
public record ConfDesc(ConfRef ref, Object defaultValue, Type type) {

    /**
     * 使用类型引用（TypeRef）创建配置描述符的静态工厂方法。
     * <p>
     * 这是一个便利的静态工厂方法，通过 {@link TypeRef} 来指定泛型类型，避免类型擦除问题。
     * 当需要处理泛型类型（如 {@code List<String>}、{@code Map<String, Integer>} 等）时，
     * 使用此方法可以正确获取泛型类型信息。
     *
     * <p><strong>使用示例：</strong>
     * <pre>{@code
     * // 示例 1：处理 List<String> 类型
     * ConfRef ref = new ConfRef("app", "database", "hosts");
     * ConfDesc listDesc = ConfDesc.of(
     *     ref,
     *     Collections.emptyList(),  // 默认值
     *     new TypeRef<List<String>>() {}  // 类型引用
     * );
     *
     * // 示例 2：处理 Map<String, Integer> 类型
     * ConfRef mapRef = new ConfRef("app", "cache", "timeouts");
     * ConfDesc mapDesc = ConfDesc.of(
     *     mapRef,
     *     Collections.emptyMap(),  // 默认值
     *     new TypeRef<Map<String, Integer>>() {}  // 类型引用
     * );
     *
     * // 示例 3：处理 Set<String> 类型
     * ConfRef setRef = new ConfRef("app", "security", "allowed-ips");
     * ConfDesc setDesc = ConfDesc.of(
     *     setRef,
     *     Collections.emptySet(),  // 默认值
     *     new TypeRef<Set<String>>() {}  // 类型引用
     * );
     * }</pre>
     *
     * <p><strong>注意事项：</strong>
     * <ul>
     *   <li>必须使用匿名内部类的方式创建 {@code TypeRef} 实例（注意末尾的 {@code {} }）</li>
     *   <li>泛型类型参数必须与 defaultValue 的类型匹配</li>
     *   <li>如果 defaultValue 为 null，则类型由 typeRef 决定</li>
     * </ul>
     *
     * @param <T>          目标类型的泛型参数
     * @param ref          配置引用，标识要注入的配置项，不能为 null
     * @param defaultValue 默认值，当配置不存在或转换失败时使用，可以为 null。类型应与 typeRef 的泛型参数匹配
     * @param typeRef      类型引用，用于获取泛型类型信息，不能为 null。必须通过匿名内部类方式创建
     * @return 配置描述符实例
     * @see TypeRef
     */
    public static <T> ConfDesc of(ConfRef ref, T defaultValue, TypeRef<T> typeRef) {
        return new ConfDesc(ref, defaultValue, typeRef.getType());
    }
}
