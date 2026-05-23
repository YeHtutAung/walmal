package com.walmal.inventory.application.impl;

import com.walmal.inventory.api.dto.response.ReservationResponse;
import com.walmal.inventory.api.dto.response.StockListItemResponse;
import com.walmal.inventory.application.InventoryAdminService;
import com.walmal.inventory.domain.InventoryReservation;
import com.walmal.inventory.domain.ReservationStatus;
import com.walmal.inventory.infrastructure.InventoryReservationRepository;
import com.walmal.inventory.infrastructure.InventoryStockRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryAdminServiceImpl implements InventoryAdminService {

    private final InventoryStockRepository stockRepo;
    private final InventoryReservationRepository reservationRepo;

    public InventoryAdminServiceImpl(InventoryStockRepository stockRepo,
                                      InventoryReservationRepository reservationRepo) {
        this.stockRepo = stockRepo;
        this.reservationRepo = reservationRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockListItemResponse> listAllStock(Pageable pageable) {
        return stockRepo.findAllWithLocation(pageable)
                .map(s -> new StockListItemResponse(
                        s.getId(),
                        s.getVariantId(),
                        s.getLocation().getId(),
                        s.getLocation().getName(),
                        s.getAvailableQuantity(),
                        s.getReservedQuantity(),
                        s.getLowStockThreshold()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReservationResponse> listReservations(@Nullable ReservationStatus status, Pageable pageable) {
        Page<InventoryReservation> page = (status != null)
                ? reservationRepo.findByStatus(status, pageable)
                : reservationRepo.findAll(pageable);

        return page.map(r -> new ReservationResponse(
                r.getId(),
                r.getOrderId(),
                r.getVariantId(),
                r.getLocation().getId(),
                r.getQuantity(),
                r.getStatus().name(),
                r.getConflictReason() != null ? r.getConflictReason().name() : null,
                r.getExpiresAt(),
                r.getCreatedAt()));
    }
}
