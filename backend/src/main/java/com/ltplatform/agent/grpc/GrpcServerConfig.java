package com.ltplatform.agent.grpc;

import com.ltplatform.config.LtPlatformProperties;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcServerConfig {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    private Server server;

    @Bean
    Server grpcServer(LtPlatformProperties props, AgentControlGrpcService service) throws IOException {
        server = NettyServerBuilder.forPort(props.getGrpc().getPort())
                .addService(service)
                .addService(ProtoReflectionService.newInstance())
                .maxInboundMessageSize(64 * 1024 * 1024)
                .build()
                .start();
        log.info("gRPC server started on port {}", props.getGrpc().getPort());
        return server;
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
