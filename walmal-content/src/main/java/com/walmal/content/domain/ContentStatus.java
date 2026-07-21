package com.walmal.content.domain;

/**
 * Lifecycle status values for the {@code content_home} document. These are the
 * primary-key values (one row per status) — DRAFT is the editable working copy,
 * PUBLISHED is the live storefront copy.
 */
public final class ContentStatus {
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";

    private ContentStatus() {}
}
