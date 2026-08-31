package com.flashcart.order.api.dto;

import jakarta.validation.constraints.Size;

/** @param reason free text recorded on the order and in its history, for support to read */
public record CancelOrderRequest(@Size(max = 300) String reason) {
}
