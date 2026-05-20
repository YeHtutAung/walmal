package com.walmal.product.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public record CreateVariantRequest(
        @NotBlank @Size(max = 100) String sku,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 50) String barcode,
        Map<String, Object> attributes,
        @NotNull @DecimalMin("0.00") BigDecimal initialPrice,
        @NotBlank @Size(min = 3, max = 3) String currency
) {}
