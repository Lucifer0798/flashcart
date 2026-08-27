package com.flashcart.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.flashcart.catalog.api.dto.CategoryRequest;
import com.flashcart.catalog.api.dto.CategoryResponse;
import com.flashcart.catalog.api.dto.CreateProductRequest;
import com.flashcart.catalog.api.dto.FlashSaleItemRequest;
import com.flashcart.catalog.api.dto.FlashSaleItemResponse;
import com.flashcart.catalog.api.dto.FlashSaleRequest;
import com.flashcart.catalog.api.dto.FlashSaleResponse;
import com.flashcart.catalog.api.dto.ProductResponse;
import com.flashcart.catalog.api.dto.UpdateProductRequest;
import com.flashcart.catalog.domain.FlashSalePhase;
import com.flashcart.catalog.domain.FlashSaleStatus;
import com.flashcart.catalog.domain.ProductStatus;
import com.flashcart.common.web.CorrelationId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog against a real PostgreSQL, driven over real HTTP.
 *
 * <p>Not H2. The schema leans on Postgres semantics — {@code timestamptz}, {@code numeric(12,2)},
 * partial indexes, check constraints — and an in-memory dialect that quietly accepts different
 * semantics would give a green build for a schema that does not exist anywhere real.
 *
 * <p>Named {@code *IT} so it runs under {@code mvn verify} (failsafe) and is skipped by the fast
 * {@code mvn test} loop, which needs no Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class CatalogIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private TestRestTemplate rest;

	// --- helpers ---------------------------------------------------------------------------------

	private CategoryResponse createCategory(String name) {
		ResponseEntity<CategoryResponse> response = rest.postForEntity("/api/v1/categories",
				new CategoryRequest(name, null, "created by CatalogIT"), CategoryResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody();
	}

	private ResponseEntity<ProductResponse> createProduct(UUID categoryId, String sku, String name, String price) {
		return rest.postForEntity("/api/v1/products",
				new CreateProductRequest(sku, name, null, "created by CatalogIT", categoryId,
						new BigDecimal(price), "USD", ProductStatus.ACTIVE, null),
				ProductResponse.class);
	}

	private static String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	// --- products --------------------------------------------------------------------------------

	@Test
	@DisplayName("a product round-trips through create, fetch by id and fetch by SKU")
	void productRoundTrip() {
		CategoryResponse category = createCategory(unique("Audio"));
		String sku = unique("AUD").toUpperCase();

		ProductResponse created = createProduct(category.id(), sku, "Aurora Headphones", "299.00").getBody();

		assertThat(created).isNotNull();
		assertThat(created.sku()).isEqualTo(sku);
		assertThat(created.slug()).isEqualTo("aurora-headphones");
		assertThat(created.category().id()).isEqualTo(category.id());
		// With no live sale, the effective price is simply the list price.
		assertThat(created.effectivePrice()).isEqualByComparingTo("299.00");
		assertThat(created.onFlashSale()).isFalse();
		assertThat(created.offer()).isNull();
		assertThat(created.version()).isZero();

		ProductResponse byId = rest.getForObject("/api/v1/products/" + created.id(), ProductResponse.class);
		assertThat(byId.id()).isEqualTo(created.id());

		// The lookup other services use, since SKU is what orders and inventory hold.
		ProductResponse bySku = rest.getForObject("/api/v1/products/sku/" + sku.toLowerCase(), ProductResponse.class);
		assertThat(bySku.id()).isEqualTo(created.id());
	}

	@Test
	@DisplayName("a duplicate SKU is a 409, not a 500 from the unique constraint")
	void duplicateSkuIsAConflict() {
		CategoryResponse category = createCategory(unique("Audio"));
		String sku = unique("DUP").toUpperCase();
		assertThat(createProduct(category.id(), sku, unique("First"), "10.00").getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		ResponseEntity<Map> conflict = rest.postForEntity("/api/v1/products",
				new CreateProductRequest(sku, unique("Second"), null, null, category.id(),
						new BigDecimal("20.00"), "USD", ProductStatus.ACTIVE, null),
				Map.class);

		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(conflict.getBody()).containsEntry("code", "SKU_TAKEN");
	}

	@Test
	@DisplayName("a rejected body comes back as a 400 naming the offending fields")
	void validationFailureNamesTheFields() {
		ResponseEntity<Map> response = rest.postForEntity("/api/v1/products",
				new CreateProductRequest("", "", null, null, null, new BigDecimal("-5.00"), "usd", null, null),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
		@SuppressWarnings("unchecked")
		List<Map<String, String>> fieldErrors = (List<Map<String, String>>) response.getBody().get("fieldErrors");
		assertThat(fieldErrors).extracting(error -> error.get("field"))
				.contains("sku", "name", "categoryId", "basePrice", "currency");
	}

	@Test
	@DisplayName("an update against a stale version is refused instead of silently overwriting")
	void staleUpdateIsRefused() {
		CategoryResponse category = createCategory(unique("Audio"));
		ProductResponse product = createProduct(category.id(), unique("LOCK").toUpperCase(),
				unique("Lockable"), "50.00").getBody();

		UpdateProductRequest firstEdit = new UpdateProductRequest("Renamed once", null, null, category.id(),
				new BigDecimal("55.00"), "USD", ProductStatus.ACTIVE, null, product.version());
		ResponseEntity<ProductResponse> ok = rest.exchange("/api/v1/products/" + product.id(), HttpMethod.PUT,
				new HttpEntity<>(firstEdit), ProductResponse.class);
		assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ok.getBody().version()).isEqualTo(product.version() + 1);

		// A second editor still holding the original version must be told, not silently win.
		UpdateProductRequest staleEdit = new UpdateProductRequest("Renamed twice", null, null, category.id(),
				new BigDecimal("60.00"), "USD", ProductStatus.ACTIVE, null, product.version());
		ResponseEntity<Map> stale = rest.exchange("/api/v1/products/" + product.id(), HttpMethod.PUT,
				new HttpEntity<>(staleEdit), Map.class);

		assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(stale.getBody()).containsEntry("code", "STALE_PRODUCT");
	}

	@Test
	@DisplayName("deleting a product archives it, because past orders still point at it")
	void deleteArchivesRatherThanRemoves() {
		CategoryResponse category = createCategory(unique("Audio"));
		ProductResponse product = createProduct(category.id(), unique("ARC").toUpperCase(),
				unique("Archivable"), "12.00").getBody();

		rest.delete("/api/v1/products/" + product.id());

		ProductResponse after = rest.getForObject("/api/v1/products/" + product.id(), ProductResponse.class);
		assertThat(after.status()).isEqualTo(ProductStatus.ARCHIVED);
	}

	@Test
	@DisplayName("an unknown product is a 404 carrying the shared error envelope")
	void unknownProductIsNotFound() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/products/" + UUID.randomUUID(), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
		assertThat(response.getBody()).containsKey("timestamp");
		assertThat(response.getBody()).containsKey("correlationId");
	}

	// --- flash sales -----------------------------------------------------------------------------

	@Test
	@DisplayName("scheduling a sale moves the product's effective price without touching the product")
	void liveSaleChangesTheEffectivePrice() {
		CategoryResponse category = createCategory(unique("Audio"));
		ProductResponse product = createProduct(category.id(), unique("SALE").toUpperCase(),
				unique("Discountable"), "299.00").getBody();

		// Built as a DRAFT whose window is already open: items can still be added, and it goes live
		// the moment it is scheduled. That is the real admin flow, not a test shortcut.
		Instant now = Instant.now();
		FlashSaleResponse sale = rest.postForObject("/api/v1/flash-sales",
				new FlashSaleRequest(unique("Launch Week"), null, now.minus(1, ChronoUnit.HOURS),
						now.plus(6, ChronoUnit.HOURS), FlashSaleStatus.DRAFT),
				FlashSaleResponse.class);
		assertThat(sale.phase()).isEqualTo(FlashSalePhase.DRAFT);

		FlashSaleItemResponse item = rest.postForObject("/api/v1/flash-sales/" + sale.id() + "/items",
				new FlashSaleItemRequest(product.id(), new BigDecimal("179.00"), 500, 2),
				FlashSaleItemResponse.class);
		assertThat(item.discountPercent()).isEqualTo(40);

		// Still DRAFT, so the storefront must still be quoting list price.
		assertThat(rest.getForObject("/api/v1/products/" + product.id(), ProductResponse.class).effectivePrice())
				.isEqualByComparingTo("299.00");

		FlashSaleResponse scheduled = rest.postForObject("/api/v1/flash-sales/" + sale.id() + "/schedule", null,
				FlashSaleResponse.class);
		assertThat(scheduled.phase()).isEqualTo(FlashSalePhase.ACTIVE);

		ProductResponse onSale = rest.getForObject("/api/v1/products/" + product.id(), ProductResponse.class);
		assertThat(onSale.onFlashSale()).isTrue();
		assertThat(onSale.effectivePrice()).isEqualByComparingTo("179.00");
		assertThat(onSale.basePrice()).isEqualByComparingTo("299.00");
		assertThat(onSale.offer().perCustomerLimit()).isEqualTo(2);
		assertThat(onSale.offer().flashSaleId()).isEqualTo(sale.id());

		// The listing prices the whole page the same way, in one batched query.
		ResponseEntity<Map> listing = rest.getForEntity(
				"/api/v1/products?categoryId=" + category.id() + "&status=ACTIVE", Map.class);
		assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> content = (List<Map<String, Object>>) listing.getBody().get("content");
		assertThat(content).anySatisfy(row -> {
			assertThat(row).containsEntry("sku", onSale.sku());
			assertThat(row).containsEntry("onFlashSale", true);
			assertThat(new BigDecimal(row.get("effectivePrice").toString())).isEqualByComparingTo("179.00");
		});

		// Cancelling takes the price back to list on the very next read, with nothing swept.
		rest.postForObject("/api/v1/flash-sales/" + sale.id() + "/cancel", null, FlashSaleResponse.class);
		assertThat(rest.getForObject("/api/v1/products/" + product.id(), ProductResponse.class).effectivePrice())
				.isEqualByComparingTo("299.00");
	}

	@Test
	@DisplayName("a sale price at or above list is refused, so 'sale' always means cheaper")
	void salePriceMustBeADiscount() {
		CategoryResponse category = createCategory(unique("Audio"));
		ProductResponse product = createProduct(category.id(), unique("NODISC").toUpperCase(),
				unique("Full price"), "100.00").getBody();
		Instant now = Instant.now();
		FlashSaleResponse sale = rest.postForObject("/api/v1/flash-sales",
				new FlashSaleRequest(unique("No discount"), null, now, now.plus(1, ChronoUnit.HOURS),
						FlashSaleStatus.DRAFT),
				FlashSaleResponse.class);

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/flash-sales/" + sale.id() + "/items",
				new FlashSaleItemRequest(product.id(), new BigDecimal("100.00"), 10, 1), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("code", "SALE_PRICE_NOT_A_DISCOUNT");
	}

	@Test
	@DisplayName("a live sale's line-up is frozen, so prices cannot move under shoppers mid-session")
	void liveSaleRejectsNewItems() {
		CategoryResponse category = createCategory(unique("Audio"));
		ProductResponse first = createProduct(category.id(), unique("FRZ1").toUpperCase(),
				unique("First"), "100.00").getBody();
		ProductResponse second = createProduct(category.id(), unique("FRZ2").toUpperCase(),
				unique("Second"), "100.00").getBody();

		Instant now = Instant.now();
		FlashSaleResponse sale = rest.postForObject("/api/v1/flash-sales",
				new FlashSaleRequest(unique("Frozen"), null, now.minus(1, ChronoUnit.MINUTES),
						now.plus(1, ChronoUnit.HOURS), FlashSaleStatus.DRAFT),
				FlashSaleResponse.class);
		rest.postForObject("/api/v1/flash-sales/" + sale.id() + "/items",
				new FlashSaleItemRequest(first.id(), new BigDecimal("50.00"), 10, 1), FlashSaleItemResponse.class);
		rest.postForObject("/api/v1/flash-sales/" + sale.id() + "/schedule", null, FlashSaleResponse.class);

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/flash-sales/" + sale.id() + "/items",
				new FlashSaleItemRequest(second.id(), new BigDecimal("50.00"), 10, 1), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "FLASH_SALE_LIVE");
	}

	@Test
	@DisplayName("an empty sale cannot be scheduled")
	void emptySaleCannotBeScheduled() {
		Instant now = Instant.now();
		FlashSaleResponse sale = rest.postForObject("/api/v1/flash-sales",
				new FlashSaleRequest(unique("Empty"), null, now, now.plus(1, ChronoUnit.HOURS), null),
				FlashSaleResponse.class);
		assertThat(sale.status()).isEqualTo(FlashSaleStatus.DRAFT);

		ResponseEntity<Map> response = rest.postForEntity("/api/v1/flash-sales/" + sale.id() + "/schedule", null,
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "FLASH_SALE_EMPTY");
	}

	@Test
	@DisplayName("a sale ending before it starts is refused by the API, not by the check constraint")
	void invertedWindowIsABadRequest() {
		Instant now = Instant.now();
		ResponseEntity<Map> response = rest.postForEntity("/api/v1/flash-sales",
				new FlashSaleRequest(unique("Backwards"), null, now.plus(1, ChronoUnit.HOURS), now, null),
				Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// --- categories ------------------------------------------------------------------------------

	@Test
	@DisplayName("a category holding products refuses to be deleted")
	void nonEmptyCategoryCannotBeDeleted() {
		CategoryResponse category = createCategory(unique("Occupied"));
		createProduct(category.id(), unique("OCC").toUpperCase(), unique("Occupant"), "10.00");

		ResponseEntity<Map> response = rest.exchange("/api/v1/categories/" + category.id(), HttpMethod.DELETE,
				null, Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("code", "CATEGORY_NOT_EMPTY");
	}

	@Test
	@DisplayName("a category is reachable by slug as well as by id")
	void categoryIsAddressableBySlug() {
		CategoryResponse category = createCategory(unique("Smart Home"));

		CategoryResponse bySlug = rest.getForObject("/api/v1/categories/" + category.slug(), CategoryResponse.class);

		assertThat(bySlug.id()).isEqualTo(category.id());
	}

	// --- cross-cutting ---------------------------------------------------------------------------

	@Test
	@DisplayName("the caller's correlation id is echoed back rather than replaced")
	void correlationIdIsEchoed() {
		String supplied = UUID.randomUUID().toString();
		HttpHeaders headers = new HttpHeaders();
		headers.set(CorrelationId.HEADER, supplied);

		ResponseEntity<Void> response = rest.exchange("/api/v1/categories", HttpMethod.GET,
				new HttpEntity<>(headers), Void.class);

		assertThat(response.getHeaders().getFirst(CorrelationId.HEADER)).isEqualTo(supplied);
	}

	@Test
	@DisplayName("a request with no correlation id still gets one")
	void correlationIdIsMintedWhenAbsent() {
		ResponseEntity<Void> response = rest.exchange("/api/v1/categories", HttpMethod.GET, null, Void.class);

		assertThat(response.getHeaders().getFirst(CorrelationId.HEADER)).isNotBlank();
	}

	@Test
	@DisplayName("the page size is capped, so one request cannot ask for the whole catalog")
	void pageSizeIsCapped() {
		ResponseEntity<Map> response = rest.getForEntity("/api/v1/products?size=100000", Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("the service reports itself healthy once Flyway has run")
	void actuatorHealthIsUp() {
		ResponseEntity<Map<String, Object>> response = rest.exchange("/actuator/health", HttpMethod.GET, null,
				new ParameterizedTypeReference<Map<String, Object>>() {
				});

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("status", "UP");
	}
}
