package com.walmal.pos.api;

import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.model.ApiResponse;
import com.walmal.pos.api.dto.RecordOnlineSaleRequest;
import com.walmal.pos.application.PosSaleService;
import com.walmal.pos.application.dto.PosSaleDto;
import com.walmal.pos.application.dto.PosSaleSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for POS sale recording and queries.
 * Base path: {@code /api/v1/pos/sales}
 *
 * <p>No business logic lives here — all work is delegated to {@link PosSaleService}.</p>
 *
 * <p>The {@code X-Idempotency-Key} header is extracted here and passed to the service.
 * The service uses it to prevent duplicate order creation on terminal retry.</p>
 *
 * <p>Role-based access:
 * <ul>
 *   <li>POST / (online sale): POS_OPERATOR only</li>
 *   <li>GET /{id}: POS_OPERATOR or ADMIN</li>
 *   <li>GET ?terminalId=: POS_OPERATOR or ADMIN</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/pos/sales")
@Tag(name = "POS Sales", description = "Online POS sale recording and sale history queries")
public class PosSaleController {

    private final PosSaleService posSaleService;

    public PosSaleController(PosSaleService posSaleService) {
        this.posSaleService = posSaleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('POS_OPERATOR')")
    @Operation(summary = "Record an online POS sale",
               description = "Validates variants, fetches prices, creates an Order module order, " +
                             "and persists the POS sale record. Supply X-Idempotency-Key header to " +
                             "safely retry without creating duplicate orders.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Sale recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request or inactive variant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — POS_OPERATOR role required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Terminal not found")
    })
    public ApiResponse<PosSaleDto> recordOnlineSale(
            @Valid @RequestBody RecordOnlineSaleRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {

        // cashierId sourced from auth principal — service does not access SecurityContextHolder
        UUID cashierId = principal != null ? principal.userId() : null;

        PosSaleDto sale = posSaleService.recordOnlineSale(
                request.terminalId(),
                request.items(),
                cashierId,
                request.currency(),
                idempotencyKey);

        return ApiResponse.ok("Online sale recorded", sale);
    }

    @GetMapping("/{saleId}")
    @PreAuthorize("hasAnyRole('POS_OPERATOR', 'ADMIN')")
    @Operation(summary = "Get sale details",
               description = "Returns full sale details including all line items.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Sale not found")
    })
    public ApiResponse<PosSaleDto> getSale(@PathVariable UUID saleId) {
        return ApiResponse.ok(posSaleService.getSale(saleId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('POS_OPERATOR', 'ADMIN')")
    @Operation(summary = "List sales by terminal",
               description = "Returns a paginated list of sales for a terminal, ordered by sold_at DESC.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ApiResponse<Page<PosSaleSummaryDto>> listSalesByTerminal(
            @RequestParam UUID terminalId,
            @PageableDefault(size = 20, sort = "soldAt") Pageable pageable) {
        return ApiResponse.ok(posSaleService.listSalesByTerminal(terminalId, pageable));
    }
}
