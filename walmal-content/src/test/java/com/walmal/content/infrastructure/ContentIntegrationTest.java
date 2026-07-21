package com.walmal.content.infrastructure;

import com.walmal.common.audit.AuditService;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.content.application.HomeContentService;
import com.walmal.content.domain.CategoryTile;
import com.walmal.content.domain.ContentHome;
import com.walmal.content.domain.ContentStatus;
import com.walmal.content.domain.Cta;
import com.walmal.content.domain.Hero;
import com.walmal.content.domain.HomeContent;
import com.walmal.content.domain.Promo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for the walmal-content module against the real
 * Docker-Compose Postgres (localhost:5432/walmal) — NOT Testcontainers, which is
 * incompatible in this environment.
 *
 * <p>A full {@code @SpringBootTest} boots the entire content context: the JPA
 * {@link ContentHomeRepository}, the {@code HomeContentServiceImpl} service, and the
 * {@code ContentController}. The service requires a {@link FileStorageService} (via the
 * image storage adapter) and an {@link AuditService}; the controller requires the
 * {@code walmal.content.preview-token} property — all supplied below so the context boots.</p>
 *
 * <p>{@link TestInfrastructureConfig} provides a no-op {@link FileStorageService} and a
 * JdbcTemplate-backed {@link AuditService} that writes real rows to {@code audit_log},
 * so the publish/saveDraft audit trail is assertable.</p>
 *
 * <p>Tagged {@code "integration"} — run with
 * {@code mvn -pl walmal-content test -Dgroups=integration -DexcludedGroups=}.
 * Requires Docker Compose services running.</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/walmal",
    "spring.datasource.username=walmal",
    "spring.datasource.password=walmal",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "walmal.content.preview-token=test-preview"
})
class ContentIntegrationTest {

    @Autowired
    ContentHomeRepository repository;

    @Autowired
    HomeContentService service;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // Keep the shared Docker-Compose DB clean — this test writes real DRAFT/PUBLISHED
    // rows and real audit_log rows. Delete both lifecycle rows and the content_home
    // audit entries so the shared DB stays clean and re-runs stay deterministic.
    @AfterEach
    void cleanUp() {
        repository.deleteById(ContentStatus.DRAFT);
        repository.deleteById(ContentStatus.PUBLISHED);
        jdbcTemplate.update("DELETE FROM audit_log WHERE table_name = 'content_home'");
    }

    private HomeContent sampleDoc() {
        Hero hero = new Hero(
                "New season",
                "Gear up for summer",
                "Everything you need for the outdoors, now in stock.",
                new Cta("Shop now", "/products"),
                new Cta("View lookbook", "/lookbook"),
                "/img/hero-summer.jpg");
        List<CategoryTile> tiles = List.of(
                new CategoryTile("Running", "/c/running", "/img/running.jpg"),
                new CategoryTile("Cycling", "/c/cycling", "/img/cycling.jpg"));
        Promo promo = new Promo(
                "Limited time",
                "20% off all footwear",
                "Use code SUMMER at checkout.",
                new Cta("Grab the deal", "/promo/footwear"),
                "/img/promo-footwear.jpg");
        return new HomeContent(hero, tiles, promo);
    }

    /** A {@link HomeContent} whose {@code categoryTiles} list has exactly {@code n} elements. */
    private HomeContent docWithTiles(int n) {
        List<CategoryTile> tiles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tiles.add(new CategoryTile("Cat " + i, "/c/cat-" + i, "/img/cat-" + i + ".jpg"));
        }
        HomeContent base = sampleDoc();
        return new HomeContent(base.hero(), tiles, base.promo());
    }

    @Test
    void should_roundTripJsonbDocument_when_savedAndReloaded() {
        HomeContent doc = sampleDoc();

        repository.save(new ContentHome(ContentStatus.DRAFT, doc, "tester"));

        ContentHome reloaded = repository.findById(ContentStatus.DRAFT).orElseThrow();
        assertThat(reloaded.getContent().hero().headline()).isEqualTo(doc.hero().headline());
        assertThat(reloaded.getContent().hero().secondaryCta()).isNotNull();
        assertThat(reloaded.getContent().categoryTiles()).hasSize(doc.categoryTiles().size());
        assertThat(reloaded.getContent().promo().cta().href()).isEqualTo(doc.promo().cta().href());
        assertThat(reloaded.getUpdatedBy()).isEqualTo("tester");
    }

    @Test
    void should_publishDraft_when_draftExists_andExposeItAsPublished() {
        service.saveDraft(sampleDoc(), "admin");
        assertThat(service.getPublished()).isEmpty();           // not yet published
        service.publish("admin");
        assertThat(service.getPublished()).isPresent();
        assertThat(service.getPublished().get().hero().headline())
                .isEqualTo(sampleDoc().hero().headline());
        // audit row written (publish)
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE table_name = 'content_home' AND performed_by = 'admin'",
            Integer.class);
        assertThat(n).isGreaterThanOrEqualTo(1);
    }

    @Test
    void should_reject_when_publishWithNoDraft() {
        assertThatThrownBy(() -> service.publish("admin")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_persistVariableLengthTiles_intact() {
        HomeContent doc = docWithTiles(5);   // a HomeContent whose categoryTiles has 5 elements
        service.saveDraft(doc, "admin");
        assertThat(service.getDraft().categoryTiles()).hasSize(5);
    }

    // ── Test infrastructure configuration ────────────────────────────────────

    /**
     * Supplies the infrastructure interface implementations the content service layer
     * requires, without pulling in Redis, RabbitMQ, or MinIO.
     *
     * <ul>
     *   <li>{@link AuditService} — writes real rows to {@code audit_log} via JdbcTemplate
     *       so integration tests can assert on the audit trail.</li>
     *   <li>{@link FileStorageService} — no-op; image storage is not exercised by these tests.</li>
     * </ul>
     */
    @TestConfiguration
    static class TestInfrastructureConfig {

        @Bean
        @Primary
        AuditService jdbcAuditService(JdbcTemplate jdbcTemplate) {
            return entry -> jdbcTemplate.update(
                    "INSERT INTO audit_log " +
                    "(id, table_name, record_id, action, old_value, new_value, performed_by, created_at) " +
                    "VALUES (gen_random_uuid(), ?, ?::uuid, ?, ?::jsonb, ?::jsonb, ?, NOW())",
                    entry.tableName(),
                    entry.recordId().toString(),
                    entry.action().name(),
                    entry.oldValue(),
                    entry.newValue(),
                    entry.performedBy());
        }

        @Bean
        @Primary
        FileStorageService noOpFileStorageService() {
            return new FileStorageService() {
                @Override public StoredFile upload(String bucket, String key, InputStream content,
                                                   String contentType, long size) {
                    return new StoredFile(key, bucket, contentType, size);
                }
                @Override public InputStream download(String bucket, String key) { return InputStream.nullInputStream(); }
                @Override public void delete(String bucket, String key) {}
                @Override public String getPresignedUrl(String bucket, String key) { return "http://test/" + key; }
            };
        }
    }
}
