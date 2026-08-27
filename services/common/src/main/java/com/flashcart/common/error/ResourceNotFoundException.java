package com.flashcart.common.error;

/** The addressed resource does not exist. Maps to 404. */
public class ResourceNotFoundException extends FlashCartException {

	public ResourceNotFoundException(String message) {
		super("NOT_FOUND", message);
	}

	/** Convenience for the common "{@code Product 42 not found}" shape. */
	public static ResourceNotFoundException of(String resource, Object id) {
		return new ResourceNotFoundException("%s %s not found".formatted(resource, id));
	}
}
