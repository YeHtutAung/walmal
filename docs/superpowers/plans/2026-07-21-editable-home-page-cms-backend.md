# Editable Home Page CMS — Backend (`content` module) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new `walmal-content` backend module exposing a draft→publish home-page content API (JSONB document per status) plus a content-image upload endpoint, so the storefront home page becomes admin-editable.

**Architecture:** New Maven module `com.walmal.content` aggregated into `walmal-app`. A single `content_home` table holds the whole home document as JSONB, keyed by `status` (`DRAFT`/`PUBLISHED`); publish copies DRAFT→PUBLISHED. Images go to a new `content-images` MinIO bucket via the existing `FileStorageService`. Public read of published content; ADMIN/STAFF draft edit; ADMIN-only publish; draft read is dual-auth (JWT **or** a shared `previewToken`, mirroring the Stripe-webhook controller-level self-auth pattern).

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Data JPA, Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)`, Flyway, PostgreSQL 15 (JSONB), MinIO via `FileStorageService`, JUnit 5 + Mockito + AssertJ; integration tests against Docker-Compose Postgres (Testcontainers is incompatible in this env — see repo memory).

**Spec:** `docs/superpowers/specs/2026-07-21-editable-home-page-cms-design.md`

**Module conventions:** Follow `CLAUDE.md` (package layout `api`/`domain`/`application`/`infrastructure`/`config`; naming; Definition of Done). This is a net-new module — when executing, route architecture/schema/scaffold review through the walmal agents (`backend-architect` → `database-designer` → `module-builder` → `test-validator` → `security-auditor`); the tasks below encode their conventions directly. `security-auditor` MUST review before this is considered done (new upload path + admin-managed content + a new auth path).

---

## File Structure

**New module `walmal-content/`:**
- `pom.xml` — mirror `walmal-product/pom.xml`. **Main-scope module coupling is `walmal-common` only** (for `FileStorageService`, `AuditService`, `ApiResponse`, `AuthenticatedPrincipal`); but also keep these starters/deps that `walmal-product` carries and Task 5 needs: `spring-boot-starter-data-jpa`, `spring-boot-starter-web`, `spring-boot-starter-validation`, **`spring-boot-starter-security` (main — `@PreAuthorize`, `@AuthenticationPrincipal`, `AccessDeniedException`)**, `spring-boot-starter-test` (test), **`spring-security-test` (test)**, **`walmal-auth` at `<scope>test</scope>`** (so `ContentControllerTest` can `@Import(AuthSecurityConfig)` + use `TokenValidationService`/`JwtProperties` to exercise the real filter chain, exactly as `ProductControllerTest` does), and **`flyway-core` at test scope** (integration test runs migrations). The pom inherits `<excludedGroups>${excludedGroups}</excludedGroups>` (default `integration`) — so `@Tag("integration")` tests are skipped in the normal `test` phase and must be run with `-Dgroups=integration`.
- `src/test/java/com/walmal/content/ContentTestApplication.java` — a bare `@SpringBootApplication` config anchor (copy `walmal-product/src/test/java/com/walmal/product/ProductTestApplication.java`). **Required** — both `@SpringBootTest` and `@WebMvcTest` need a `@SpringBootConfiguration` in the module's test tree or they fail with "Unable to find a @SpringBootConfiguration."
- `src/main/java/com/walmal/content/domain/`
  - `ContentHome.java` — JPA entity: `status` (PK, String), `content` (JSONB → `HomeContent`), `updatedAt`, `updatedBy`.
  - `HomeContent.java` — record: `{ Hero hero, List<CategoryTile> categoryTiles, Promo promo }` (the persisted document AND the API body; one shape, no duplication).
  - `Hero.java`, `Cta.java`, `CategoryTile.java`, `Promo.java` — records with Bean Validation.
  - `ContentStatus.java` — constants `DRAFT` / `PUBLISHED` (String; not an enum, to keep it the entity PK cleanly).
- `src/main/java/com/walmal/content/infrastructure/`
  - `ContentHomeRepository.java` — `JpaRepository<ContentHome, String>`.
  - `ContentImageStorageAdapter.java` — wraps `FileStorageService`, bucket `content-images` (mirrors `ProductImageStorageAdapter`).
- `src/main/java/com/walmal/content/application/`
  - `HomeContentService.java` — interface.
  - `impl/HomeContentServiceImpl.java` — implementation.
  - `dto/ContentImageDto.java` — record `{ String imageUrl }`.
- `src/main/java/com/walmal/content/api/`
  - `ContentController.java` — the 5 endpoints.
- `src/test/java/com/walmal/content/` — `ContentTestApplication.java` (`@SpringBootApplication` anchor), `application/HomeContentServiceImplTest.java` (Mockito), `api/ContentControllerTest.java` (`@WebMvcTest`), `infrastructure/ContentIntegrationTest.java` (Docker-Compose PG, `@Tag("integration")`).

**Modified existing files:**
- `pom.xml` (root) — add `<module>walmal-content</module>`.
- `walmal-app/pom.xml` — add `walmal-content` dependency.
- `walmal-app/src/main/resources/db/migration/V19__content_create_tables.sql` — new.
- `walmal-auth/.../config/AuthSecurityConfig.java` — add two GET paths to `PUBLIC_GET_PATHS`.
- `walmal-app/src/main/resources/application.yml` — `walmal.content.preview-token` binding.
- `docs/adr/ADR-10-content-module.md` — new ADR.
- `docs/kb/SYSTEM.md` — new endpoints, bucket, env var, module.

---

## Task 1: Module scaffold, Maven wiring, migration

**Files:**
- Create: `walmal-content/pom.xml`
- Modify: `pom.xml` (root, module list), `walmal-app/pom.xml` (dependency)
- Create: `walmal-app/src/main/resources/db/migration/V19__content_create_tables.sql`

- [ ] **Step 1: Create `walmal-content/pom.xml`** — copy `walmal-product/pom.xml`, change `<artifactId>` to `walmal-content`. Keep the exact dependency set (do NOT trim it): `walmal-common` (main), `spring-boot-starter-data-jpa`, `-web`, `-validation`, `-security` (all main); `walmal-auth` **test scope**, `spring-boot-starter-test`, `spring-security-test`, `flyway-core` (all test); plus the same Surefire plugin block with `<excludedGroups>${excludedGroups}</excludedGroups>` and the `excludedGroups=integration` property. Match `walmal-product/pom.xml` exactly for parent/versions. (Rationale: `walmal-product` is likewise "walmal-common only" in *main* scope; its `walmal-auth` dep is test-scoped — the controller test needs the real security config.)

- [ ] **Step 2: Register module in root `pom.xml`** — add after the `walmal-notification` line:
```xml
        <module>walmal-content</module>
```
(Place it before `walmal-app` — `walmal-app` must build last.)

- [ ] **Step 3: Add dependency in `walmal-app/pom.xml`** — after the `walmal-notification` dependency block:
```xml
        <dependency>
            <groupId>com.walmal</groupId>
            <artifactId>walmal-content</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 4: Write migration `V19__content_create_tables.sql`:**
```sql
-- =============================================================================
-- TABLE: content_home
-- Home-page CMS document, one row per lifecycle status. The whole editorial
-- document (hero, category tiles, promo) is stored as a single JSONB value;
-- structure is validated at the application layer (Bean Validation on the
-- request DTO). Publishing copies the DRAFT row's content into the PUBLISHED row.
-- Owned exclusively by the walmal-content module.
-- =============================================================================
CREATE TABLE content_home (
    status      VARCHAR(16)  PRIMARY KEY
                             CHECK (status IN ('DRAFT', 'PUBLISHED')),
    content     JSONB        NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(255) NOT NULL
);
-- No seed rows: absence of the PUBLISHED row is the signal the storefront uses
-- to fall back to its built-in static home content.
```

- [ ] **Step 5: Build to verify wiring + migration apply.** Ensure Docker Compose services are up (`docker compose up -d --wait`), then:
```
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./mvnw -q -pl walmal-content,walmal-app -am package -DskipTests
```
Expected: BUILD SUCCESS. Then boot the app (test profile) once and confirm Flyway applies V19 with no error in the log (`grep -i 'V19\|content_home\|Migrating' logs`). Stop the app.

- [ ] **Step 6: Commit**
```bash
git add pom.xml walmal-content/pom.xml walmal-app/pom.xml walmal-app/src/main/resources/db/migration/V19__content_create_tables.sql
git commit -m "feat(content): scaffold walmal-content module + content_home migration"
```

---

## Task 2: Domain content types + entity + repository

**Files:**
- Create: `walmal-content/src/main/java/com/walmal/content/domain/{Cta,Hero,CategoryTile,Promo,HomeContent,ContentHome,ContentStatus}.java`
- Create: `walmal-content/src/main/java/com/walmal/content/infrastructure/ContentHomeRepository.java`
- Test: `walmal-content/src/test/java/com/walmal/content/infrastructure/ContentIntegrationTest.java`

- [ ] **Step 1: Write the content value records** (`domain/`), with Bean Validation. `Cta.java`:
```java
package com.walmal.content.domain;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
public record Cta(
        @NotBlank @Size(max = 40) String label,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^/.*", message = "href must be a site-relative path starting with /")
        String href) {}
```
`Hero.java` (`eyebrow`, `headline`, `subtext`, `primaryCta`, `secondaryCta` nullable, `imageUrl` nullable):
```java
package com.walmal.content.domain;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record Hero(
        @Size(max = 60) String eyebrow,
        @NotBlank @Size(max = 120) String headline,
        @Size(max = 400) String subtext,
        @Valid @NotNull Cta primaryCta,
        @Valid Cta secondaryCta,            // nullable — hero renders 1 or 2 buttons
        @Size(max = 512) String imageUrl) {}
```
`CategoryTile.java`:
```java
package com.walmal.content.domain;
import jakarta.validation.constraints.*;
public record CategoryTile(
        @NotBlank @Size(max = 40) String label,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^/.*") String href,
        @Size(max = 512) String imageUrl) {}
```
`Promo.java`:
```java
package com.walmal.content.domain;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
public record Promo(
        @Size(max = 60) String eyebrow,
        @NotBlank @Size(max = 120) String heading,
        @Size(max = 400) String text,
        @Valid @NotNull Cta cta,
        @Size(max = 512) String imageUrl) {}
```
`HomeContent.java` (the whole document — persisted as JSONB AND used as the API body):
```java
package com.walmal.content.domain;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record HomeContent(
        @Valid @NotNull Hero hero,
        @Valid @Size(max = 12) List<CategoryTile> categoryTiles,   // 0..12, order = display order
        @Valid @NotNull Promo promo) {}
```
`ContentStatus.java`:
```java
package com.walmal.content.domain;
public final class ContentStatus {
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    private ContentStatus() {}
}
```

- [ ] **Step 2: Write the entity** `ContentHome.java` (JSONB via `@JdbcTypeCode`, same pattern as `Order.shippingAddress`):
```java
package com.walmal.content.domain;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "content_home")
public class ContentHome {

    @Id
    @Column(name = "status", length = 16, nullable = false)
    private String status;                    // ContentStatus.DRAFT | PUBLISHED

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb", nullable = false)
    private HomeContent content;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    protected ContentHome() {}

    public ContentHome(String status, HomeContent content, String updatedBy) {
        this.status = status;
        this.content = content;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public void update(HomeContent content, String updatedBy) {
        this.content = content;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getStatus() { return status; }
    public HomeContent getContent() { return content; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
```

- [ ] **Step 3: Write the repository** `ContentHomeRepository.java`:
```java
package com.walmal.content.infrastructure;
import com.walmal.content.domain.ContentHome;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ContentHomeRepository extends JpaRepository<ContentHome, String> {}
```

- [ ] **Step 4: Write a failing persistence test** in `ContentIntegrationTest.java`.

  **⚠ Harness: do NOT copy `ProductIntegrationTest`'s connection setup — it uses Testcontainers (`@Testcontainers` + `PostgreSQLContainer` + `@DynamicPropertySource`), which is INCOMPATIBLE with this environment (repo memory: BadRequestException 400).** The correct Docker-Compose-PG exemplar is `walmal-app/src/test/java/com/walmal/WalmalApplicationTest.java`: an inline `@SpringBootTest(properties = {...})` pointing at `localhost:5432`. Use this shape:
```java
@Tag("integration")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:5432/walmal",
    "spring.datasource.username=walmal",
    "spring.datasource.password=walmal",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    // ContentController @Value binds this with no in-annotation default, so the
    // @SpringBootTest context fails to boot without it (unresolved placeholder).
    "walmal.content.preview-token=test-preview"
})
class ContentIntegrationTest {
    @Autowired ContentHomeRepository repository;
    @Autowired HomeContentService service;
    @Autowired JdbcTemplate jdbcTemplate;
    // reuse the SAME inner-config pattern ProductIntegrationTest uses for beans
    // (this part IS reusable): a @TestConfiguration supplying a no-op
    // FileStorageService and a JdbcTemplate-backed AuditService. Content needs
    // FileStorageService (for uploadImage) + AuditService (for publish).
    ...
}
```
  Copy only `ProductIntegrationTest.TestInfrastructureConfig`'s bean definitions (no-op `FileStorageService`, JdbcTemplate `AuditService`), NOT its container/datasource wiring. The `ContentTestApplication` anchor (Task 1) provides the `@SpringBootConfiguration`. First test:
```java
@Test
void should_roundTripJsonbDocument_when_savedAndReloaded() {
    HomeContent doc = sampleDoc();               // helper building a full HomeContent
    repository.save(new ContentHome(ContentStatus.DRAFT, doc, "tester"));
    ContentHome reloaded = repository.findById(ContentStatus.DRAFT).orElseThrow();
    assertThat(reloaded.getContent().hero().headline()).isEqualTo(doc.hero().headline());
    assertThat(reloaded.getContent().categoryTiles()).hasSize(doc.categoryTiles().size());
    assertThat(reloaded.getContent().promo().cta().href()).isEqualTo(doc.promo().cta().href());
}
```

- [ ] **Step 5: Run the test, expect FAIL** (until Flyway migration + entity are on the test classpath). Ensure the test module has the migrations it needs — integration tests load `classpath:db/migration`; copy `V1__common_create_audit_log.sql` (audit_log, needed by the AuditService bean) and `V19__content_create_tables.sql` into `walmal-content/src/test/resources/db/migration/` (mirror how `walmal-product/src/test/resources/db/migration/` is populated). **These are `@Tag("integration")` tests excluded from the default phase — you MUST pass `-Dgroups=integration` or the command runs 0 tests and falsely reports success:**
```
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./mvnw -q -pl walmal-content test -Dgroups=integration -DexcludedGroups= -Dtest=ContentIntegrationTest
```
Expected initially: FAIL/ERROR (and confirm "Tests run: 1" — not "Tests run: 0"). Docker-Compose services must be up (`docker compose up -d --wait`). **`-DexcludedGroups=` is REQUIRED in addition to `-Dgroups=integration`:** the module pom sets `excludedGroups=integration` by default, and when a tag is in both include and exclude, exclude wins → 0 tests (false green). Verified during Task 2.

- [ ] **Step 6: Make it pass** — ensure migrations are in `src/test/resources/db/migration/` and the datasource properties point at localhost:5432; rerun with `-Dgroups=integration`. Expected: PASS (JSONB round-trips), "Tests run: 1, Failures: 0".

- [ ] **Step 7: Commit**
```bash
git add walmal-content/src/main/java/com/walmal/content/domain walmal-content/src/main/java/com/walmal/content/infrastructure/ContentHomeRepository.java walmal-content/src/test
git commit -m "feat(content): domain document types, JSONB entity, repository + round-trip test"
```

---

## Task 3: Content-image storage adapter

**Files:**
- Create: `walmal-content/.../infrastructure/ContentImageStorageAdapter.java`
- Test: unit test in `walmal-content/src/test/.../infrastructure/ContentImageStorageAdapterTest.java`

- [ ] **Step 1: Write a failing unit test** (Mockito `FileStorageService`) asserting `store(section, filename, stream, size, contentType)` builds key `home/{section}/{uuid}-{filename}`, calls `upload("content-images", key, ...)`, and `getUrl(key)` delegates to `getPresignedUrl("content-images", key)`. Verify the uuid prefix via a regex on the captured key.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement `ContentImageStorageAdapter`** mirroring `ProductImageStorageAdapter` (constructor-inject `FileStorageService`; `BUCKET = "content-images"`; `store(...)`, `getUrl(...)`; key `String.format("home/%s/%s-%s", section, UUID.randomUUID(), safe(filename))` where `safe()` strips path separators). Only this class touches `FileStorageService` (DIP).

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Commit** `feat(content): content-images MinIO storage adapter`.

---

## Task 4: HomeContentService + unit tests

**Files:**
- Create: `.../application/HomeContentService.java`, `.../application/impl/HomeContentServiceImpl.java`, `.../application/dto/ContentImageDto.java`
- Test: `.../application/HomeContentServiceImplTest.java`

- [ ] **Step 1: Define the interface** `HomeContentService`:
```java
public interface HomeContentService {
    Optional<HomeContent> getPublished();                 // empty if never published
    HomeContent getDraft();                                // draft, else published, else DEFAULT
    void saveDraft(HomeContent content, String performedBy);
    void publish(String performedBy);                      // DRAFT -> PUBLISHED (+audit)
    ContentImageDto uploadImage(String section, InputStream data, String filename,
                                String contentType, long size, String performedBy);
}
```
`ContentImageDto`: `public record ContentImageDto(String imageUrl) {}`.

- [ ] **Step 2: Write failing unit tests** (Mockito repo + adapter + `AuditService`):
  - `getPublished` returns empty when repo has no PUBLISHED row; returns content when present.
  - `saveDraft` upserts the DRAFT row (new when absent; `update(...)` when present).
  - `publish` reads DRAFT, upserts PUBLISHED with the draft's content, and calls `auditService.log(...)` **before** the save (verify order with `InOrder`).
  - `publish` with no DRAFT row throws `IllegalStateException` (→ maps to 409/400; see controller).
  - `uploadImage` calls the adapter and returns its URL wrapped in `ContentImageDto`.
  - `getDraft` falls back: DRAFT → else PUBLISHED → else a non-null DEFAULT document (define `HomeContent DEFAULT` mirroring today's static copy so the editor always opens populated).

- [ ] **Step 3: Run → FAIL.**

- [ ] **Step 4: Implement `HomeContentServiceImpl`** (`@Service @Transactional`). Constructor-inject `ContentHomeRepository`, `ContentImageStorageAdapter`, `AuditService`. Implement upsert helpers keyed by status; `publish` writes an `AuditEntry("content_home", CONTENT_HOME_AUDIT_ID, AuditAction.UPDATE, oldJson, newJson, performedBy)` **before** persisting the PUBLISHED row (Audit Log rule). Note: `audit_log.record_id` is `UUID NOT NULL` but `content_home`'s PK is a `String` — so use a **fixed sentinel `UUID` constant** `CONTENT_HOME_AUDIT_ID` (not null, not the string PK). `AuditAction` has only `DELETE`/`UPDATE`/`STATUS_CHANGE` — use `UPDATE` (there is no `PUBLISH` value despite the spec prose). Include a `static final HomeContent DEFAULT` built from the current storefront copy (hero "Own the pitch." etc.) — used only by `getDraft` fallback.

- [ ] **Step 5: Run → PASS.**

- [ ] **Step 6: Commit** `feat(content): HomeContentService (draft/publish/upload) + unit tests`.

---

## Task 5: ContentController + security wiring (incl. preview-token dual-auth)

**Files:**
- Create: `.../api/ContentController.java`
- Modify: `walmal-auth/.../config/AuthSecurityConfig.java`
- Modify: `walmal-app/src/main/resources/application.yml`
- Test: `.../api/ContentControllerTest.java` (`@WebMvcTest`)

- [ ] **Step 1: Add the preview-token config** to `application.yml` under a `walmal:` `content:` key:
```yaml
walmal:
  content:
    preview-token: ${CONTENT_PREVIEW_TOKEN:dev-preview-token-change-me}
```

- [ ] **Step 2: Allowlist the public + dual-auth GET paths** in `AuthSecurityConfig.PUBLIC_GET_PATHS` — add:
```java
            // Home CMS: published content is public; the draft read is dual-auth
            // (JWT or previewToken) and self-authorizes in ContentController, so it
            // must pass the filter chain without a mandatory JWT. Mutating content
            // routes (PUT/POST) are NOT listed here — they hit anyRequest().authenticated()
            // + method security.
            "/api/v1/content/home",
            "/api/v1/content/home/draft",
```
(Exact-path entries — do NOT use `/api/v1/content/**`, which would expose the mutating routes.)

- [ ] **Step 3: Write failing `@WebMvcTest` tests** for `ContentController` (mock `HomeContentService`), covering:
  - `GET /api/v1/content/home` → 200 with published body; → 204 when service returns empty.
  - `GET /api/v1/content/home/draft?previewToken=<correct>` → 200 (no JWT). Wrong/missing token AND no admin auth → 401/403.
  - `GET /api/v1/content/home/draft` with ADMIN principal (no token) → 200.
  - `PUT /api/v1/content/home/draft` with invalid body (blank headline / non-`/` href) → 400 (Bean Validation).
  - `POST /api/v1/content/home/publish` → 204.
  - `POST /api/v1/content/images` multipart → 201 with `{ imageUrl }`.
  (Use `@WithMockUser(roles=...)` for the role paths; the previewToken path is tested by injecting the token value via `@TestPropertySource` or `@Value` mock.)

- [ ] **Step 4: Run → FAIL.**

- [ ] **Step 5: Implement `ContentController`** (`@RestController @RequestMapping("/api/v1/content")`). Inject `HomeContentService` and `@Value("${walmal.content.preview-token}") String previewToken`. Endpoints (mirror `ProductImageController` for multipart + `ApiResponse` envelope):
  - `GET /home` → `getPublished()`; return `204 No Content` when empty, else `ApiResponse.ok(content)`.
  - `GET /home/draft` (params: optional `previewToken`, `@AuthenticationPrincipal AuthenticatedPrincipal principal`) — **self-authorize** (no `@PreAuthorize` on this method, since the previewToken path carries no JWT). `AuthenticatedPrincipal` is `record(UUID userId, String username, String role)` — it has **no `hasRole` method**; compare the `role()` string (stored without the `ROLE_` prefix). Allow if `previewToken != null && MessageDigest.isEqual(previewToken.getBytes(UTF_8), configuredToken.getBytes(UTF_8))` OR `principal != null && (principal.role().equals("ADMIN") || principal.role().equals("STAFF"))`; else throw `AccessDeniedException`. Return `ApiResponse.ok(getDraft())`.
  - `PUT /home/draft` `@PreAuthorize("hasAnyRole('ADMIN','STAFF')")`, `@Valid @RequestBody HomeContent` → `saveDraft(...)`, return `ApiResponse.ok("Draft saved", null)` or 204.
  - `POST /home/publish` `@PreAuthorize("hasRole('ADMIN')")` → `publish(principal.username())`, 204. Catch `IllegalStateException` (no draft) → 409 via the global handler (add a mapping if needed) or a `@ResponseStatus` exception.
  - `POST /images` `@PreAuthorize("hasAnyRole('ADMIN','STAFF')")`, params `section` + `MultipartFile file` → validate `file.getContentType()` starts with `image/` (400 otherwise), call `uploadImage(...)`, return `201` `ApiResponse.ok("Image uploaded", dto)`.
  - Springdoc `@Operation`/`@ApiResponses`/`@SecurityRequirement` on every method (Definition of Done).

- [ ] **Step 6: Run → PASS.** Also run the whole `walmal-content` + `walmal-auth` unit suites to ensure the security-config change didn't break `CorsMethodCoverageTest`/auth tests.

- [ ] **Step 7: Commit** `feat(content): REST controller + public/preview-token/role security wiring`.

---

## Task 6: End-to-end integration test (Docker-Compose PG)

**Files:**
- Modify: `.../infrastructure/ContentIntegrationTest.java`

- [ ] **Step 1: Add a full-flow test** using the real service + repo (FileStorageService no-op bean already provided):
```java
@Test
void should_publishDraft_when_draftExists_andExposeItAsPublished() {
    service.saveDraft(sampleDoc(), "admin");
    assertThat(service.getPublished()).isEmpty();           // not yet published
    service.publish("admin");
    assertThat(service.getPublished()).isPresent();
    assertThat(service.getPublished().get().hero().headline())
            .isEqualTo(sampleDoc().hero().headline());
    // audit row written
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
    HomeContent doc = docWithTiles(5);
    service.saveDraft(doc, "admin");
    assertThat(service.getDraft().categoryTiles()).hasSize(5);
}
```

- [ ] **Step 2: Run the integration test** (Docker-Compose services up) — **`-Dgroups=integration` required** (else 0 tests run):
```
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./mvnw -q -pl walmal-content test -Dgroups=integration -DexcludedGroups=
```
Expected: PASS; confirm the run count matches the number of `@Test` methods (not "Tests run: 0"). (`-DexcludedGroups=` is required — see Task 2 Step 5.)

- [ ] **Step 3: Commit** `test(content): end-to-end draft/publish integration coverage`.

---

## Task 7: OpenAPI, ADR, KB/SYSTEM.md, docs

**Files:**
- Create: `docs/adr/ADR-10-content-module.md`
- Modify: `docs/kb/SYSTEM.md`, `README.md` (if module count / endpoint list documented)

- [ ] **Step 1: Write `ADR-10-content-module.md`** — decision: new `content` module; JSONB document per status vs relational (rejected); public-read/dual-auth-draft/admin-publish security; no product dependency (category picker is admin-side). Follow the format of an existing ADR (e.g. `ADR-9-api-gateway-layer.md`).

- [ ] **Step 2: Update `docs/kb/SYSTEM.md`** — add to the module/endpoint facts: `/api/v1/content/home` (public GET), `/api/v1/content/home/draft` (dual-auth GET), `PUT /content/home/draft`, `POST /content/home/publish` (ADMIN), `POST /content/images`; the `content-images` MinIO bucket; the `CONTENT_PREVIEW_TOKEN` env var; note the new `walmal-content` module. Per `CLAUDE.md` this must happen in the same work session.

- [ ] **Step 3: Update `README.md`** if it states a module count or endpoint inventory that this changes.

- [ ] **Step 4: Verify OpenAPI** — boot the app, GET `http://localhost:8080/v3/api-docs`, confirm the `content` endpoints appear with schemas. (Definition of Done: OpenAPI docs generated.)

- [ ] **Step 5: Commit** `docs(content): ADR-10, SYSTEM.md endpoints/bucket/env, OpenAPI`.

---

## Task 8: Full build, boot, manual verification

- [ ] **Step 1: Full build (unit tests only; integration tagged/needs PG):**
```
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./mvnw -q package -DskipTests
```
Expected: BUILD SUCCESS across the reactor (incl. `walmal-content`, `walmal-app`).

- [ ] **Step 2: Boot the app** (Docker-Compose services up) with `CONTENT_PREVIEW_TOKEN=test-preview` set, wait for `/actuator/health` = UP.

- [ ] **Step 3: Manual curl verification** (login as `admin_test`/`AdminPass123!`):
  - `GET /api/v1/content/home` → **204** (nothing published yet).
  - `PUT /api/v1/content/home/draft` with a valid document (bearer admin) → 200.
  - `GET /api/v1/content/home/draft?previewToken=test-preview` (no bearer) → **200** with the draft.
  - `GET /api/v1/content/home/draft?previewToken=wrong` (no bearer) → **401/403**.
  - `POST /api/v1/content/home/publish` (bearer admin) → **204**.
  - `GET /api/v1/content/home` → **200** with the published document.
  - `POST /api/v1/content/images` (multipart, a seed PNG) → **201** with `{ imageUrl }`; GET that URL through MinIO → 200 image.
  - `PUT /api/v1/content/home/draft` with blank headline → **400**.

- [ ] **Step 4: Confirm no regressions** — run `./mvnw -q -pl walmal-auth test` (security config change) and the product suite; all green.

- [ ] **Step 5: Final commit** (if any doc/cleanup remains) and stop the local app.

---

## Deploy (separate, gated step — not part of task execution)

After the plan is fully executed and verified locally: this is a backend change → deploys via push to `main` (CI build → SSH pull+restart `app` → smoke test). **Live production deploy to `api.yehtutaung.xyz` — requires explicit user confirmation at push time** (per session policy). Before first publish in prod, `GET /content/home` returns 204 and the storefront is unaffected, so the backend can ship ahead of admin/store safely.

## Follow-on plans (written after this lands)
- **Plan 2 — `walmal-admin`**: Home Page editor (forms, image upload, add/remove/reorder tiles, Save Draft/Preview/Publish).
- **Plan 3 — `walmal-store`**: data-driven `hero`/`category-tiles`/`promo-banner` with static fallback + Next.js Draft Mode preview route (using `CONTENT_PREVIEW_TOKEN`).
