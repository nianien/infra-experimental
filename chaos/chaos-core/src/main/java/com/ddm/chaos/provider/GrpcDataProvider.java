package com.ddm.chaos.provider;

import com.ddm.chaos.resolver.ConfRef;
import com.ddm.chaos.proto.ConfigItem;
import com.ddm.chaos.proto.ConfigRef;
import com.ddm.chaos.proto.ConfigServiceGrpc;
import com.ddm.chaos.proto.ConfigServiceGrpc.ConfigServiceBlockingStub;
import com.ddm.chaos.proto.LoadConfigRequest;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 基于 gRPC 的数据提供者实现。
 * <p>
 * 通过 gRPC 调用远程配置服务来获取配置数据。
 * <p>
 * <strong>使用方式：</strong>
 * 通过构造函数传入已配置的 gRPC 客户端存根（ConfigServiceBlockingStub），
 * 由外部（如 Spring）管理 gRPC 通道的生命周期。
 *
 * @author liyifei
 * @see DataProvider
 * @since 1.0
 */
public class GrpcDataProvider implements DataProvider {

    private static final Logger log = LoggerFactory.getLogger(GrpcDataProvider.class);

    private final ConfigServiceGrpc.ConfigServiceBlockingStub stub;

    /**
     * 构造 gRPC 数据提供者。
     * <p>
     * 使用传入的 gRPC 客户端存根进行远程调用。
     * 存根的生命周期由外部管理，本类不负责关闭。
     *
     * @param stub gRPC 客户端存根，不能为 null
     * @throws NullPointerException 如果 stub 为 null
     */
    public GrpcDataProvider(ConfigServiceBlockingStub stub) {
        this.stub = Objects.requireNonNull(stub, "stub required");
    }


    /**
     * 通过 gRPC 调用远程服务加载配置项。
     *
     * @param ref 配置引用，包含 namespace、group、key
     * @return 配置项数据，如果未找到则返回 null
     * @throws IllegalStateException 如果 gRPC 调用失败
     */
    @Override
    public ConfItem loadData(ConfRef ref) {
        try {
            log.debug("Loading config via gRPC: {}", ref);

            // 构建请求
            ConfigRef protoRef = ConfigRef.newBuilder()
                    .setNamespace(ref.namespace())
                    .setGroup(ref.group())
                    .setKey(ref.key())
                    .build();

            LoadConfigRequest request = LoadConfigRequest.newBuilder()
                    .setRef(protoRef)
                    .build();

            // 调用 gRPC 服务
            var response = stub.loadConfig(request);

            if (response.getNotFound()) {
                log.debug("Config item not found via gRPC: {}", ref);
                return null;
            }

            // 转换为 ConfItem
            ConfigItem protoItem = response.getItem();
            ConfItem item = new ConfItem(
                    protoItem.getNamespace(),
                    protoItem.getGroup(),
                    protoItem.getKey(),
                    protoItem.getValue(),
                    protoItem.getVariants()
            );

            log.trace("Successfully loaded config item via gRPC: {} (value length: {})",
                    ref, item.value() != null ? item.value().length() : 0);

            return item;
        } catch (StatusRuntimeException e) {
            log.error("gRPC call failed for config: {} (status: {})", ref, e.getStatus(), e);
            throw new IllegalStateException(
                    String.format("Failed to load config via gRPC: %s (status: %s)", ref, e.getStatus()), e);
        } catch (Exception e) {
            log.error("Unexpected error while loading config via gRPC: {}", ref, e);
            throw new IllegalStateException(
                    String.format("Failed to load config via gRPC: %s", ref), e);
        }
    }


}

