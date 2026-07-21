package com.walmal.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.content.application.dto.ContentImageDto;
import com.walmal.content.application.impl.HomeContentServiceImpl;
import com.walmal.content.domain.CategoryTile;
import com.walmal.content.domain.ContentHome;
import com.walmal.content.domain.ContentStatus;
import com.walmal.content.domain.Cta;
import com.walmal.content.domain.Hero;
import com.walmal.content.domain.HomeContent;
import com.walmal.content.domain.Promo;
import com.walmal.content.infrastructure.ContentHomeRepository;
import com.walmal.content.infrastructure.ContentImageStorageAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HomeContentServiceImpl}. Collaborators
 * ({@link ContentHomeRepository}, {@link ContentImageStorageAdapter},
 * {@link AuditService}) are Mockito mocks; a real {@link ObjectMapper} is used for
 * audit JSON serialisation (pure utility, nothing to stub).
 */
@ExtendWith(MockitoExtension.class)
class HomeContentServiceImplTest {

    @Mock
    private ContentHomeRepository repository;
    @Mock
    private ContentImageStorageAdapter storageAdapter;
    @Mock
    private AuditService auditService;

    @Captor
    private ArgumentCaptor<ContentHome> contentHomeCaptor;

    private HomeContentServiceImpl service;

    private HomeContent sampleContent;

    @BeforeEach
    void setUp() {
        service = new HomeContentServiceImpl(repository, storageAdapter, auditService, new ObjectMapper());
        sampleContent = new HomeContent(
                new Hero("Eyebrow", "Headline", "Subtext",
                        new Cta("Shop", "/products"), null, null),
                List.of(new CategoryTile("Jerseys", "/products?category=jerseys", null)),
                new Promo("Promo eyebrow", "Promo heading", "Promo text",
                        new Cta("Buy", "/products?category=boots"), null));
    }

    // ── getPublished ──────────────────────────────────────────────────────────

    @Test
    void should_returnEmpty_when_noPublishedRowExists() {
        when(repository.findById(ContentStatus.PUBLISHED)).thenReturn(Optional.empty());

        assertThat(service.getPublished()).isEmpty();
    }

    @Test
    void should_returnPublishedContent_when_publishedRowExists() {
        ContentHome published = new ContentHome(ContentStatus.PUBLISHED, sampleContent, "editor");
        when(repository.findById(ContentStatus.PUBLISHED)).thenReturn(Optional.of(published));

        assertThat(service.getPublished()).contains(sampleContent);
    }

    // ── saveDraft ─────────────────────────────────────────────────────────────

    @Test
    void should_saveNewDraftRow_when_noDraftExists() {
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.empty());

        service.saveDraft(sampleContent, "alice");

        verify(repository).save(contentHomeCaptor.capture());
        ContentHome saved = contentHomeCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ContentStatus.DRAFT);
        assertThat(saved.getContent()).isEqualTo(sampleContent);
        assertThat(saved.getUpdatedBy()).isEqualTo("alice");
    }

    @Test
    void should_updateExistingDraftRow_when_draftExists() {
        HomeContent oldContent = new HomeContent(
                new Hero("Old", "Old headline", null, new Cta("Old", "/old"), null, null),
                List.of(), new Promo("x", "y", null, new Cta("z", "/z"), null));
        ContentHome existing = new ContentHome(ContentStatus.DRAFT, oldContent, "bob");
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.of(existing));

        service.saveDraft(sampleContent, "alice");

        verify(repository).save(existing);
        assertThat(existing.getContent()).isEqualTo(sampleContent);
        assertThat(existing.getUpdatedBy()).isEqualTo("alice");
    }

    @Test
    void should_auditBeforeSave_when_savingDraft() {
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.empty());

        service.saveDraft(sampleContent, "alice");

        // audit BEFORE persisting the draft row — audit-before-write rule
        InOrder inOrder = inOrder(auditService, repository);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(repository).save(any(ContentHome.class));
    }

    @Test
    void should_logCorrectAuditEntry_when_savingDraft() {
        HomeContent oldContent = new HomeContent(
                new Hero("Old", "Old headline", null, new Cta("Old", "/old"), null, null),
                List.of(), new Promo("x", "y", null, new Cta("z", "/z"), null));
        ContentHome existing = new ContentHome(ContentStatus.DRAFT, oldContent, "bob");
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.of(existing));

        service.saveDraft(sampleContent, "alice");

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).log(auditCaptor.capture());
        AuditEntry entry = auditCaptor.getValue();
        assertThat(entry.tableName()).isEqualTo("content_home");
        assertThat(entry.action()).isEqualTo(AuditAction.UPDATE);
        assertThat(entry.recordId()).isNotNull();
        assertThat(entry.oldValue()).isNotNull();        // JSON of the pre-existing draft
        assertThat(entry.newValue()).isNotNull();        // JSON of the new draft
        assertThat(entry.performedBy()).isEqualTo("alice");
    }

    // ── publish ───────────────────────────────────────────────────────────────

    @Test
    void should_auditBeforeSave_when_publishingDraft() {
        ContentHome draft = new ContentHome(ContentStatus.DRAFT, sampleContent, "alice");
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.of(draft));
        when(repository.findById(ContentStatus.PUBLISHED)).thenReturn(Optional.empty());

        service.publish("alice");

        // audit BEFORE persisting the published row
        InOrder inOrder = inOrder(auditService, repository);
        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(repository).save(any(ContentHome.class));

        // the persisted PUBLISHED row carries the draft's content
        verify(repository).save(contentHomeCaptor.capture());
        ContentHome savedPublished = contentHomeCaptor.getValue();
        assertThat(savedPublished.getStatus()).isEqualTo(ContentStatus.PUBLISHED);
        assertThat(savedPublished.getContent()).isEqualTo(sampleContent);
    }

    @Test
    void should_logCorrectAuditEntry_when_publishingDraft() {
        ContentHome draft = new ContentHome(ContentStatus.DRAFT, sampleContent, "alice");
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.of(draft));
        when(repository.findById(ContentStatus.PUBLISHED)).thenReturn(Optional.empty());

        service.publish("alice");

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).log(auditCaptor.capture());
        AuditEntry entry = auditCaptor.getValue();
        assertThat(entry.tableName()).isEqualTo("content_home");
        assertThat(entry.action()).isEqualTo(AuditAction.UPDATE);
        assertThat(entry.recordId()).isNotNull();
        assertThat(entry.oldValue()).isNull();          // nothing previously published
        assertThat(entry.newValue()).isNotNull();       // JSON of the draft being published
        assertThat(entry.performedBy()).isEqualTo("alice");
    }

    @Test
    void should_throwIllegalState_when_publishingWithNoDraft() {
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish("alice"))
                .isInstanceOf(IllegalStateException.class);

        verify(auditService, never()).log(any());
        verify(repository, never()).save(any());
    }

    // ── uploadImage ───────────────────────────────────────────────────────────

    @Test
    void should_storeAndReturnUrl_when_uploadingImage() {
        InputStream data = new ByteArrayInputStream(new byte[]{1, 2, 3});
        when(storageAdapter.store("hero", "pic.png", data, 3L, "image/png"))
                .thenReturn("home/hero/uuid-pic.png");
        when(storageAdapter.getUrl("home/hero/uuid-pic.png"))
                .thenReturn("https://cdn/home/hero/uuid-pic.png");

        ContentImageDto result = service.uploadImage("hero", data, "pic.png", "image/png", 3L, "alice");

        assertThat(result).isEqualTo(new ContentImageDto("https://cdn/home/hero/uuid-pic.png"));
        verify(storageAdapter).store("hero", "pic.png", data, 3L, "image/png");
        verify(storageAdapter).getUrl("home/hero/uuid-pic.png");
    }

    // ── getDraft fallback chain ───────────────────────────────────────────────

    @Test
    void should_returnDraftContent_when_draftExists() {
        ContentHome draft = new ContentHome(ContentStatus.DRAFT, sampleContent, "alice");
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.of(draft));

        assertThat(service.getDraft()).isEqualTo(sampleContent);
    }

    @Test
    void should_fallBackToPublished_when_noDraftButPublishedExists() {
        ContentHome published = new ContentHome(ContentStatus.PUBLISHED, sampleContent, "alice");
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.empty());
        when(repository.findById(ContentStatus.PUBLISHED)).thenReturn(Optional.of(published));

        assertThat(service.getDraft()).isEqualTo(sampleContent);
    }

    @Test
    void should_fallBackToDefault_when_neitherDraftNorPublishedExists() {
        when(repository.findById(ContentStatus.DRAFT)).thenReturn(Optional.empty());
        when(repository.findById(ContentStatus.PUBLISHED)).thenReturn(Optional.empty());

        HomeContent result = service.getDraft();

        assertThat(result).isNotNull();
        assertThat(result).isSameAs(HomeContentServiceImpl.DEFAULT);
    }
}
