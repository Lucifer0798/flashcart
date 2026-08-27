package com.flashcart.catalog.service;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogMapperTest {

	@Test
	void computesWholePercentDiscount() {
		assertThat(CatalogMapper.discountPercent(new BigDecimal("299.00"), new BigDecimal("179.00"))).isEqualTo(40);
	}

	@Test
	@DisplayName("rounds to the nearest whole percent so every client shows the same badge")
	void roundsHalfUp() {
		// 129.00 -> 89.00 is 31.007...%, which must render as 31 everywhere, not 31 in one client
		// and 32 in another.
		assertThat(CatalogMapper.discountPercent(new BigDecimal("129.00"), new BigDecimal("89.00"))).isEqualTo(31);
	}

	@Test
	@DisplayName("a non-discount reports zero rather than a negative percent")
	void neverReportsANegativeDiscount() {
		assertThat(CatalogMapper.discountPercent(new BigDecimal("50.00"), new BigDecimal("60.00"))).isZero();
		assertThat(CatalogMapper.discountPercent(new BigDecimal("50.00"), new BigDecimal("50.00"))).isZero();
	}

	@Test
	@DisplayName("a free product does not divide by zero")
	void handlesZeroBasePrice() {
		assertThat(CatalogMapper.discountPercent(BigDecimal.ZERO, BigDecimal.ZERO)).isZero();
	}
}
