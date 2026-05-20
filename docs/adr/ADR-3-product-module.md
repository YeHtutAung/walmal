# ADR-3: Product Module Design

**Date**: 2026-05-20
**Status**: Accepted
**Module**: walmal-product (Build Order Step 3)
**Authors**: Backend Architect Agent

---

## Context

The Product module is the master data authority for all sellable items in walmal.
It owns the canonical record for what a product is (name, description, brand, status),
how it is subdivided (variants with SKUs and barcodes), how it is priced (one active
price row per variant), and what images represent it (MinIO-backed binary storage).

Inventory (Step 4) and Order (Step 5) reference product variants by UUID. They must
never reach across the module boundary to query product tables directly. The Product
module exposes three ISP-segregated service interfaces for their respective consumers.

This ADR records the decisions for the module's domain model, service interface design,
image storage strategy, caching approach, event contracts, and audit log requirements.

---

## Decision Drivers

1. Module boundary integrity: Inventory and Order reference `variant_id` as a UUID
   foreign key concept only — they never query `product_variants` directly.
2. ISP compliance: Order/POS consumers need catalog and pricing lookups. API Gateway
   needs search. Neither consumer should depend on methods it does not use.
3. DIP compliance: MinIO accessed only via `FileStorageService`, RabbitMQ only via
   `DomainEventPublisher`, Redis only via `CacheService`, audit only via `AuditService`.
4. Price correctness: Only one price row per variant may be "active" at any moment.
   This constraint must be enforced at the database level, not only in application code.
5. Read performance: Product catalog, variant lookup, and category tree are the hottest
   read paths in the platform. Redis caching is mandatory on these paths.
6. Audit compliance: Deactivation, image deletion, and price changes are destructive
   operations and must write to `audit_log` before the DB mutation executes.
7. Flyway migration number: V3 (follows V1 infrastructure, V2 auth).

---

## Considered Options

### Category Hierarchy: Closure Table vs. Adjacency List

**Option A: Closure Table**
Stores all ancestor–descendant pairs in a separate table. Enables O(1) subtree queries
without recursion.
- Rejected for MVP: adds a second table and insert-time triggers for what is a
  relatively shallow hierarchy (typically 3 levels for retail). Operational complexity
  outweighs the benefit.

**Option B: Nested Sets (MPTT)**
Stores left/right boundaries. Reads are fast; writes require re-numbering.
- Rejected: category tree updates (adding a subcategory) require table-wide re-numbering.
  Unacceptable for a live retail catalog.

**Option C: Adjacency List (self-referencing FK) [SELECTED]**
Each row carries `parent_id UUID REFERENCES product_categories(id)`. Top-level categories
have `parent_id IS NULL`. PostgreSQL recursive CTEs (`WITH RECURSIVE`) retrieve full
subtrees efficiently for the relatively shallow depth of a retail category tree.
- Accepted: simplest schema, Spring Data JPA supports self-referencing associations,
  subtree queries are handled in the `ProductSearchService` implementation using
  native queries when needed.

### Price Constraint: Application-level vs. DB Partial Unique Index

**Option A: Application-level enforcement only**
Service checks for an existing active price before insert.
- Rejected: race condition under concurrent requests. Two threads can both pass the
  check and both insert, leaving two active price rows.

**Option B: DB partial unique index [SELECTED]**
```sql
CREATE UNIQUE INDEX idx_product_prices_active_variant
  ON product_prices (variant_id)
  WHERE effective_from = (
    SELECT MAX(p2.effective_from) FROM product_prices p2
    WHERE p2.variant_id = product_prices.variant_id
  );
```
A cleaner and simpler approach: the table keeps one row per variant at all times.
When a price is updated, the existing row is updated in place (not inserted). The
`AuditService.log()` call with `AuditAction.UPDATE` captures the old amount before
the update executes. This eliminates the need for a partial index on a mutable column
and avoids orphaned historical rows.

**Decision**: One row per variant, enforced by a `UNIQUE` constraint on `variant_id`
in `product_prices`. A price "change" is an UPDATE on that row, preceded by an
`AuditService.log()` call. No price history table in MVP.

### Image Primary Flag: Boolean Column vs. Sort Order = 0

**Option A: `is_primary BOOLEAN` column**
Explicit flag. Requires application-level enforcement that exactly one image per
product/variant is primary.
- Selected. Clearer intent, simpler query (`WHERE is_primary = true`). A partial unique
  index on `(product_id, is_primary) WHERE is_primary = true` enforces uniqueness at
  DB level per product.

### Product Status Transitions: Enum + Application Guard vs. State Machine

**Option A: State machine library (e.g. Spring Statemachine)**
- Rejected for MVP: significant framework overhead, XML/annotation configuration,
  overkill for two states (ACTIVE, INACTIVE) and one allowed transition each way.

**Option B: Enum + guard method in domain entity [SELECTED]**
`ProductStatus` enum with `ACTIVE` and `INACTIVE` values. The `Product` entity exposes
`activate()` and `deactivate()` methods that enforce valid transitions and throw
`BusinessRuleException` on invalid transitions. New statuses (e.g. `DISCONTINUED`,
`DRAFT`) extend the enum without modifying existing guard logic — OCP compliant.

---

## Decision

### Owned Tables

All five tables are owned exclusively by walmal-product. No other module may JOIN
against them or inject any of their Repository beans.

#### `product_categories`

```
id            UUID         PRIMARY KEY DEFAULT gen_random_uuid()
parent_id     UUID         REFERENCES product_categories(id) ON DELETE RESTRICT
name          VARCHAR(100) NOT NULL
slug          VARCHAR(120) NOT NULL
description   TEXT
is_active     BOOLEAN      NOT NULL DEFAULT TRUE
created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()

UNIQUE (slug)
INDEX (parent_id)
INDEX (is_active)
```

Natural key: `slug` (URL-safe, human-readable, unique).
Self-referencing FK: `parent_id` — adjacency list hierarchy.
`ON DELETE RESTRICT` prevents orphaning children when a category is deleted.

#### `product_products`

```
id            UUID         PRIMARY KEY DEFAULT gen_random_uuid()
category_id   UUID         NOT NULL REFERENCES product_categories(id)
name          VARCHAR(255) NOT NULL
slug          VARCHAR(300) NOT NULL
description   TEXT
brand         VARCHAR(100)
status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'INACTIVE'))
created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()

UNIQUE (slug)
INDEX (category_id)
INDEX (status)
INDEX (brand)
```

Natural key: `slug`. Status is constrained by CHECK at DB level.

#### `product_variants`

```
id            UUID         PRIMARY KEY DEFAULT gen_random_uuid()
product_id    UUID         NOT NULL REFERENCES product_products(id)
sku           VARCHAR(100) NOT NULL
barcode       VARCHAR(50)
color         VARCHAR(50)
size          VARCHAR(50)
status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'INACTIVE'))
created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()

UNIQUE (sku)
INDEX (product_id)
INDEX (barcode)
INDEX (status)
```

Natural key: `sku` — globally unique. This is the identifier that Inventory and Order
store as a foreign key concept (they store the UUID, never query `product_variants`).
`barcode` may be null for products without barcodes (e.g. custom-order items).

#### `product_prices`

```
id            UUID           PRIMARY KEY DEFAULT gen_random_uuid()
variant_id    UUID           NOT NULL REFERENCES product_variants(id)
amount        NUMERIC(10,2)  NOT NULL CHECK (amount >= 0)
currency      VARCHAR(3)     NOT NULL DEFAULT 'USD'
effective_from TIMESTAMPTZ   NOT NULL DEFAULT NOW()
created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW()
updated_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW()

UNIQUE (variant_id)
```

One row per variant. The `UNIQUE (variant_id)` constraint is the DB-level enforcement
of the single-active-price rule. A price change is an UPDATE on this row, not an INSERT.
`AuditService.log()` with `AuditAction.UPDATE` captures old amount before execution.

#### `product_images`

```
id            UUID         PRIMARY KEY DEFAULT gen_random_uuid()
product_id    UUID         NOT NULL REFERENCES product_products(id)
variant_id    UUID         REFERENCES product_variants(id)
storage_key   VARCHAR(512) NOT NULL
cdn_url       VARCHAR(512) NOT NULL
alt_text      VARCHAR(255)
display_order INTEGER      NOT NULL DEFAULT 0
is_primary    BOOLEAN      NOT NULL DEFAULT FALSE
created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()

UNIQUE (product_id) WHERE is_primary = true   -- one primary image per product
INDEX (product_id, display_order)
INDEX (variant_id)
```

`storage_key` holds the MinIO object key (e.g. `products/{productId}/{variantId}/{filename}`).
`cdn_url` is the publicly accessible URL returned by `FileStorageService.getPresignedUrl()`
or a CDN prefix in production. `variant_id` is nullable: a product-level image has
no variant association.

Flyway migration: `V3__product_create_tables.sql`

---

## Package Structure

```
walmal-product/
  pom.xml

  src/main/java/com/walmal/product/
    api/
      ProductController.java          (@RestController, /api/v1/product/products)
      CategoryController.java         (@RestController, /api/v1/product/categories)
      ProductImageController.java     (@RestController, /api/v1/product/products/{id}/images)
      dto/
        request/
          CreateProductRequest.java
          UpdateProductRequest.java
          CreateVariantRequest.java
          UpdateVariantRequest.java
          CreateCategoryRequest.java
          SetPriceRequest.java
          UploadImageRequest.java
        response/
          ProductResponse.java
          ProductDetailResponse.java
          VariantResponse.java
          CategoryResponse.java
          PriceResponse.java
          ImageResponse.java

    domain/
      Category.java                   (@Entity, table: product_categories)
      Product.java                    (@Entity, table: product_products)
      ProductVariant.java             (@Entity, table: product_variants)
      ProductPrice.java               (@Entity, table: product_prices)
      ProductImage.java               (@Entity, table: product_images)
      ProductStatus.java              (enum: ACTIVE, INACTIVE)
      event/
        ProductCreatedEvent.java      (extends DomainEvent)
        ProductPriceChangedEvent.java (extends DomainEvent)
        ProductDetailsChangedEvent.java (extends DomainEvent)
        ProductDeactivatedEvent.java  (extends DomainEvent)

    application/
      ProductCatalogService.java      (interface — cross-module: Order, POS)
      ProductPricingService.java      (interface — cross-module: Order, POS)
      ProductSearchService.java       (interface — cross-module: API Gateway)
      ProductManagementService.java   (interface — internal: admin operations)
      ProductImageService.java        (interface — internal: image upload/delete)
      impl/
        ProductCatalogServiceImpl.java
        ProductPricingServiceImpl.java
        ProductSearchServiceImpl.java
        ProductManagementServiceImpl.java
        ProductImageServiceImpl.java

    infrastructure/
      CategoryRepository.java         (JpaRepository<Category, UUID>)
      ProductRepository.java          (JpaRepository<Product, UUID>)
      ProductVariantRepository.java   (JpaRepository<ProductVariant, UUID>)
      ProductPriceRepository.java     (JpaRepository<ProductPrice, UUID>)
      ProductImageRepository.java     (JpaRepository<ProductImage, UUID>)
      ProductImageStorageAdapter.java (wraps FileStorageService)

    config/
      ProductRabbitMQConfig.java      (declares product.exchange, queues)
      ProductCacheConfig.java         (cache key constants and TTL config)
      ProductOpenApiConfig.java       (Springdoc GroupedOpenApi for /product/**)

  src/test/java/com/walmal/product/
    api/
      ProductControllerTest.java      (@WebMvcTest)
      CategoryControllerTest.java     (@WebMvcTest)
      ProductImageControllerTest.java (@WebMvcTest)
    domain/
      ProductStatusTransitionTest.java
      ProductVariantTest.java
    application/
      ProductManagementServiceImplTest.java  (Mockito)
      ProductPricingServiceImplTest.java     (Mockito)
      ProductImageServiceImplTest.java       (Mockito)
    infrastructure/
      ProductRepositoryTest.java      (@DataJpaTest, Testcontainers)
      ProductIntegrationTest.java     (@SpringBootTest, Testcontainers)
```

---

## Domain Model Detail

### Category (@Entity, table: product_categories)

```java
@Entity
@Table(name = "product_categories")
public class Category extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;                    // null for root categories

    @OneToMany(mappedBy = "parent")
    private List<Category> children = new ArrayList<>();

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 120)
    private String slug;                        // natural key — URL-safe

    @Column(name = "description")
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
```

### Product (@Entity, table: product_products)

```java
@Entity
@Table(name = "product_products")
public class Product extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 300)
    private String slug;                        // natural key

    @Column(name = "description")
    private String description;

    @Column(name = "brand", length = 100)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status = ProductStatus.ACTIVE;

    // Guard methods enforce valid transitions
    public void activate() {
        if (this.status == ProductStatus.ACTIVE) {
            throw new BusinessRuleException("Product is already ACTIVE");
        }
        this.status = ProductStatus.ACTIVE;
    }

    public void deactivate() {
        if (this.status == ProductStatus.INACTIVE) {
            throw new BusinessRuleException("Product is already INACTIVE");
        }
        this.status = ProductStatus.INACTIVE;
    }
}
```

### ProductVariant (@Entity, table: product_variants)

```java
@Entity
@Table(name = "product_variants")
public class ProductVariant extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "sku", nullable = false, unique = true, length = 100)
    private String sku;                         // natural key — globally unique

    @Column(name = "barcode", length = 50)
    private String barcode;                     // nullable

    @Column(name = "color", length = 50)
    private String color;

    @Column(name = "size", length = 50)
    private String size;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status = ProductStatus.ACTIVE;

    public void deactivate() { ... }            // same guard pattern as Product
    public void activate() { ... }
}
```

### ProductPrice (@Entity, table: product_prices)

```java
@Entity
@Table(name = "product_prices")
public class ProductPrice extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false, unique = true)
    private ProductVariant variant;             // UNIQUE enforced by DB

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
}
```

### ProductImage (@Entity, table: product_images)

```java
@Entity
@Table(name = "product_images")
public class ProductImage extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;             // nullable — product-level image

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;                  // MinIO object key

    @Column(name = "cdn_url", nullable = false, length = 512)
    private String cdnUrl;                      // presigned or CDN URL

    @Column(name = "alt_text", length = 255)
    private String altText;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;
}
```

---

## Service Interfaces

### Cross-module: ProductCatalogService (consumed by Order, POS)

ISP rationale: Order and POS need to confirm that a variant exists and is sellable.
They do not need search or price lookup from this interface.

```java
package com.walmal.product.application;

import java.util.Optional;
import java.util.UUID;

public interface ProductCatalogService {

    /**
     * Returns lightweight variant details by SKU for use during order creation.
     * Returns empty if the SKU does not exist or belongs to an inactive variant/product.
     */
    Optional<VariantSummaryDto> findVariantBySku(String sku);

    /**
     * Returns product details for display in Order confirmation and POS receipt.
     * Throws ResourceNotFoundException if the product does not exist.
     */
    ProductDetailDto getProductDetails(UUID productId);

    /**
     * Returns true if the variant exists and both the variant and its parent product
     * have status ACTIVE. Used by Order to reject orders for discontinued items.
     */
    boolean isVariantActive(UUID variantId);
}
```

DTOs used (in `com.walmal.product.api.dto.response`, exposed as shared types):

```java
// VariantSummaryDto — minimal, safe cross-module projection
record VariantSummaryDto(
    UUID variantId,
    UUID productId,
    String sku,
    String barcode,
    String productName,
    String color,
    String size
) {}

// ProductDetailDto — richer, for display purposes
record ProductDetailDto(
    UUID productId,
    String name,
    String slug,
    String brand,
    String description,
    String status,
    String categoryName
) {}
```

### Cross-module: ProductPricingService (consumed by Order, POS)

ISP rationale: Pricing is a distinct concern from catalog lookup. Order uses pricing
at checkout; POS uses pricing at till. Neither consumer needs search capabilities.

```java
package com.walmal.product.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface ProductPricingService {

    /**
     * Returns the current active price for the given variant.
     * Returns empty if no price record exists for the variant.
     * This is the hot path called on every order line and POS scan.
     */
    Optional<PriceDto> getCurrentPrice(UUID variantId);

    /**
     * Returns the price for a variant, throwing BusinessRuleException
     * if no price is set. Prefer this over getCurrentPrice() when
     * a missing price should halt order creation.
     */
    PriceDto getPriceForVariant(UUID variantId);
}
```

```java
record PriceDto(
    UUID variantId,
    BigDecimal amount,
    String currency,
    Instant effectiveFrom
) {}
```

### Cross-module: ProductSearchService (consumed by API Gateway)

ISP rationale: Search and browse are read-only, high-traffic operations used only by
the API Gateway (web storefront, mobile). Order and POS never need free-text search.

```java
package com.walmal.product.application;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductSearchService {

    /**
     * Full-text search across product name, brand, and description.
     * Returns a paginated result of product summaries.
     */
    Page<ProductSummaryDto> searchProducts(String query, Pageable pageable);

    /**
     * Returns all ACTIVE products in the given category, including products
     * in descendant categories. Uses recursive CTE at the DB layer.
     */
    Page<ProductSummaryDto> listByCategory(UUID categoryId, Pageable pageable);

    /**
     * Returns the full active category tree, starting from roots.
     * Cached — category tree changes rarely.
     */
    List<CategoryTreeDto> getCategoryTree();
}
```

```java
record ProductSummaryDto(
    UUID productId,
    String name,
    String slug,
    String brand,
    String primaryImageUrl,
    BigDecimal lowestPrice,
    String currency
) {}

record CategoryTreeDto(
    UUID categoryId,
    String name,
    String slug,
    List<CategoryTreeDto> children
) {}
```

### Internal: ProductManagementService (admin operations, same module)

Not exposed across module boundaries. Used by `ProductController` within walmal-product.

```java
package com.walmal.product.application;

// Method signatures — representative, non-exhaustive
public interface ProductManagementService {
    ProductDetailDto createProduct(CreateProductRequest request, String performedBy);
    ProductDetailDto updateProductDetails(UUID productId, UpdateProductRequest request, String performedBy);
    void deactivateProduct(UUID productId, String performedBy);   // writes audit_log first
    void activateProduct(UUID productId, String performedBy);

    VariantSummaryDto createVariant(UUID productId, CreateVariantRequest request, String performedBy);
    void deactivateVariant(UUID variantId, String performedBy);   // writes audit_log first
    void activateVariant(UUID variantId, String performedBy);

    PriceDto setPrice(UUID variantId, SetPriceRequest request, String performedBy);   // audit_log first
    CategoryResponse createCategory(CreateCategoryRequest request);
}
```

### Internal: ProductImageService (image upload/delete, same module)

```java
package com.walmal.product.application;

public interface ProductImageService {
    ImageResponse uploadImage(UUID productId, UUID variantId,
                              InputStream content, String filename,
                              String contentType, long size,
                              String altText, boolean isPrimary,
                              String performedBy);
    void deleteImage(UUID imageId, String performedBy);   // writes audit_log first
    List<ImageResponse> listImages(UUID productId);
}
```

---

## Image Storage Strategy

**Bucket**: `product-images`

**Object key pattern**: `products/{productId}/{variantId}/{filename}`
For product-level images (no variant): `products/{productId}/_product/{filename}`

**Upload flow**:
1. `ProductImageController` receives `MultipartFile` via `POST /api/v1/product/products/{id}/images`.
2. Controller delegates to `ProductImageService.uploadImage(...)`.
3. `ProductImageServiceImpl` calls `FileStorageService.upload(bucket, key, inputStream, contentType, size)`.
4. `FileStorageService` returns a `StoredFile` record (key, bucket, contentType, size).
5. `ProductImageServiceImpl` calls `FileStorageService.getPresignedUrl(bucket, key)` to obtain the `cdnUrl`.
6. A `ProductImage` entity is persisted with `storageKey` and `cdnUrl`.
7. If `isPrimary = true`, the service first clears any existing `is_primary = true` row
   for that product, then sets the new row as primary.

**Delete flow**:
1. `AuditService.log()` is called with `AuditAction.DELETE` before any DB or storage mutation.
2. `FileStorageService.delete(bucket, storageKey)` removes the object from MinIO.
3. The `ProductImage` row is deleted from the database.

**What ProductImage stores**: `storageKey` (MinIO key), `cdnUrl` (presigned/CDN URL),
`altText`, `displayOrder`, `isPrimary`. The module never stores raw binary data in PostgreSQL.

**DIP**: `ProductImageStorageAdapter` in `infrastructure/` is the only class that
calls `FileStorageService`. `ProductImageServiceImpl` injects `ProductImageStorageAdapter`
(or the `FileStorageService` interface directly) — never the MinIO SDK.

---

## Published Events

Exchange: `product.exchange`

| Routing Key | Trigger | Consumer Intent |
|---|---|---|
| `product.created` | New product variant activated via `createVariant()` | Inventory: initialize stock record for the variant |
| `product.price.changed` | Price row updated via `setPrice()` | Order: re-price pending order lines; POS: refresh price cache |
| `product.details.changed` | Name, description, brand, or status changed via `updateProductDetails()` | No MVP consumer — published for future extensibility |
| `product.deactivated` | Product or variant set INACTIVE via `deactivateProduct()` / `deactivateVariant()` | Inventory: flag variant unsellable; Order: reject pending orders for that variant |

Note: `product.updated` does NOT exist. Fine-grained routing keys are used instead.

### Event Classes

```
com.walmal.product.domain.event.ProductCreatedEvent extends DomainEvent
  Fields: UUID variantId, UUID productId, String sku, String productName

com.walmal.product.domain.event.ProductPriceChangedEvent extends DomainEvent
  Fields: UUID variantId, UUID productId, BigDecimal oldAmount, BigDecimal newAmount,
          String currency

com.walmal.product.domain.event.ProductDetailsChangedEvent extends DomainEvent
  Fields: UUID productId, String productName, String brand, String status

com.walmal.product.domain.event.ProductDeactivatedEvent extends DomainEvent
  Fields: UUID entityId, String entityType (PRODUCT | VARIANT), String sku,
          UUID productId, String performedBy
```

All events are published via `DomainEventPublisher.publish(event, routingKey)`.
`RabbitTemplate` is never used directly in business logic.

---

## SOLID Compliance

### SRP — One class, one responsibility

| Class | Single Responsibility |
|---|---|
| `ProductManagementServiceImpl` | Create/update/activate/deactivate products and variants; publish change events |
| `ProductPricingServiceImpl` | Retrieve current price for a variant; audit and publish price changes |
| `ProductCatalogServiceImpl` | Answer catalog queries from Order and POS; wrap cache reads |
| `ProductSearchServiceImpl` | Execute search and browse queries; assemble paginated results |
| `ProductImageServiceImpl` | Coordinate image upload/delete between storage and persistence |
| `ProductImageStorageAdapter` | Translate between MinIO keys and `FileStorageService` calls |

### DIP — Infrastructure via interfaces only

| Infrastructure | Interface Used | Never Used Directly |
|---|---|---|
| MinIO | `FileStorageService` | MinIO SDK (`MinioClient`) |
| RabbitMQ | `DomainEventPublisher` | `RabbitTemplate` |
| Redis | `CacheService` | `RedisTemplate` |
| Audit table | `AuditService` | Direct `JdbcTemplate` or `AuditLogRepository` in service |

### ISP — Interfaces split by consumer

| Interface | Consumer | Methods Exposed |
|---|---|---|
| `ProductCatalogService` | Order, POS | `findVariantBySku`, `getProductDetails`, `isVariantActive` |
| `ProductPricingService` | Order, POS | `getCurrentPrice`, `getPriceForVariant` |
| `ProductSearchService` | API Gateway | `searchProducts`, `listByCategory`, `getCategoryTree` |
| `ProductManagementService` | Internal (ProductController) | Full admin CRUD |
| `ProductImageService` | Internal (ProductImageController) | Upload, delete, list |

Order and POS never see search methods. API Gateway never sees price mutation or
variant deactivation.

### OCP — Product status transitions

`ProductStatus` enum starts with `ACTIVE` and `INACTIVE`. Adding `DRAFT` or
`DISCONTINUED` in a future sprint adds a new enum constant and a new guard condition
in the entity's `activate()` / `deactivate()` methods. Existing callers that check
`isVariantActive()` or `ProductStatus.ACTIVE` continue to work without modification.

### LSP — No subtype violations

No inheritance hierarchies in this module outside of `extends BaseEntity` (which is
a `@MappedSuperclass` providing `id`, `createdAt`, `updatedAt`). No subtypes throw
`UnsupportedOperationException`.

---

## Caching Strategy

All caching uses `CacheService` (DIP). `RedisTemplate` is never injected into
application-layer classes.

| Cache Key Pattern | Content | TTL | Eviction Trigger |
|---|---|---|---|
| `product:detail:{productId}` | `ProductDetailDto` | 10 minutes | `updateProductDetails()`, `deactivateProduct()` |
| `product:variant:sku:{sku}` | `VariantSummaryDto` | 5 minutes | `deactivateVariant()`, variant update |
| `product:variant:{variantId}` | `VariantSummaryDto` | 5 minutes | Same as above |
| `product:price:{variantId}` | `PriceDto` | 5 minutes | `setPrice()` |
| `product:category:tree` | `List<CategoryTreeDto>` | 30 minutes | Any category create/update/deactivate |
| `product:search:{hash}` | `Page<ProductSummaryDto>` | 2 minutes | `product.created` event handler |

Cache-aside pattern: read from cache first; on miss, query the DB and populate the cache.

`product:variant:sku:{sku}` is the hot path for Order and POS. It must have low TTL
and be evicted immediately on variant deactivation to prevent order creation against
an inactive SKU.

`product:category:tree` has the longest TTL (30 minutes) because category structure
changes rarely and the full tree is small in memory.

---

## Audit Log Requirements

Per CLAUDE.md: all destructive DB operations write to `audit_log` before execution.
`AuditService.log(AuditEntry)` must be called before the corresponding DB mutation.

| Operation | Method | AuditAction | Table Written | Timing |
|---|---|---|---|---|
| Deactivate product | `deactivateProduct()` | `STATUS_CHANGE` | `product_products` | Before UPDATE status = 'INACTIVE' |
| Activate product | `activateProduct()` | `STATUS_CHANGE` | `product_products` | Before UPDATE status = 'ACTIVE' |
| Deactivate variant | `deactivateVariant()` | `STATUS_CHANGE` | `product_variants` | Before UPDATE status = 'INACTIVE' |
| Activate variant | `activateVariant()` | `STATUS_CHANGE` | `product_variants` | Before UPDATE status = 'ACTIVE' |
| Update price | `setPrice()` | `UPDATE` | `product_prices` | Before UPDATE amount |
| Delete image | `deleteImage()` | `DELETE` | `product_images` | Before MinIO delete and DB delete |

`oldValue` in `AuditEntry` holds a JSON snapshot of the field(s) being changed.
`performedBy` is the username extracted from `AuthenticatedPrincipal` in the controller
and passed down to the service method.

Product creation and variant creation are not destructive operations and do not require
an audit log entry.

---

## Auth Integration

The Product module does not import any class from walmal-auth.

- `TokenValidationService` is already applied globally by `JwtAuthenticationFilter`
  (declared in walmal-auth, wired in walmal-app). Product controllers receive an
  authenticated request automatically.
- Controllers use `@AuthenticationPrincipal AuthenticatedPrincipal principal` to
  obtain `principal.username()` for the `performedBy` field in `AuditEntry`.
- `AuthenticatedPrincipal` is defined in walmal-common — safe to import.
- Role-based access (`ADMIN` only for create/update/deactivate) is declared via
  `@PreAuthorize("hasRole('ADMIN')")` on controller methods.

---

## Maven Dependencies

walmal-product depends on:
- `walmal-common` (DIP interfaces, BaseEntity, AuditService, AuthenticatedPrincipal)
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-amqp` (for RabbitMQ config declarations)
- `springdoc-openapi-starter-webmvc-ui`
- `spring-boot-starter-test` + `testcontainers:postgresql` (test scope)

walmal-infrastructure is NOT a direct Maven dependency. The application assembly
(walmal-app) wires the concrete `FileStorageService`, `CacheService`, and
`DomainEventPublisher` implementations at runtime.

`walmal-product` must be added to the parent `pom.xml` `<modules>` section and to
`walmal-app`'s `<dependencies>` by the module-builder.

---

## Consequences

### Positive

- Module boundary is clean: Inventory and Order reference variant UUIDs; they never
  query product tables.
- `UNIQUE (variant_id)` on `product_prices` makes "one active price" a database
  invariant — no application-level race condition possible.
- ISP-split interfaces mean Order/POS compile-time dependency surface is minimal.
  Adding a new search method to `ProductSearchService` cannot break Order module.
- Redis caching on the SKU lookup path (`product:variant:sku:{sku}`) keeps POS
  barcode scans sub-millisecond after warm-up.
- Adjacency list hierarchy allows arbitrary category depth with simple recursive CTE
  queries — no schema migration required to add a new category level.

### Negative / Risks

- No price history: this ADR deliberately omits a price history table for MVP. A
  future `product_price_history` table can be added by the database-designer without
  changing the `ProductPricingService` interface.
- Presigned URL expiry: `cdnUrl` stored in `product_images` may expire if MinIO
  presigned URLs have short TTLs. Mitigation: store the key, not the URL, and generate
  fresh URLs at read time via `FileStorageService.getPresignedUrl()`. The `cdnUrl`
  column can be treated as a cache field updated periodically, or a CDN prefix can
  be prepended to `storageKey` in production without changing the entity.
- Full-text search via `ProductSearchService.searchProducts()` uses PostgreSQL
  `ILIKE` or `to_tsvector` in the MVP. A dedicated search engine (e.g. Elasticsearch)
  is not in scope. Performance will be adequate for small-to-medium catalogs.

---

## Definition of Done Checklist

- [ ] `ProductCatalogService` interface defined in `application/`
- [ ] `ProductPricingService` interface defined in `application/`
- [ ] `ProductSearchService` interface defined in `application/`
- [ ] `ProductManagementService` interface defined in `application/`
- [ ] `ProductImageService` interface defined in `application/`
- [ ] All five implementations complete in `application/impl/`
- [ ] All five JPA entities mapped to `product_*` tables
- [ ] `V3__product_create_tables.sql` Flyway migration applied
- [ ] `ProductController`, `CategoryController`, `ProductImageController` complete with OpenAPI annotations
- [ ] `ProductRabbitMQConfig` declares `product.exchange` and four routing keys
- [ ] All four domain events published via `DomainEventPublisher` only
- [ ] `AuditService.log()` called before every destructive operation (6 operations listed above)
- [ ] `CacheService` used for all cache reads/writes — `RedisTemplate` not injected in application layer
- [ ] `FileStorageService` used for all MinIO operations — MinIO SDK not in application layer
- [ ] No cross-module Repository bean imports
- [ ] Integration tests pass (`@SpringBootTest` + Testcontainers PostgreSQL)
- [ ] `@WebMvcTest` tests for all three controllers
- [ ] Docker Compose health check passes with product endpoints reachable
- [ ] `walmal-product` added to parent `pom.xml` `<modules>` and `walmal-app` `<dependencies>`
