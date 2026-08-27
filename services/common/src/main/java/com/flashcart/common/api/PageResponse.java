package com.flashcart.common.api;

import java.util.List;

/**
 * A pagination envelope owned by us rather than Spring Data's {@code Page}.
 *
 * <p>{@code Page}'s JSON shape is an implementation detail that has changed between Spring versions
 * and leaks sort/pageable internals into the public contract. This is the shape clients see.
 *
 * @param content       the page of items
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements total matching items across all pages
 * @param totalPages    total pages at this size
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
		int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
		return new PageResponse<>(content, page, size, totalElements, totalPages);
	}
}
