package com.walmal.warehouse.domain;

import com.walmal.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FulfillmentOrderTest {

    private FulfillmentOrder fulfillment;

    @BeforeEach
    void setUp() {
        fulfillment = new FulfillmentOrder(UUID.randomUUID(), UUID.randomUUID(), "{}");
    }

    @Test
    @DisplayName("should_startInPendingStatus")
    void should_startInPendingStatus() {
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.PENDING);
    }

    @Test
    @DisplayName("should_allowPendingToPicking")
    void should_allowPendingToPicking() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, "Picker assigned");
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.PICKING);
        assertThat(fulfillment.getNotes()).isEqualTo("Picker assigned");
    }

    @Test
    @DisplayName("should_allowPickingToPacked")
    void should_allowPickingToPacked() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.PACKED);
    }

    @Test
    @DisplayName("should_allowPackedToShipped")
    void should_allowPackedToShipped() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);
        fulfillment.advanceTo(FulfillmentStatus.SHIPPED, null);
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.SHIPPED);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_skippingPickingPhase")
    void should_throwBusinessRuleException_when_skippingPickingPhase() {
        assertThatThrownBy(() -> fulfillment.advanceTo(FulfillmentStatus.PACKED, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("PACKED");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_advancingFromShipped")
    void should_throwBusinessRuleException_when_advancingFromShipped() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);
        fulfillment.advanceTo(FulfillmentStatus.SHIPPED, null);
        assertThatThrownBy(() -> fulfillment.advanceTo(FulfillmentStatus.PICKING, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("should_allowCancellationFromPending")
    void should_allowCancellationFromPending() {
        fulfillment.cancel();
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("should_allowCancellationFromPicking")
    void should_allowCancellationFromPicking() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.cancel();
        assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_cancellingPackedFulfillment")
    void should_throwBusinessRuleException_when_cancellingPackedFulfillment() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);
        assertThatThrownBy(() -> fulfillment.cancel())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PACKED");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_cancellingShippedFulfillment")
    void should_throwBusinessRuleException_when_cancellingShippedFulfillment() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);
        fulfillment.advanceTo(FulfillmentStatus.SHIPPED, null);
        assertThatThrownBy(() -> fulfillment.cancel())
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("should_reportIsCancellable_when_pendingOrPicking")
    void should_reportIsCancellable_when_pendingOrPicking() {
        assertThat(fulfillment.isCancellable()).isTrue();
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        assertThat(fulfillment.isCancellable()).isTrue();
    }

    @Test
    @DisplayName("should_reportNotCancellable_when_packedOrShipped")
    void should_reportNotCancellable_when_packedOrShipped() {
        fulfillment.advanceTo(FulfillmentStatus.PICKING, null);
        fulfillment.advanceTo(FulfillmentStatus.PACKED, null);
        assertThat(fulfillment.isCancellable()).isFalse();
        fulfillment.advanceTo(FulfillmentStatus.SHIPPED, null);
        assertThat(fulfillment.isCancellable()).isFalse();
    }
}
