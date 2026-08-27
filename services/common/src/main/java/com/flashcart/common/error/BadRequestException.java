package com.flashcart.common.error;

/** The request itself is wrong in a way bean validation could not express. Maps to 400. */
public class BadRequestException extends FlashCartException {

	public BadRequestException(String message) {
		super("BAD_REQUEST", message);
	}

	public BadRequestException(String code, String message) {
		super(code, message);
	}
}
