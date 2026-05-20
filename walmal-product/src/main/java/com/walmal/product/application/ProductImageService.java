package com.walmal.product.application;

import com.walmal.product.api.dto.response.ImageResponse;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Internal image management interface — not exposed outside the walmal-product module.
 *
 * <p>SRP: All image upload, delete, and listing operations are isolated here.
 * The implementation delegates storage operations to {@code FileStorageService} via
 * {@code ProductImageStorageAdapter}.</p>
 */
public interface ProductImageService {

    /**
     * Uploads an image to MinIO and persists the {@code ProductImage} entity.
     * If {@code isPrimary} is true, clears any existing primary image for the product
     * before setting this one as primary — both in the same transaction.
     */
    ImageResponse uploadImage(UUID productId, UUID variantId,
                              InputStream content, String filename,
                              String contentType, long size,
                              String altText, boolean isPrimary,
                              String performedBy);

    /**
     * Deletes an image. Writes audit_log BEFORE MinIO and DB deletion.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if the image does not exist
     */
    void deleteImage(UUID imageId, String performedBy);

    /**
     * Returns all images for the given product, ordered by display_order ascending.
     */
    List<ImageResponse> listImages(UUID productId);

    /**
     * Sets the given image as the primary image for its product.
     * Clears the previous primary (if any) and sets the new one in a single transaction.
     */
    void setPrimaryImage(UUID imageId, String performedBy);
}
