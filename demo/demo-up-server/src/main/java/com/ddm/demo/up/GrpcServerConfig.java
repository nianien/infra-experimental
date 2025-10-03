package com.ddm.demo.up;

import com.ddm.argus.trace.grpc.TraceInterceptor;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcServerConfig {

    @GrpcGlobalServerInterceptor
    @GrpcGlobalClientInterceptor
    public TraceInterceptor traceInterceptor() {
        return new TraceInterceptor();
    }
}
