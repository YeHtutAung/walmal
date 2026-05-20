package com.walmal.product.infrastructure;

import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

/**
 * Adapter that translates product image storage operations into calls to {@link FileStorageService}.
 *
 * <p>DIP: this is the ONLY class in walmal-product that calls {@link FileStorageService}.
 * Service implementations depend on this adapter (or the {@code FileStorageService} interface
 * directly). The MinIO SDK is never visible outside walmal-infrastructure.</p>
 *
 * <p>Bucket: {@code product-images}</p>
 * <p>Object key pattern:
 * <ul>
 *   <li>Variant image: {@code products/{productId}/{variantId}/{filename}}</li>
 *   <li>Product-level image: {@code products/{productId}/_product/{filename}}</li>
 * </ul>
 * </p>
 */
@Component
public class ProductImageStorageAdapter {

    static final String BUCKET = "product-images";

    private final FileStorageService fileStorageService;

    public ProductImageStorageAdapter(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * Uploads an image to MinIO and returns the storage key.
     *
     * @param productId   owner product id
     * @param variantId   associated variant id, or null for product-level images
     * @param filename    original filename
     * @param data        image input stream
     * @param size        size in bytes
     * @param contentType MIME type
     * @return the MinIO storage key
     */
    public String store(UUID productId, UUID variantId, String filename,
                        InputStream data, long size, String contentType) {
        String key = buildKey(productId, variantId, filename);
        StoredFile stored = fileStorageService.upload(BUCKET, key, data, contentType, size);
        return stored.key();
    }

    /**
     * Deletes an image from MinIO by its storage key.
     */
    public void delete(String storageKey) {
        fileStorageService.delete(BUCKET, storageKey);
    }

    /**
     * Returns the presigned or CDN URL for the given storage key.
     */
    public String getUrl(String storageKey) {
        return fileStorageService.getPresignedUrl(BUCKET, storageKey);
    }

    private String buildKey(UUID productId, UUID variantId, String filename) {
        String variantSegment = (variantId != null) ? variantId.toString() : "_product";
        return String.format("products/%s/%s/%s", productId, variantSegment, filename);
    }
}
