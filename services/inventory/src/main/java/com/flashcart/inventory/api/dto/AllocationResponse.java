package com.flashcart.inventory.api.dto;

import java.util.UUID;

import com.flashcart.inventory.domain.SaleAllocation;

/**
 * @param remainingUnits what this sale can still sell — allocated minus held minus sold
 */
public record AllocationResponse(
		UUID id,
		UUID flashSaleId,
		String sku,
		int allocatedUnits,
		int reservedUnits,
		int committedUnits,
		int remainingUnits,
		int perCustomerLimit) {

	public static AllocationResponse from(SaleAllocation allocation) {
		return new AllocationResponse(allocation.getId(), allocation.getFlashSaleId(), allocation.getSku(),
				allocation.getAllocatedUnits(), allocation.getReservedUnits(), allocation.getCommittedUnits(),
				allocation.remainingUnits(), allocation.getPerCustomerLimit());
	}
}
