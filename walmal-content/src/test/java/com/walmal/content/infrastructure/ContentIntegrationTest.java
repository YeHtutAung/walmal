package com.walmal.content.infrastructure;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence round-trip test for the {@code content_home} JSONB document.
 *
 * <p>Uses the Docker-Compose Postgres directly (localhost:5432/walmal) — NOT
 * Testcontainers, which is incompatible in this environment. Flyway validates the
 * classpath migrations (V1..V19) against the already-migrated shared DB, then
 * Hibernate validates the schema. The test asserts a full {@link HomeContent}
 * document survives a save/reload round-trip through the JSONB column.</p>
 *
 * <p>Tagged {@code "integration"} — run with {@code mvn test -Dgroups=integration}.
 * Requires Docker Compose services running.</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/walmal",
    "spring.datasource.username=walmal",
    "spring.datasource.password=walmal",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true"
})
class ContentIntegrationTest {

    @Autowired
    ContentHomeRepository repository;

    // Keep the shared Docker-Compose DB clean — this test writes a real DRAFT row.
    @AfterEach
    void cleanUp() {
        repository.deleteById(ContentStatus.DRAFT);
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
}
