package com.flashcart.inventory.api.dto;

import jakarta.validation.constraints.Size;

/** @param reason free text for the ledger, e.g. "payment declined" or "customer cancelled" */
public record ReleaseRequest(@Size(max = 200) String reason) {
}
