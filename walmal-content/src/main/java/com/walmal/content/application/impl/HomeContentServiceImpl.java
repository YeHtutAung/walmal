package com.walmal.content.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.content.application.HomeContentService;
import com.walmal.content.application.dto.ContentImageDto;
import com.walmal.content.domain.CategoryTile;
import com.walmal.content.domain.ContentHome;
import com.walmal.content.domain.ContentStatus;
import com.walmal.content.domain.Cta;
import com.walmal.content.domain.Hero;
import com.walmal.content.domain.HomeContent;
import com.walmal.content.domain.Promo;
import com.walmal.content.infrastructure.ContentHomeRepository;
import com.walmal.content.infrastructure.ContentImageStorageAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of {@link HomeContentService}.
 *
 * <p>SRP: owns the full draft/publish lifecycle of the single home-page document.</p>
 *
 * <p>DIP: image storage is delegated to {@link ContentImageStorageAdapter} (never the
 * MinIO SDK directly); auditing goes through the {@link AuditService} interface.</p>
 *
 * <p>Audit rule: {@link #publish} writes to the audit log <em>before</em> the destructive
 * upsert of the PUBLISHED row, per the platform audit-before-write rule.</p>
 */
@Service
@Transactional
public class HomeContentServiceImpl implements HomeContentService {

    private static final Logger log = LoggerFactory.getLogger(HomeContentServiceImpl.class);

    /**
     * Fixed sentinel {@code record_id} for {@code content_home} audit entries.
     *
     * <p>{@code audit_log.record_id} is {@code UUID NOT NULL}, but the {@code content_home}
     * primary key is the String lifecycle status — a String PK cannot be stored there and
     * {@code null} violates the NOT NULL constraint. This constant is a stable identifier
     * for "the home content document" as an audit target.</p>
     */
    private static final UUID CONTENT_HOME_AUDIT_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000c0f7e");

    /**
     * Built-in fallback document mirroring the current live storefront home copy, so the
     * editor opens populated even before any draft or publish has occurred.
     */
    public static final HomeContent DEFAULT = new HomeContent(
            new Hero(
                    "26/27 Season Drop",
                    "Own\nthe pitch.",
                    "The latest match kits, elite boots and training gear — built for players who don't clock off.",
                    new Cta("Shop new arrivals", "/products"),
                    new Cta("Shop boots", "/products?category=boots"),
                    null),
            List.of(
                    new CategoryTile("Jerseys", "/products?category=jerseys", null),
                    new CategoryTile("Boots", "/products?category=boots", null),
                    new CategoryTile("Teamwear", "/products?category=teamwear", null),
                    new CategoryTile("Equipment", "/products?category=equipment", null)),
            new Promo(
                    "Limited release",
                    "The Velocity\nElite Pack",
                    "Featherweight speed boots engineered for the counter-attack. Only while stocks last.",
                    new Cta("Shop the pack", "/products?category=boots"),
                    null));

    private final ContentHomeRepository repository;
    private final ContentImageStorageAdapter storageAdapter;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public HomeContentServiceImpl(ContentHomeRepository repository,
                                  ContentImageStorageAdapter storageAdapter,
                                  AuditService auditService,
                                  ObjectMapper objectMapper) {
        this.repository = repository;
        this.storageAdapter = storageAdapter;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HomeContent> getPublished() {
        return repository.findById(ContentStatus.PUBLISHED).map(ContentHome::getContent);
    }

    @Override
    @Transactional(readOnly = true)
    public HomeContent getDraft() {
        return repository.findById(ContentStatus.DRAFT)
                .map(ContentHome::getContent)
                .orElseGet(() -> repository.findById(ContentStatus.PUBLISHED)
                        .map(ContentHome::getContent)
                        .orElse(DEFAULT));
    }

    @Override
    public void saveDraft(HomeContent content, String performedBy) {
        upsert(ContentStatus.DRAFT, content, performedBy);
        log.info("Home content draft saved by {}", performedBy);
    }

    @Override
    public void publish(String performedBy) {
        ContentHome draft = repository.findById(ContentStatus.DRAFT)
                .orElseThrow(() -> new IllegalStateException("No draft to publish"));

        String oldPublishedJson = repository.findById(ContentStatus.PUBLISHED)
                .map(existing -> toJson(existing.getContent()))
                .orElse(null);
        String newDraftJson = toJson(draft.getContent());

        // AUDIT FIRST — before the destructive upsert of the PUBLISHED row.
        auditService.log(new AuditEntry(
                "content_home", CONTENT_HOME_AUDIT_ID, AuditAction.UPDATE,
                oldPublishedJson, newDraftJson, performedBy));

        upsert(ContentStatus.PUBLISHED, draft.getContent(), performedBy);
        log.info("Home content published by {}", performedBy);
    }

    @Override
    public ContentImageDto uploadImage(String section, InputStream data, String filename,
                                       String contentType, long size, String performedBy) {
        String key = storageAdapter.store(section, filename, data, size, contentType);
        String url = storageAdapter.getUrl(key);
        log.info("Home content image uploaded to section '{}' (key={}) by {}", section, key, performedBy);
        return new ContentImageDto(url);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Upserts the row for the given lifecycle status: updates in place if present,
     * otherwise inserts a new row, then persists.
     */
    private void upsert(String status, HomeContent content, String performedBy) {
        ContentHome row = repository.findById(status)
                .map(existing -> {
                    existing.update(content, performedBy);
                    return existing;
                })
                .orElseGet(() -> new ContentHome(status, content, performedBy));
        repository.save(row);
    }

    private String toJson(HomeContent content) {
        if (content == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            // Auditing must not block a valid publish; record a fallback marker instead.
            log.warn("Failed to serialise HomeContent for audit log", e);
            return "{\"error\":\"serialization-failed\"}";
        }
    }
}
