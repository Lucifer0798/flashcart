package com.flashcart.inventory.domain;

/** What caused a row in the movement ledger. */
public enum MovementType {

	/** Stock arrived at the warehouse. Increases on-hand. */
	RECEIVED,

	/** A manual correction — damage, shrinkage, a recount. Signed, and always carries a reason. */
	ADJUSTED,

	/** A reservation took units out of circulation. Increases reserved, leaves on-hand alone. */
	RESERVED,

	/** A reservation gave units back before expiry. Decreases reserved. */
	RELEASED,

	/** A reservation timed out. Decreases reserved; distinct from RELEASED so the ledger shows who
	 *  gave up versus who ran out of time. */
	EXPIRED,

	/** A sale completed. Decreases both on-hand and reserved: the units have physically left. */
	COMMITTED,

	/** A completed sale was undone before the goods left. Increases on-hand; the exact inverse of
	 *  COMMITTED except that reserved is untouched, because nothing is held any more. */
	RETURNED
}
