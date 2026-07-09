package com.walmal.infrastructure.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Central configuration for walmal-infrastructure. Loaded via the application's
 * {@code com.walmal} component scan (not Spring Boot auto-configuration
 * registration — there is no {@code AutoConfiguration.imports} entry).
 */
@Configuration
@ComponentScan("com.walmal.infrastructure")
@EnableJpaRepositories("com.walmal.infrastructure")
@EnableScheduling  // activates OutboxRelay (and module jobs, e.g. inventory's ReservationExpiryJob)
public class InfrastructureConfiguration {

    @Bean
    public MinioClient minioClient(
            @Value("${walmal.minio.url}") String url,
            @Value("${walmal.minio.access-key}") String accessKey,
            @Value("${walmal.minio.secret-key}") String secretKey) {
        return MinioClient.builder()
            .endpoint(url)
            .credentials(accessKey, secretKey)
            .build();
    }
}
