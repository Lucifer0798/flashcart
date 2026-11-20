// A sale opens: every shopper signs in, then all of them check out at the same moment, and each watches their
// order until it settles. See load/checkout.sh and ADR 0076.
//
// Signing in is not what is measured here (ADR 0071 measured it): each shopper signs in first and then waits for
// START_AT, so the orders, not the sign-ins, arrive together.

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://gateway:8080';
const PREFIX = __ENV.PREFIX;
const PASSWORD = __ENV.PASSWORD;
const SKU = __ENV.SKU;
const SHOPPERS = Number(__ENV.SHOPPERS || 1000);
const START_AT = Number(__ENV.START_AT);
const SETTLE_SECONDS = Number(__ENV.SETTLE_SECONDS || 120);
const LABEL = __ENV.LABEL || 'checkout';

if (!PREFIX || !PASSWORD || !SKU || !START_AT) {
	throw new Error('PREFIX, PASSWORD, SKU and START_AT are required; run this through load/checkout.sh');
}

const signInFailed = new Counter('sign_in_failed');
const late = new Counter('started_late');
const placed = new Counter('order_placed');
const placeRefused = new Counter('order_place_refused');
const shipped = new Counter('order_shipped');
const cancelled = new Counter('order_cancelled');
const stuck = new Counter('order_stuck');
const pollErrors = new Counter('poll_errors');

const placeMs = new Trend('order_place_ms', true);
const shippedMs = new Trend('order_shipped_ms', true);
const cancelledMs = new Trend('order_cancelled_ms', true);

export const options = {
	scenarios: {
		sale_opens: {
			executor: 'per-vu-iterations',
			vus: SHOPPERS,
			iterations: 1,
			maxDuration: '10m',
		},
	},
	summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max'],
};

const json = { headers: { 'Content-Type': 'application/json' } };

export default function () {
	const shopper = __VU;
	const session = http.post(`${GATEWAY}/api/v1/users/sessions`,
		JSON.stringify({ email: `${PREFIX}-${shopper}@example.test`, password: PASSWORD }),
		Object.assign({ tags: { name: 'sign-in' } }, json));
	if (session.status !== 200) {
		signInFailed.add(1);
		console.warn(`sign-in for shopper ${shopper}: ${session.status}`);
		return;
	}
	const auth = { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.json('accessToken')}` } };

	// Everybody waits for the same moment, so the orders arrive together.
	const wait = (START_AT - Date.now()) / 1000;
	if (wait > 0) {
		sleep(wait);
	} else {
		late.add(1);
	}

	const order = http.post(`${GATEWAY}/api/v1/orders`,
		JSON.stringify({ idempotencyKey: `${PREFIX}-${shopper}`, lines: [{ sku: SKU, quantity: 1 }] }),
		Object.assign({ tags: { name: 'place' } }, auth));
	const placedAt = Date.now();
	if (order.status !== 202) {
		placeRefused.add(1, { status: String(order.status) });
		console.warn(`placing for shopper ${shopper}: ${order.status} ${String(order.body).slice(0, 120)}`);
		return;
	}
	placed.add(1);
	placeMs.add(order.timings.duration);
	const orderNumber = order.json('orderNumber');

	// Watched the way a shopper would: every few seconds, until it settles or they give up.
	const deadline = placedAt + SETTLE_SECONDS * 1000;
	let status = 'CREATED';
	while (Date.now() < deadline) {
		sleep(3);
		const seen = http.get(`${GATEWAY}/api/v1/orders/${orderNumber}`, Object.assign({ tags: { name: 'watch' } }, auth));
		if (seen.status !== 200) {
			pollErrors.add(1);
			continue;
		}
		status = seen.json('status');
		if (status === 'SHIPPED') {
			shipped.add(1);
			shippedMs.add(Date.now() - placedAt);
			return;
		}
		if (status === 'CANCELLED') {
			cancelled.add(1);
			cancelledMs.add(Date.now() - placedAt);
			return;
		}
	}
	stuck.add(1, { status });
	console.warn(`order ${orderNumber} for shopper ${shopper} still ${status} after ${SETTLE_SECONDS}s`);
}

export function handleSummary(data) {
	const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
	const trend = (name) => (data.metrics[name] ? data.metrics[name].values : {});
	const result = {
		shoppers: SHOPPERS,
		signInFailed: count('sign_in_failed'),
		late: count('started_late'),
		placed: count('order_placed'),
		placeRefused: count('order_place_refused'),
		shipped: count('order_shipped'),
		cancelled: count('order_cancelled'),
		stuck: count('order_stuck'),
		pollErrors: count('poll_errors'),
		placeMs: trend('order_place_ms'),
		shippedMs: trend('order_shipped_ms'),
		cancelledMs: trend('order_cancelled_ms'),
		durationMs: data.state.testRunDurationMs,
	};
	return {
		stdout: `${JSON.stringify(result, null, 2)}\n`,
		[`/results/${LABEL}.json`]: JSON.stringify(result, null, 2),
	};
}
