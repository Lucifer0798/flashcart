package com.flashcart.common.security;

import com.flashcart.common.error.OperatorRequiredException;

/**
 * Whether this caller may read a row belonging to a customer, and the record of it when they may
 * only because of their role.
 *
 * <p>Payment and shipping had the same decision written out twice, differing in a noun and an action
 * constant. It is a security decision with an audit obligation attached, which is the worst kind to
 * keep two copies of: the copies drift, and the one that drifts looks fine.
 *
 * <h2>Why this is not on {@link CallerIdentity}</h2>
 *
 * <p>Because the order service must not have it. An operator can read any customer's payment and
 * parcel and <strong>cannot</strong> read their order — ADR 0025 recorded that asymmetry and said
 * changing it needs its own argument. Leaving {@code mayRead} on the type every service uses would
 * make granting that power a one-line accident.
 *
 * <p>This type needs an {@link OperatorAccessLog}, which needs an {@code operator_access_log} table,
 * which only payment and shipping have. So the constraint is structural rather than a comment asking
 * people to be careful: a service cannot gain audited operator reads without first deciding to store
 * the audit.
 */
public class CustomerDataAccess {

	private final CallerIdentity caller;
	private final OperatorAccessLog accessLog;

	public CustomerDataAccess(CallerIdentity caller, OperatorAccessLog accessLog) {
		this.caller = caller;
		this.accessLog = accessLog;
	}

	/** The subject of a verified token. Delegates, so a handler needs only one collaborator. */
	public String require(String authorization) {
		return caller.require(authorization);
	}

	/**
	 * Whether the caller may read a row owned by {@code ownerId}, recording the access when it is
	 * granted on the strength of the role rather than ownership.
	 *
	 * <p>Returns a boolean rather than throwing, because <em>what to say when the answer is no</em> is
	 * the handler's business: payment and shipping both answer 404 naming their own resource, and that
	 * choice is ADR 0021's — a 403 would confirm the identifier exists and hand an enumerator an
	 * oracle over order numbers and tracking numbers.
	 *
	 * <p>The record is written <strong>before</strong> this returns true, so a failure to record is a
	 * failure to read. See ADR 0025 for why that is the right way round here.
	 */
	public boolean mayRead(String authorization, String ownerId, String action, String resourceId) {
		String subject = caller.require(authorization);
		if (ownerId.equals(subject)) {
			return true;
		}
		if (!caller.isOperator(authorization)) {
			return false;
		}
		accessLog.record(subject, action, resourceId, ownerId);
		return true;
	}

	/**
	 * Whose rows a listing should return.
	 *
	 * <p>No {@code customerId} means your own, which is the only way a customer can ask and is
	 * therefore not a way anybody can mistype into somebody else's (ADR 0023). Naming somebody else
	 * requires the operator role, and is refused outright rather than quietly narrowed to the caller's
	 * own rows — answering a request for another customer's data with your own reads as success, and
	 * whoever wrote the call finds out otherwise much later.
	 *
	 * @param resource plural, for the refusal message: {@code "payments"}, {@code "shipments"}
	 */
	public String subjectOf(String authorization, String requestedCustomerId, String listAction,
			String resource) {
		String subject = caller.require(authorization);
		if (requestedCustomerId == null || requestedCustomerId.equals(subject)) {
			return subject;
		}
		if (!caller.isOperator(authorization)) {
			throw new OperatorRequiredException(
					"Only an operator may read another customer's " + resource);
		}
		accessLog.record(subject, listAction, null, requestedCustomerId);
		return requestedCustomerId;
	}
}
