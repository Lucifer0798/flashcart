package com.flashcart.catalog.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

	@Test
	void lowercasesAndHyphenates() {
		assertThat(Slugs.slugify("Aurora Over-Ear Headphones")).isEqualTo("aurora-over-ear-headphones");
	}

	@Test
	@DisplayName("punctuation collapses instead of producing runs of hyphens")
	void collapsesPunctuation() {
		assertThat(Slugs.slugify("Noise-Cancelling Headphones (2024)!!"))
				.isEqualTo("noise-cancelling-headphones-2024");
	}

	@Test
	@DisplayName("accents are stripped rather than rejected")
	void stripsAccents() {
		// Rejecting them would leave "Café Crème" with no usable slug at all.
		assertThat(Slugs.slugify("Café Crème")).isEqualTo("cafe-creme");
	}

	@Test
	void trimsLeadingAndTrailingSeparators() {
		assertThat(Slugs.slugify("  --Hello--  ")).isEqualTo("hello");
	}

	@Test
	@DisplayName("a name with nothing sluggable yields an empty string, not a row of hyphens")
	void unsluggableNameYieldsEmpty() {
		// The services treat empty as a 400 rather than persisting a meaningless slug.
		assertThat(Slugs.slugify("!!!")).isEmpty();
	}

	@Test
	void prefersAnExplicitSlugButNormalisesIt() {
		assertThat(Slugs.orDerive("Custom-Slug", "Ignored Name")).isEqualTo("custom-slug");
		assertThat(Slugs.orDerive("  ", "Fallback Name")).isEqualTo("fallback-name");
	}
}
