package com.walmal.pos.api;

import com.walmal.common.model.ApiResponse;
import com.walmal.pos.api.dto.OfflineSyncRequest;
import com.walmal.pos.application.PosSyncService;
import com.walmal.pos.application.dto.SyncResultDto;
import com.walmal.pos.application.dto.SyncStatusDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for offline POS sync operations.
 * Base path: {@code /api/v1/pos/sync}
 *
 * <p>No business logic lives here — all work is delegated to {@link PosSyncService}.</p>
 *
 * <p>Role-based access:
 * <ul>
 *   <li>POST / (submit sync): POS_OPERATOR only</li>
 *   <li>GET /status: POS_OPERATOR or ADMIN</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/pos/sync")
@Tag(name = "POS Sync", description = "Offline sale submission and sync status queries")
public class PosSyncController {

    private final PosSyncService posSyncService;

    public PosSyncController(PosSyncService posSyncService) {
        this.posSyncService = posSyncService;
    }

    @PostMapping
    @PreAuthorize("hasRole('POS_OPERATOR')")
    @Operation(summary = "Submit offline sale batch for sync",
               description = "Processes a batch of offline sale payloads recorded while the terminal " +
                             "was disconnected. Each item is processed independently — a failure on " +
                             "one item does not roll back successfully processed items. " +
                             "Maximum batch size: 100 items.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Batch processed (check result for per-item failures)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request or batch exceeds 100 items"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — POS_OPERATOR role required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Terminal not found")
    })
    public ApiResponse<SyncResultDto> submitOfflineSync(@Valid @RequestBody OfflineSyncRequest request) {
        SyncResultDto result = posSyncService.submitOfflineSync(
                request.terminalId(), request.offlineSales());
        return ApiResponse.ok("Sync batch processed", result);
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('POS_OPERATOR', 'ADMIN')")
    @Operation(summary = "Get sync status for a terminal",
               description = "Returns real-time counts of pending and failed sync queue entries " +
                             "for operator dashboard monitoring.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Terminal not found")
    })
    public ApiResponse<SyncStatusDto> getSyncStatus(@RequestParam UUID terminalId) {
        return ApiResponse.ok(posSyncService.getSyncStatus(terminalId));
    }
}
