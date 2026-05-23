package com.walmal.warehouse.api;

import com.walmal.common.model.ApiResponse;
import com.walmal.warehouse.application.WarehouseAdminService;
import com.walmal.warehouse.application.dto.FulfillmentDetailDto;
import com.walmal.warehouse.application.dto.FulfillmentSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin read-only view of warehouse fulfillments.
 * Exposed at /api/v1/warehouse/tasks to match the admin panel resource naming.
 */
@RestController
@RequestMapping("/api/v1/warehouse/tasks")
@Tag(name = "Warehouse Admin", description = "Admin read-only view of fulfillment tasks")
public class WarehouseAdminController {

    private final WarehouseAdminService adminService;

    public WarehouseAdminController(WarehouseAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")
    @Operation(summary = "List all fulfillment tasks (paginated)")
    public ApiResponse<Page<FulfillmentSummaryDto>> listTasks(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ApiResponse.ok(adminService.listFulfillments(pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")
    @Operation(summary = "Get fulfillment task detail by fulfillment ID")
    public ApiResponse<FulfillmentDetailDto> getTask(@PathVariable UUID id) {
        return ApiResponse.ok(adminService.getFulfillmentById(id));
    }
}
