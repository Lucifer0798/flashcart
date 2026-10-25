package com.flashcart.inventory.service;

import java.time.Clock;
import java.time.Instant;

import com.flashcart.inventory.domain.ReservationStatus;
import com.flashcart.inventory.domain.WaitlistEntry;
import com.flashcart.inventory.domain.WaitlistStatus;
import com.flashcart.inventory.repository.ReservationRepository;
import com.flashcart.inventory.repository.WaitlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What closing an account means for inventory: it stops queueing for stock. See ADR 0053.
 *
 * <p>A closed account left in a waitlist would be told when stock came back and have a unit held in its
 * name for ten minutes, nobody to check out, and every real shopper behind it waiting for nothing. So its
 * waiting places are cancelled, and a unit already held for it is released. That release goes through the
 * ledger like any other, which offers the unit to the next in line (ADR 0045).
 *
 * <p>Order-owned reservations are left to their orders, and the per-customer sale counts are kept: they
 * are what was bought, not who bought it.
 */
@Service
public class AccountClosure {

	private static final Logger log = LoggerFactory.getLogger(AccountClosure.class);

	private final WaitlistRepository waitlist;
	private final ReservationService reservations;
	private final ReservationRepository holds;
	private final ClosedAccounts closedAccounts;
	private final Clock clock;

	public AccountClosure(WaitlistRepository waitlist, ReservationService reservations, ReservationRepository holds,
			ClosedAccounts closedAccounts, Clock clock) {
		this.waitlist = waitlist;
		this.reservations = reservations;
		this.holds = holds;
		this.closedAccounts = closedAccounts;
		this.clock = clock;
	}

	/**
	 * Idempotent: a second call finds nothing waiting and no live hold.
	 *
	 * @param closedAt when the account closed, kept beside when this service heard
	 */
	@Transactional
	public void forget(String customerId, Instant closedAt) {
		// Recorded first and under the lock a join takes, so no join can slip a place in after the
		// cancelling below has looked (ADR 0054).
		closedAccounts.lock(customerId);
		closedAccounts.record(customerId, closedAt);
		int cancelled = 0;
		int released = 0;
		// Waiting places first, so a unit released below cannot be offered straight back to another of this
		// account's own places.
		for (WaitlistEntry entry : waitlist.findByCustomerIdOrderByCreatedAtDesc(customerId)) {
			if (entry.getStatus() == WaitlistStatus.WAITING) {
				entry.cancel(clock.instant());
				cancelled++;
			}
		}
		waitlist.flush();
		for (WaitlistEntry entry : waitlist.findByCustomerIdOrderByCreatedAtDesc(customerId)) {
			String key = WaitlistService.holdKey(entry.getId());
			boolean live = entry.getStatus() == WaitlistStatus.NOTIFIED && holds.findByReservationKey(key)
					.filter(hold -> hold.getStatus() == ReservationStatus.HELD).isPresent();
			if (live) {
				reservations.release(key, "account closed");
				released++;
			}
		}
		if (cancelled > 0 || released > 0) {
			log.info("Account {} closed: cancelled {} waitlist place(s), released {} held unit(s)", customerId,
					cancelled, released);
		}
	}
}
