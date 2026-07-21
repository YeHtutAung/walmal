package com.walmal.content.application;

import com.walmal.content.application.dto.ContentImageDto;
import com.walmal.content.domain.HomeContent;

import java.io.InputStream;
import java.util.Optional;

/**
 * Public API of the walmal-content module: manages the editable home-page document
 * through its draft/publish lifecycle.
 *
 * <p>Two lifecycle rows exist at most, keyed by status:
 * {@code DRAFT} (editable working copy) and {@code PUBLISHED} (live storefront copy).</p>
 */
public interface HomeContentService {

    /**
     * Returns the live, published home-page document.
     *
     * @return the published content, or {@link Optional#empty()} if nothing has ever been published
     */
    Optional<HomeContent> getPublished();

    /**
     * Returns the document for the editor to open, following the fallback chain
     * DRAFT → PUBLISHED → built-in DEFAULT. Never returns {@code null}.
     */
    HomeContent getDraft();

    /**
     * Upserts the DRAFT document with the given content.
     */
    void saveDraft(HomeContent content, String performedBy);

    /**
     * Promotes the current DRAFT to PUBLISHED. Writes an audit entry before persisting.
     *
     * @throws IllegalStateException if there is no draft to publish
     */
    void publish(String performedBy);

    /**
     * Stores a home-page content image and returns its reference URL.
     *
     * @param section     home-page section the image belongs to (e.g. {@code hero})
     * @param data        image input stream
     * @param filename    original filename
     * @param contentType MIME type
     * @param size        size in bytes
     * @param performedBy the acting principal
     */
    ContentImageDto uploadImage(String section, InputStream data, String filename,
                                String contentType, long size, String performedBy);
}
