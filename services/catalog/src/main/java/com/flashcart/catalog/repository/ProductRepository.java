package com.flashcart.catalog.repository;

import java.util.Optional;
import java.util.UUID;

import com.flashcart.catalog.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

	/**
	 * Overridden purely to attach the entity graph. Product#category is LAZY, and the listing
	 * renders the category name for every row — without this, a page of 50 products costs 51 queries.
	 */
	@Override
	@EntityGraph(attributePaths = "category")
	Page<Product> findAll(Specification<Product> spec, Pageable pageable);

	@EntityGraph(attributePaths = "category")
	Optional<Product> findBySku(String sku);

	@EntityGraph(attributePaths = "category")
	Optional<Product> findBySlug(String slug);

	@Override
	@EntityGraph(attributePaths = "category")
	Optional<Product> findById(UUID id);

	boolean existsBySku(String sku);

	boolean existsBySlug(String slug);

	boolean existsByCategoryId(UUID categoryId);
}
