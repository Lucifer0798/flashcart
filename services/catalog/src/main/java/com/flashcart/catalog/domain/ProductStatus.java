package com.flashcart.catalog.domain;

/** Whether a product is visible to shoppers. */
public enum ProductStatus {

	/** Being set up. Never returned by the public listing. */
	DRAFT,

	/** On sale. */
	ACTIVE,

	/**
	 * Withdrawn. Kept rather than deleted, because orders placed months ago still reference it and a
	 * dangling product id would break every historical order view.
	 */
	ARCHIVED
}
