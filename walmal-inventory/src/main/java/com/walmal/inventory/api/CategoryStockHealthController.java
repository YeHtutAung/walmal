package com.walmal.inventory.api;

import com.walmal.common.model.ApiResponse;
import com.walmal.inventory.api.dto.response.CategoryStockHealthDto;
import com.walmal.inventory.application.CategoryStockHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for the category-level stock-health rollup (read-only).
 * Base path: /api/v1/inventory/categories
 */
@RestController
@RequestMapping("/api/v1/inventory/categories")
@Tag(name = "Category Stock Health", description = "Category-level stock-health rollup")
public class CategoryStockHealthController {

    private final CategoryStockHealthService categoryStockHealthService;

    public CategoryStockHealthController(CategoryStockHealthService categoryStockHealthService) {
        this.categoryStockHealthService = categoryStockHealthService;
    }

    @GetMapping("/stock-health")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Get stock health by category",
               description = "Returns one entry per category with product/OK/low/critical stock counts.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ApiResponse<List<CategoryStockHealthDto>> getStockHealthByCategory() {
        return ApiResponse.ok(categoryStockHealthService.getStockHealthByCategory());
    }
}
