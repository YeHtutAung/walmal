package com.walmal.gateway.exception;

import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.payment.WebhookVerificationException;
import com.walmal.order.api.OrderExceptionHandler;
import com.walmal.order.api.PaymentWebhookController;
import com.walmal.order.application.PaymentWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for a real bug found during Task 4 live verification: with
 * both {@code OrderExceptionHandler} (module-scoped) and
 * {@code GlobalExceptionHandler} (app-wide, {@code @Order(LOWEST_PRECEDENCE)})
 * registered in the same context, Spring resolves {@code @ExceptionHandler}
 * advice by consulting beans in {@code @Order} order — but a bean with NO
 * {@code @Order} annotation is ALSO treated as {@code LOWEST_PRECEDENCE}, so
 * {@code OrderExceptionHandler} tied with {@code GlobalExceptionHandler}
 * rather than running first as {@code GlobalExceptionHandler}'s own javadoc
 * assumes ("module handlers are consulted first"). For any exception type
 * only {@code OrderExceptionHandler} declares a specific handler for (like
 * {@link WebhookVerificationException}), the tie-break landed on
 * {@code GlobalExceptionHandler} first in this app's bean registration order,
 * whose {@code Exception.class} catch-all then swallowed it into a 500 —
 * silently masking the intended 400. This never showed up in
 * {@code PaymentWebhookControllerTest} (walmal-order) because that
 * {@code @WebMvcTest} only imports {@code OrderExceptionHandler}, not
 * {@code GlobalExceptionHandler} — this test imports both, exactly as the
 * real running app does, to catch the interaction that a narrower test can't.
 */
@WebMvcTest(controllers = PaymentWebhookController.class)
// GlobalExceptionHandler deliberately imported BEFORE OrderExceptionHandler —
// the worst-case registration order. Only the module advice's explicit
// @Order(0) makes this pass; a tie would let Global's catch-all 500 win.
@Import({PaymentWebhookController.class, AuthSecurityConfig.class, GlobalExceptionHandler.class, OrderExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-precedence-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15"
})
class ExceptionHandlerPrecedenceTest {

    /**
     * Minimal boot configuration for this slice. Without it, @WebMvcTest
     * walks up to {@code WalmalApplication}, whose class-level
     * {@code @EnableJpaRepositories}/{@code @EntityScan} survive slicing and
     * demand an entityManagerFactory the web slice doesn't have — the same
     * reason every domain module keeps a bare {@code XxxTestApplication}.
     */
    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class SliceConfig {}

    @Autowired MockMvc mockMvc;

    @MockitoBean PaymentWebhookService paymentWebhookService;
    @MockitoBean TokenValidationService tokenValidationService;

    @Test
    void should_return400_notGlobalHandler500_when_webhookSignatureInvalid() throws Exception {
        doThrow(new WebhookVerificationException("Invalid Stripe webhook signature"))
                .when(paymentWebhookService).handle(anyString(), anyString());

        mockMvc.perform(post("/api/v1/payment/webhook")
                        .header("Stripe-Signature", "t=1,v1=bogus")
                        .contentType("application/json")
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isBadRequest());
    }
}
