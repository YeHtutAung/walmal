package com.walmal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.minio.MinioClient;

@Tag("integration")
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration",
    "spring.datasource.url=jdbc:postgresql://localhost:5432/walmal",
    "spring.datasource.username=walmal",
    "spring.datasource.password=walmal",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "walmal.minio.url=http://localhost:9000",
    "walmal.minio.access-key=test",
    "walmal.minio.secret-key=test1234",
    "walmal.jwt.secret=integration-test-secret-key-walmal-app-minimum-256-bits",
    "walmal.jwt.access-token-expire-minutes=15",
    // Fail-closed gateway: the stub bean only exists when this is set to `stub`.
    // This full-context test runs on the default profile, so it must opt in
    // explicitly (the running app opts in via the `test` profile).
    "walmal.payment.gateway=stub",
    // StripeWebhookVerifierImpl is registered unconditionally (not gated on the
    // gateway property above) and fails fast at construction if this is blank —
    // this full-context test runs on the default profile, which has no default
    // (see application.yml), so it must supply one explicitly, same as the JWT
    // secret and gateway properties above.
    "walmal.payment.stripe.webhook-secret=integration-test-webhook-secret"
})
class WalmalApplicationTest {

    @MockitoBean
    private MinioClient minioClient;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @Test
    void should_loadApplicationContext_when_started() {
        // Context loads successfully = pass
        // Requires Docker Compose services running (postgres on localhost:5432)
    }
}
