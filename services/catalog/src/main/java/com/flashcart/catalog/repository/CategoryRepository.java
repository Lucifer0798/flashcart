package com.flashcart.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.flashcart.catalog.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

	Optional<Category> findBySlug(String slug);

	boolean existsBySlug(String slug);

	List<Category> findAllByOrderByNameAsc();
}
