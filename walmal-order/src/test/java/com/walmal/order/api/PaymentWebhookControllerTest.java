package com.walmal.order.api;

import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.payment.WebhookVerificationException;
import com.walmal.order.application.PaymentWebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link PaymentWebhookController}. Imports AuthSecurityConfig
 * to prove the endpoint is actually reachable without a JWT (permitAll) — the
 * request's own auth is the Stripe signature, checked inside the mocked
 * service, not Spring Security.
 */
@WebMvcTest(controllers = PaymentWebhookController.class)
@Import({AuthSecurityConfig.class, OrderExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-controller-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15"
})
class PaymentWebhookControllerTest {

    private static final String WEBHOOK_PATH = "/api/v1/payment/webhook";

    @Autowired MockMvc mockMvc;

    @MockitoBean PaymentWebhookService paymentWebhookService;
    @MockitoBean TokenValidationService tokenValidationService;

    @Test
    @DisplayName("should_return200_when_serviceHandlesEventWithoutError_and_noJwtRequired")
    void should_return200_when_serviceHandlesSuccessfully() throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .header("Stripe-Signature", "t=1,v1=deadbeef")
                        .contentType("application/json")
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isOk());

        verify(paymentWebhookService).handle(eq("{\"id\":\"evt_1\"}"), eq("t=1,v1=deadbeef"));
    }

    @Test
    @DisplayName("should_return400_when_signatureHeaderMissing_and_serviceNeverCalled")
    void should_return400_when_signatureHeaderMissing() throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType("application/json")
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentWebhookService);
    }

    @Test
    @DisplayName("should_return400_when_serviceThrowsWebhookVerificationException")
    void should_return400_when_signatureInvalid() throws Exception {
        doThrow(new WebhookVerificationException("Invalid Stripe webhook signature"))
                .when(paymentWebhookService).handle(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header("Stripe-Signature", "t=1,v1=bogus")
                        .contentType("application/json")
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isBadRequest());
    }
}
