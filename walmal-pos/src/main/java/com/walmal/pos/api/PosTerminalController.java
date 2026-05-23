package com.walmal.pos.api;

import com.walmal.common.model.ApiResponse;
import com.walmal.pos.api.dto.RegisterTerminalRequest;
import com.walmal.pos.application.PosAdminService;
import com.walmal.pos.application.PosTerminalService;
import com.walmal.pos.application.dto.PosTerminalDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for POS terminal lifecycle management.
 * Base path: {@code /api/v1/pos/terminals}
 *
 * <p>No business logic lives here — all work is delegated to {@link PosTerminalService}.</p>
 *
 * <p>Role-based access:
 * <ul>
 *   <li>POST / (register): ADMIN only</li>
 *   <li>PUT /{id}/deactivate: ADMIN only</li>
 *   <li>GET /{id}: ADMIN or POS_OPERATOR</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/pos/terminals")
@Tag(name = "POS Terminals", description = "POS terminal registration, deactivation, and queries")
public class PosTerminalController {

    private final PosTerminalService posTerminalService;
    private final PosAdminService posAdminService;

    public PosTerminalController(PosTerminalService posTerminalService,
                                  PosAdminService posAdminService) {
        this.posTerminalService = posTerminalService;
        this.posAdminService = posAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'POS_OPERATOR')")
    @Operation(summary = "List all POS terminals",
               description = "Returns a paginated list of all registered terminals.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ApiResponse<Page<PosTerminalDto>> listTerminals(
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return ApiResponse.ok(posAdminService.listAllTerminals(pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a new POS terminal",
               description = "Creates a new terminal record in ACTIVE status at the given inventory location.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Terminal registered"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — ADMIN role required")
    })
    public ApiResponse<UUID> registerTerminal(@Valid @RequestBody RegisterTerminalRequest request) {
        UUID terminalId = posTerminalService.registerTerminal(request.name(), request.locationId());
        return ApiResponse.ok("Terminal registered", terminalId);
    }

    @GetMapping("/{terminalId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'POS_OPERATOR')")
    @Operation(summary = "Get terminal details",
               description = "Returns terminal details including current status and last seen timestamp.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Terminal not found")
    })
    public ApiResponse<PosTerminalDto> getTerminal(@PathVariable UUID terminalId) {
        return ApiResponse.ok(posTerminalService.getTerminal(terminalId));
    }

    @PutMapping("/{terminalId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deactivate a terminal",
               description = "Sets the terminal status to INACTIVE. Writes audit_log before mutation.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deactivated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Terminal is already INACTIVE"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — ADMIN role required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Terminal not found")
    })
    public void deactivateTerminal(@PathVariable UUID terminalId) {
        posTerminalService.deactivateTerminal(terminalId);
    }
}
