package com.ltplatform.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(LtPlatformProperties.class)
public class AppConfig {

    @Bean(name = "orchestrationExecutor")
    ThreadPoolTaskExecutor orchestrationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("orch-");
        executor.initialize();
        return executor;
    }

    @Bean
    PathInitializer pathInitializer(LtPlatformProperties props) {
        return new PathInitializer(props);
    }

    public static class PathInitializer {
        public PathInitializer(LtPlatformProperties props) {
            try {
                Files.createDirectories(Path.of(props.getArtifactRoot()));
                Files.createDirectories(Path.of(props.getLogRoot()));
                Files.createDirectories(Path.of(props.getGrpc().getCertsDir()));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create data directories", e);
            }
        }
    }
}
