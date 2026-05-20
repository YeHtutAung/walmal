package com.walmal.inventory.domain;

import com.walmal.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for InventoryReservation state machine transitions.
 */
class ReservationStatusTransitionTest {

    private InventoryLocation location;
    private InventoryReservation reservation;

    @BeforeEach
    void setUp() {
        location = mock(InventoryLocation.class);
        reservation = new InventoryReservation(
                UUID.randomUUID(), UUID.randomUUID(), location,
                10, Instant.now().plusSeconds(1800));
    }

    @Test
    @DisplayName("should_transitionToCONFIRMED_when_confirmCalledOnPENDING")
    void should_transitionToCONFIRMED_when_confirmCalledOnPENDING() {
        reservation.confirm();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_confirmCalledOnCONFIRMED")
    void should_throwBusinessRuleException_when_confirmCalledOnCONFIRMED() {
        reservation.confirm();
        assertThatThrownBy(reservation::confirm)
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only PENDING");
    }

    @Test
    @DisplayName("should_transitionToRELEASED_when_releaseCalledOnPENDING")
    void should_transitionToRELEASED_when_releaseCalledOnPENDING() {
        reservation.release(ConflictReason.CANCELLED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.getConflictReason()).isEqualTo(ConflictReason.CANCELLED);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_releaseCalledOnRELEASED")
    void should_throwBusinessRuleException_when_releaseCalledOnRELEASED() {
        reservation.release(ConflictReason.EXPIRED);
        assertThatThrownBy(() -> reservation.release(ConflictReason.EXPIRED))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already RELEASED");
    }

    @Test
    @DisplayName("should_returnTrue_when_reservationIsExpired")
    void should_returnTrue_when_reservationIsExpired() {
        InventoryReservation expired = new InventoryReservation(
                UUID.randomUUID(), UUID.randomUUID(), location,
                5, Instant.now().minusSeconds(60));
        assertThat(expired.isExpired()).isTrue();
    }

    @Test
    @DisplayName("should_returnFalse_when_reservationIsNotExpired")
    void should_returnFalse_when_reservationIsNotExpired() {
        assertThat(reservation.isExpired()).isFalse();
    }
}
