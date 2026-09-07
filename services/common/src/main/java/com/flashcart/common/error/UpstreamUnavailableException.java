package com.flashcart.common.error;

/**
 * A service this one depends on did not answer.
 *
 * <p>Distinct from {@link ConflictException} and the rest because of what
 * <a href="../../../../../../../../docs/adr/0010-refusal-and-silence-are-different-failures.md">ADR
 * 0010</a> insists on: a downstream <em>refusing</em> and a downstream <em>saying nothing</em> are
 * different failures and must not be flattened into one another. A refusal is an answer. A silence is
 * the absence of one, and the caller cannot know whether the work happened.
 *
 * <p>This maps to <strong>503</strong>, not 500. The difference is not cosmetic: 500 tells a client
 * that this service is broken, which invites it to give up and someone to be woken; 503 says the
 * request is fine and the platform is temporarily unable to serve it, which is both true and
 * actionable.
 *
 * <p>Phase 11's failure injection is what surfaced this. Killing catalog mid-checkout produced a
 * perfectly correct refusal — no half-built order, no stranded stock — reported to the caller as an
 * internal server error, indistinguishable from a genuine bug.
 */
public class UpstreamUnavailableException extends FlashCartException {

	public UpstreamUnavailableException(String code, String message, Throwable cause) {
		super(code, message, cause);
	}
}
