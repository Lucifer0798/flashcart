package com.flashcart.catalog.service;

import java.text.Normalizer;
import java.util.Locale;

import org.springframework.util.StringUtils;

/** Turns a display name into a URL-safe slug. */
public final class Slugs {

	private Slugs() {
	}

	/**
	 * {@code "Noise-Cancelling Headphones (2024)"} becomes {@code "noise-cancelling-headphones-2024"}.
	 *
	 * <p>Accents are decomposed and stripped rather than rejected, so "Café Crème" yields a usable
	 * "cafe-creme" instead of collapsing to a row of hyphens.
	 */
	public static String slugify(String input) {
		if (!StringUtils.hasText(input)) {
			return "";
		}
		String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT);
		return normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
	}

	/** Uses {@code provided} when the caller supplied one, otherwise derives it from {@code name}. */
	public static String orDerive(String provided, String name) {
		return StringUtils.hasText(provided) ? provided.toLowerCase(Locale.ROOT) : slugify(name);
	}
}
