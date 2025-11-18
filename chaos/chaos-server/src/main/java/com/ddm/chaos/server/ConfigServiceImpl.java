package com.ddm.chaos.server;

import com.ddm.chaos.defined.ConfItem;
import com.ddm.chaos.defined.ConfRef;
import com.ddm.chaos.proto.*;
import com.ddm.chaos.provider.DataProvider;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 配置服务的 gRPC 实现。
 * <p>
 * 该服务通过 {@link DataProvider} 从数据源（如数据库）加载配置数据，
 * 并通过 gRPC 接口提供给客户端。
 *
 * @author liyifei
 * @since 1.0
 */
@GrpcService
public class ConfigServiceImpl extends ConfigServiceGrpc.ConfigServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ConfigServiceImpl.class);

    private final DataProvider dataProvider;

    /**
     * 构造配置服务实现。
     *
     * @param dataProvider 数据提供者，不能为 null
     */
    public ConfigServiceImpl(DataProvider dataProvider) {
        this.dataProvider = Objects.requireNonNull(dataProvider, "dataProvider required");
    }

    @Override
    public void loadConfig(LoadConfigRequest request, StreamObserver<LoadConfigResponse> responseObserver) {
        try {
            ConfigRef ref = request.getRef();
            if (ref == null) {
                log.warn("Received null ConfigRef in LoadConfigRequest");
                responseObserver.onNext(LoadConfigResponse.newBuilder()
                        .setNotFound(true)
                        .build());
                responseObserver.onCompleted();
                return;
            }

            // 转换为 ConfRef
            ConfRef confRef = new ConfRef(
                    ref.getNamespace(),
                    ref.getGroup(),
                    ref.getKey()
            );

            log.debug("Loading config via gRPC: {}", confRef);

            // 从数据提供者加载配置
            ConfItem confItem = dataProvider.loadData(confRef);

            if (confItem == null) {
                log.debug("Config item not found: {}", confRef);
                responseObserver.onNext(LoadConfigResponse.newBuilder()
                        .setNotFound(true)
                        .build());
            } else {
                log.trace("Successfully loaded config item: {} (value length: {})",
                        confRef, confItem.value() != null ? confItem.value().length() : 0);

                // 转换为 proto 消息
                ConfigItem protoItem = ConfigItem.newBuilder()
                        .setNamespace(confItem.namespace())
                        .setGroup(confItem.group())
                        .setKey(confItem.key())
                        .setValue(confItem.value() != null ? confItem.value() : "")
                        .setVariants(confItem.variants() != null ? confItem.variants() : "")
                        .build();

                responseObserver.onNext(LoadConfigResponse.newBuilder()
                        .setNotFound(false)
                        .setItem(protoItem)
                        .build());
            }

            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to load config via gRPC", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to load config: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }
}

