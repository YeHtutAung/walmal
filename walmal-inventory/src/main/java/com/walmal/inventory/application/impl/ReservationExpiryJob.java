package com.walmal.inventory.application.impl;

import com.walmal.common.cache.DistributedLockService;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.inventory.domain.ConflictReason;
import com.walmal.inventory.domain.InventoryReservation;
import com.walmal.inventory.infrastructure.InventoryReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled job that expires stale PENDING reservations.
 *
 * <p>Runs on a configurable interval (default 60 s). Uses {@link DistributedLockService}
 * (Redis-backed) to prevent duplicate execution across multiple application instances.</p>
 *
 * <p>For each expired reservation: calls {@code releaseReservation(orderId, EXPIRED)},
 * which writes audit_log, returns stock to available pool, and publishes
 * {@code inventory.reservation.released}.</p>
 */
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);
    private static final String LOCK_KEY = "inventory:expiry-job";
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(55);

    private final InventoryReservationRepository reservationRepo;
    private final InventoryReservationService reservationService;
    private final DistributedLockService lockService;

    public ReservationExpiryJob(InventoryReservationRepository reservationRepo,
                                  InventoryReservationService reservationService,
                                  DistributedLockService lockService) {
        this.reservationRepo = reservationRepo;
        this.reservationService = reservationService;
        this.lockService = lockService;
    }

    @Scheduled(fixedDelayString = "${inventory.reservation.expiry-job-interval-ms:60000}")
    public void expireStaleReservations() {
        boolean locked = lockService.tryLock(LOCK_KEY, LOCK_TIMEOUT);
        if (!locked) {
            log.debug("Reservation expiry job skipped — another instance holds the lock.");
            return;
        }

        try {
            List<InventoryReservation> expired =
                    reservationRepo.findExpiredReservations(Instant.now());

            if (expired.isEmpty()) {
                return;
            }

            log.info("Expiring {} stale reservations.", expired.size());

            for (InventoryReservation reservation : expired) {
                try {
                    reservationService.releaseReservation(
                            reservation.getOrderId(), ConflictReason.EXPIRED);
                } catch (Exception e) {
                    // Log and continue — do not abort the entire batch on a single failure
                    log.error("Failed to expire reservation {} for order {}: {}",
                            reservation.getId(), reservation.getOrderId(), e.getMessage());
                }
            }
        } finally {
            lockService.unlock(LOCK_KEY);
        }
    }
}
