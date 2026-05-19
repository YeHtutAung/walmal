package com.walmal.infrastructure.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@ComponentScan("com.walmal.infrastructure")
@EnableJpaRepositories("com.walmal.infrastructure")
public class InfrastructureAutoConfiguration {

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
