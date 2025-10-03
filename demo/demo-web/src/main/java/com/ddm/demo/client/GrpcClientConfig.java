package com.ddm.demo.client;

import com.ddm.argus.trace.grpc.TraceInterceptor;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    @GrpcGlobalClientInterceptor
    public TraceInterceptor clientTraceInterceptor() {
        return new TraceInterceptor();
    }
}