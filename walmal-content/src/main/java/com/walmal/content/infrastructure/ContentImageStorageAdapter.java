package com.walmal.content.infrastructure;

import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

/**
 * Adapter that translates home-page content image storage operations into calls to
 * {@link FileStorageService}.
 *
 * <p>DIP: this is the ONLY class in walmal-content that calls {@link FileStorageService}.
 * Service implementations depend on this adapter, keeping the MinIO SDK (and any future
 * storage backend) invisible to the rest of the module.</p>
 *
 * <p>Bucket: {@code content-images}</p>
 * <p>Object key pattern: {@code home/{section}/{uuid}-{filename}}</p>
 */
@Component
public class ContentImageStorageAdapter {

    static final String BUCKET = "content-images";

    private final FileStorageService fileStorageService;

    public ContentImageStorageAdapter(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * Uploads a home-page content image to MinIO and returns the storage key.
     *
     * @param section     home-page section the image belongs to (e.g. {@code hero})
     * @param filename    original filename
     * @param data        image input stream
     * @param size        size in bytes
     * @param contentType MIME type
     * @return the MinIO storage key
     */
    public String store(String section, String filename, InputStream data, long size, String contentType) {
        String key = String.format("home/%s/%s-%s", section, UUID.randomUUID(), safe(filename));
        StoredFile stored = fileStorageService.upload(BUCKET, key, data, contentType, size);
        return stored.key();
    }

    /**
     * Returns the presigned or CDN URL for the given storage key.
     */
    public String getUrl(String key) {
        return fileStorageService.getPresignedUrl(BUCKET, key);
    }

    /**
     * Sanitizes a filename for safe use in a storage key: strips path separators and any
     * character outside {@code [A-Za-z0-9._-]}. Null defaults to {@code file}.
     */
    private static String safe(String filename) {
        if (filename == null) {
            return "file";
        }
        return filename.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
