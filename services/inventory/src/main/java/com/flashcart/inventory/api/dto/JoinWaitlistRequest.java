package com.flashcart.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** No customer id: the queue is joined by whoever the token says, never by whoever the body says. */
public record JoinWaitlistRequest(@NotBlank @Size(max = 64) String sku) {
}
