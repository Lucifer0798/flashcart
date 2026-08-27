package com.flashcart.catalog.repository;

import java.util.UUID;

import com.flashcart.catalog.domain.Product;
import com.flashcart.catalog.domain.ProductStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Composable filters for the product listing.
 *
 * <p>Specifications rather than one JPQL query full of {@code (:param is null or ...)} branches:
 * those force the database to plan around parameters it cannot see through, and on PostgreSQL an
 * untyped null enum parameter is a runtime error waiting for the first caller who omits the filter.
 *
 * <p>An absent filter returns {@link Specification#unrestricted()}, never {@code null}. Spring Data
 * JPA 4's {@code Specification.and} and {@code allOf} reject a null element outright, so the older
 * "null means no filter" convention turns an unfiltered listing into a 500 rather than a full list.
 */
public final class ProductSpecifications {

	private ProductSpecifications() {
	}

	public static Specification<Product> hasStatus(ProductStatus status) {
		return status == null
				? Specification.unrestricted()
				: (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	public static Specification<Product> inCategory(UUID categoryId) {
		return categoryId == null
				? Specification.unrestricted()
				: (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
	}

	public static Specification<Product> inCategorySlug(String categorySlug) {
		return !StringUtils.hasText(categorySlug)
				? Specification.unrestricted()
				: (root, query, cb) -> cb.equal(root.get("category").get("slug"), categorySlug.toLowerCase());
	}

	/**
	 * Substring match over name and SKU.
	 *
	 * <p>A leading wildcard cannot use a B-tree index, so this is a sequential scan and is honestly
	 * only adequate at catalog sizes in the tens of thousands. A real search backend is a later
	 * concern; pretending this one scales would be the mistake.
	 */
	public static Specification<Product> matching(String q) {
		if (!StringUtils.hasText(q)) {
			return Specification.unrestricted();
		}
		String pattern = "%" + q.trim().toLowerCase() + "%";
		return (root, query, cb) -> cb.or(
				cb.like(cb.lower(root.get("name")), pattern),
				cb.like(cb.lower(root.get("sku")), pattern));
	}
}
