package com.walmal.product.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.product.api.dto.response.ImageResponse;
import com.walmal.product.application.ProductImageService;
import com.walmal.product.domain.Product;
import com.walmal.product.domain.ProductImage;
import com.walmal.product.domain.ProductVariant;
import com.walmal.product.infrastructure.ProductImageRepository;
import com.walmal.product.infrastructure.ProductImageStorageAdapter;
import com.walmal.product.infrastructure.ProductRepository;
import com.walmal.product.infrastructure.ProductVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ProductImageService}.
 *
 * <p>SRP: all image upload, delete, and listing operations are isolated here.</p>
 *
 * <p>DIP: delegates MinIO operations to {@link ProductImageStorageAdapter},
 * which wraps {@link com.walmal.common.storage.FileStorageService}. This class
 * never calls the MinIO SDK directly.</p>
 *
 * <p>Primary image invariant: {@link #setPrimaryImage} clears the previous primary and
 * sets the new one within a single {@code @Transactional} method to satisfy the partial
 * unique index {@code idx_product_images_primary_per_product}.</p>
 */
@Service
@Transactional
public class ProductImageServiceImpl implements ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageServiceImpl.class);

    private final ProductImageRepository imageRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageStorageAdapter storageAdapter;
    private final AuditService auditService;

    public ProductImageServiceImpl(ProductImageRepository imageRepository,
                                   ProductRepository productRepository,
                                   ProductVariantRepository variantRepository,
                                   ProductImageStorageAdapter storageAdapter,
                                   AuditService auditService) {
        this.imageRepository = imageRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.storageAdapter = storageAdapter;
        this.auditService = auditService;
    }

    @Override
    public ImageResponse uploadImage(UUID productId, UUID variantId,
                                     InputStream content, String filename,
                                     String contentType, long size,
                                     String altText, boolean isPrimary,
                                     String performedBy) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        ProductVariant variant = null;
        if (variantId != null) {
            variant = variantRepository.findById(variantId)
                    .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId));
        }

        // Store in MinIO via adapter
        String storageKey = storageAdapter.store(productId, variantId, filename, content, size, contentType);
        String cdnUrl = storageAdapter.getUrl(storageKey);

        // If this image is primary, clear any existing primary first
        if (isPrimary) {
            clearExistingPrimary(productId);
        }

        int nextOrder = imageRepository.findByProductIdOrderByDisplayOrderAsc(productId).size();

        ProductImage image = new ProductImage(product, variant, storageKey, cdnUrl,
                altText, nextOrder, isPrimary);
        image = imageRepository.save(image);

        log.info("Image uploaded for product: {} storageKey: {} by {}", productId, storageKey, performedBy);
        return toImageResponse(image);
    }

    @Override
    public void deleteImage(UUID imageId, String performedBy) {
        ProductImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductImage", imageId));

        // AUDIT FIRST — before storage and DB deletion
        auditService.log(new AuditEntry(
                "product_images", imageId, AuditAction.DELETE,
                String.format("{\"storageKey\":\"%s\",\"productId\":\"%s\"}",
                        image.getStorageKey(), image.getProduct().getId()),
                null, performedBy));

        // Delete from MinIO
        storageAdapter.delete(image.getStorageKey());

        // Delete DB row
        imageRepository.delete(image);

        log.info("Image deleted: {} by {}", imageId, performedBy);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImageResponse> listImages(UUID productId) {
        return imageRepository.findByProductIdOrderByDisplayOrderAsc(productId)
                .stream()
                .map(this::toImageResponse)
                .collect(Collectors.toList());
    }

    @Override
    public void setPrimaryImage(UUID imageId, String performedBy) {
        ProductImage newPrimary = imageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductImage", imageId));

        UUID productId = newPrimary.getProduct().getId();

        // Clear existing primary first — satisfies partial unique index in single transaction
        clearExistingPrimary(productId);

        // Set new primary
        newPrimary.setPrimary(true);
        imageRepository.save(newPrimary);

        log.info("Primary image set to: {} for product: {} by {}", imageId, productId, performedBy);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void clearExistingPrimary(UUID productId) {
        imageRepository.findByProductIdAndPrimaryTrue(productId)
                .ifPresent(existing -> {
                    existing.setPrimary(false);
                    // saveAndFlush (not save): the "clear old primary" UPDATE must hit
                    // the DB BEFORE the caller sets the new primary. Both run in the same
                    // @Transactional, but Hibernate does not order updates by call order at
                    // flush time — it can flush the "set new = TRUE" UPDATE first, which
                    // then collides with this still-TRUE row and violates the non-deferrable
                    // partial unique index idx_product_images_primary_per_product. Flushing
                    // here forces the correct order (clear → then set). Flush is not commit,
                    // so a later failure still rolls the whole transaction back.
                    imageRepository.saveAndFlush(existing);
                });
    }

    private ImageResponse toImageResponse(ProductImage img) {
        UUID variantId = (img.getVariant() != null) ? img.getVariant().getId() : null;
        String freshUrl = storageAdapter.getUrl(img.getStorageKey());
        return new ImageResponse(
                img.getId(),
                img.getProduct().getId(),
                variantId,
                img.getStorageKey(),
                freshUrl,
                img.getAltText(),
                img.getDisplayOrder(),
                img.isPrimary(),
                img.getCreatedAt()
        );
    }
}
