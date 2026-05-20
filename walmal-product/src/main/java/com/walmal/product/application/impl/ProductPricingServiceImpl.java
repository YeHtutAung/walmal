package com.walmal.product.application.impl;

import com.walmal.common.cache.CacheService;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.product.application.ProductPricingService;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.domain.ProductPrice;
import com.walmal.product.infrastructure.ProductPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of {@link ProductPricingService}.
 *
 * <p>DIP: depends on {@link ProductPriceRepository} and {@link CacheService} interfaces only.</p>
 *
 * <p>Cache-aside: read from cache first; on miss query DB and populate cache.</p>
 */
@Service
@Transactional(readOnly = true)
public class ProductPricingServiceImpl implements ProductPricingService {

    private static final Logger log = LoggerFactory.getLogger(ProductPricingServiceImpl.class);

    static final String PRICE_CACHE_PREFIX = "product:price:";
    static final Duration PRICE_TTL = Duration.ofMinutes(5);

    private final ProductPriceRepository priceRepository;
    private final CacheService cacheService;

    public ProductPricingServiceImpl(ProductPriceRepository priceRepository,
                                     CacheService cacheService) {
        this.priceRepository = priceRepository;
        this.cacheService = cacheService;
    }

    @Override
    public Optional<PriceDto> getCurrentPrice(UUID variantId) {
        String cacheKey = PRICE_CACHE_PREFIX + variantId;
        Optional<PriceDto> cached = cacheService.get(cacheKey, PriceDto.class);
        if (cached.isPresent()) {
            log.debug("Cache hit for price: {}", variantId);
            return cached;
        }

        Optional<PriceDto> result = priceRepository.findByVariantId(variantId)
                .map(this::toPriceDto);

        result.ifPresent(dto -> cacheService.put(cacheKey, dto, PRICE_TTL));
        return result;
    }

    @Override
    public PriceDto getPriceForVariant(UUID variantId) {
        return getCurrentPrice(variantId)
                .orElseThrow(() -> new BusinessRuleException(
                        "No price configured for variant: " + variantId));
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private PriceDto toPriceDto(ProductPrice p) {
        return new PriceDto(
                p.getVariant().getId(),
                p.getAmount(),
                p.getCurrency(),
                p.getEffectiveFrom()
        );
    }
}
