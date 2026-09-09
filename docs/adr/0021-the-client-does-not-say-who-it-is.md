# 0021 — The client does not get to say who it is

**Status:** Accepted · **Date:** 2026-09-09 · **Phase:** post-roadmap

## Context

The user service was marked "Phase 4" in the README and never built. Every phase after it had
something more load-bearing to prove, and `customerId` stayed what Phase 4 made it: an opaque string
the client supplied in the request body.

That is not merely an unfinished feature. It means **a client asserts its own identity**. Anyone could
place an order as anyone. `GET /api/v1/orders?customerId=someone-else` returned that person's order
history. `POST /api/v1/orders/{orderNumber}/cancel` cancelled a stranger's order and released their
stock — during a flash sale, to somebody else's benefit.

This is the same class of mistake as taking a price from the request body, which
[ADR 0011](0011-order-owns-no-prices.md) rejected in Phase 4 for exactly the reason that applies here:
**the caller does not get to declare facts the platform is responsible for.** Prices were caught. The
customer was not.

## Decision

**Identity comes from a signed token and never from the request.**

The user service owns accounts, hashes passwords with BCrypt, and issues a short-lived HS256 JWT
whose subject is the user id — which is the same string every other service already calls
`customerId`, so nothing migrates and orders placed before this service existed remain valid.

`customerId` is **removed** from `PlaceOrderRequest`. The order service derives it from the token.

**Ownership is enforced on reads and on cancellation, not just on placement.** Placing an order as
somebody else and cancelling somebody else's order are the same hole seen from two ends, and this
project has shipped a half-fix before: Phase 10 mapped one pool-timeout exception to 503 and missed
the second hierarchy, so half of all load-shedding kept reporting itself as a server fault for a phase
and a half. `GET /orders` no longer takes a `customerId` parameter at all — a parameter naming whose
data to return is a parameter somebody will change to somebody else's.

**Somebody else's order is 404, not 403.** A 403 confirms the order number exists, which turns the
endpoint into an oracle for guessable order numbers. The same reasoning makes sign-in refuse to
distinguish an unknown email from a wrong password, and run BCrypt against a dummy hash when the email
is unknown so the timing does not distinguish them either.

**Both the gateway and the order service verify the token.** This is the part most likely to look
redundant, and it is the part that matters most.

The tidy design is for the gateway to verify once and inject a trusted `X-Customer-Id` header. That is
correct when the edge is the only route in. **It is not this deployment.** Compose publishes every
service on its own host port, and the README tells people to open catalog's Swagger UI on 18081 — so
anything that can reach the gateway can reach the order service on 18082 and send whatever header it
likes. A gateway-only check would be an authentication system with an opt-out.

So the gateway refuses unauthenticated traffic *early*, which is the same instinct as the availability
gate and the connection-pool timeout — shed doomed work before it costs anything. And the one service
that must not be wrong about who is buying verifies the token itself.

## Alternatives considered

**Keycloak or another OIDC provider.** The production-shaped answer, and what CoreBank already
demonstrates. Rejected here because it is mostly configuration rather than design, and because it adds
a container to a machine where memory pressure has already turned one test run into a nine-hour one.

**Gateway-only verification with an injected header.** Rejected for the reason above: with per-service
ports published, it is bypassable. It would become the right design the moment only the gateway is
reachable, and that is the change to make before trusting it.

**Refresh tokens.** Deliberately absent. A twelve-hour access token with no refresh is a worse
experience and a smaller surface, and rotation, revocation and refresh-token theft are a body of work
that would dwarf everything above without teaching this platform anything it does not already
demonstrate.

**Roles or scopes.** Also absent. Every authenticated caller is a shopper. Inventory's stock endpoints
and payment's internals remain unauthenticated operational APIs — which is stated here rather than
left for a reader to discover, because it is the largest remaining gap.

## Consequences

**Good.** The platform's three most sensitive operations — place, read, cancel — now answer "who is
asking" from something the caller cannot forge. The blast radius is the evidence the hole was real:
every order test had to learn to sign in.

**Bad, and worth being plain about.** The secret is symmetric, so every verifier can also mint. That is
acceptable while all of them are this platform and stops being acceptable the day a third party needs
to verify a token — which is when this becomes RS256 with the user service keeping the private half.

Bean validation also runs *before* the controller method, and therefore before the token is checked:
an unauthenticated request with a malformed body is told its body is malformed rather than that it is
unauthenticated. The security property holds — no order is created — but the service parses and
validates input for a caller that has not identified itself, and a client can learn a little about
the schema without an account. Moving the check into a filter would fix it and is not worth the
indirection today; it is recorded here so that judgement is visible rather than accidental.

The inventory, payment and shipping APIs are still open. Nothing reaches them from the internet in this
deployment, but "nothing reaches them" is a claim about a compose file, not a property of the system,
and per-service ports are published. Closing that means either an internal-only network or the same
token check on those services, and it is the obvious next piece of work.
