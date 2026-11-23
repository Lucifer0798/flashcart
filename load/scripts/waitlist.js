// A sold-out item comes back: everyone waiting is in the queue, the stock lands, and the queue decides who gets it.
// See load/waitlist.sh and ADR 0078.
//
// Shoppers 1..WAITERS queue for the SKU together at JOIN_AT. At RESTOCK_AT the runner receives the units. Each waiter
// watches their place until told the SKU is back, then checks out at once, except a share (NO_SHOW_PERCENT) who
// never come back, so their held unit has to lapse and pass down the line. Shoppers WAITERS+1..WAITERS+STRANGERS
// never queued, and try to buy the moment the stock lands: the units are held for the queue, so they should get none.

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://gateway:8080';
const PREFIX = __ENV.PREFIX;
const PASSWORD = __ENV.PASSWORD;
const SKU = __ENV.SKU;
const WAITERS = Number(__ENV.WAITERS || 500);
const STRANGERS = Number(__ENV.STRANGERS || 0);
const NO_SHOW_PERCENT = Number(__ENV.NO_SHOW_PERCENT || 0);
const JOIN_AT = Number(__ENV.JOIN_AT);
const RESTOCK_AT = Number(__ENV.RESTOCK_AT);
// How long a waiter keeps watching after the restock before deciding they will not be told.
const WATCH_SECONDS = Number(__ENV.WATCH_SECONDS || 300);
const LABEL = __ENV.LABEL || 'waitlist';

if (!PREFIX || !PASSWORD || !SKU || !JOIN_AT || !RESTOCK_AT) {
	throw new Error('PREFIX, PASSWORD, SKU, JOIN_AT and RESTOCK_AT are required; run this through load/waitlist.sh');
}

const signInFailed = new Counter('sign_in_failed');
const joined = new Counter('joined');
const joinFailed = new Counter('join_failed');
const told = new Counter('told');
const notTold = new Counter('not_told');
const noShow = new Counter('no_show');
const toldShipped = new Counter('told_shipped');
const toldCancelled = new Counter('told_cancelled');
const toldStuck = new Counter('told_stuck');
const toldRefused = new Counter('told_refused_at_placing');
const strangerShipped = new Counter('stranger_shipped');
const strangerCancelled = new Counter('stranger_cancelled');
const strangerOther = new Counter('stranger_other');

const toldAfterMs = new Trend('told_after_restock_ms', true);
const shippedAfterToldMs = new Trend('shipped_after_told_ms', true);

export const options = {
	scenarios: { restock: { executor: 'per-vu-iterations', vus: WAITERS + STRANGERS, iterations: 1, maxDuration: '20m' } },
	summaryTrendStats: ['min', 'med', 'p(95)', 'max'],
};

function json(token) {
	return { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } };
}

function waitUntil(at) {
	const wait = (at - Date.now()) / 1000;
	if (wait > 0) {
		sleep(wait);
	}
}

// Places an order for one unit and watches it settle, as a shopper would.
function checkOut(token, shopper) {
	const placed = http.post(`${GATEWAY}/api/v1/orders`,
		JSON.stringify({ idempotencyKey: `${PREFIX}-${shopper}`, lines: [{ sku: SKU, quantity: 1 }] }), json(token));
	if (placed.status !== 202) {
		return { outcome: 'refused', status: placed.status };
	}
	const orderNumber = placed.json('orderNumber');
	const deadline = Date.now() + 120000;
	while (Date.now() < deadline) {
		sleep(2);
		const seen = http.get(`${GATEWAY}/api/v1/orders/${orderNumber}`, json(token));
		if (seen.status === 200 && (seen.json('status') === 'SHIPPED' || seen.json('status') === 'CANCELLED')) {
			return { outcome: seen.json('status') };
		}
	}
	return { outcome: 'stuck' };
}

export default function () {
	const shopper = __VU;
	const stranger = shopper > WAITERS;
	const session = http.post(`${GATEWAY}/api/v1/users/sessions`,
		JSON.stringify({ email: `${PREFIX}-${shopper}@example.test`, password: PASSWORD }),
		{ headers: { 'Content-Type': 'application/json' } });
	if (session.status !== 200) {
		signInFailed.add(1);
		return;
	}
	const token = session.json('accessToken');

	if (stranger) {
		// Never queued: tries to buy just after the units land, while they are held for the queue.
		waitUntil(RESTOCK_AT + 1000);
		const result = checkOut(token, shopper);
		if (result.outcome === 'SHIPPED') {
			strangerShipped.add(1);
		} else if (result.outcome === 'CANCELLED') {
			strangerCancelled.add(1);
		} else {
			strangerOther.add(1);
			console.warn(`stranger ${shopper}: ${result.outcome} ${result.status || ''}`);
		}
		return;
	}

	waitUntil(JOIN_AT);
	const join = http.post(`${GATEWAY}/api/v1/inventory/waitlist`, JSON.stringify({ sku: SKU }), json(token));
	if (join.status !== 201) {
		joinFailed.add(1);
		console.warn(`waiter ${shopper} could not join: ${join.status} ${String(join.body).slice(0, 100)}`);
		return;
	}
	joined.add(1);

	waitUntil(RESTOCK_AT);
	const deadline = RESTOCK_AT + WATCH_SECONDS * 1000;
	while (Date.now() < deadline) {
		sleep(2);
		const mine = http.get(`${GATEWAY}/api/v1/inventory/waitlist/mine`, json(token));
		if (mine.status !== 200) {
			continue;
		}
		const entry = (mine.json() || []).find((e) => e.sku === SKU);
		if (entry && entry.status === 'NOTIFIED') {
			told.add(1);
			const toldAt = Date.now();
			toldAfterMs.add(toldAt - RESTOCK_AT);
			if (shopper % 100 < NO_SHOW_PERCENT) {
				// Told, and never comes back: the unit held for them has to lapse and go to the next in line.
				noShow.add(1);
				return;
			}
			const result = checkOut(token, shopper);
			if (result.outcome === 'SHIPPED') {
				toldShipped.add(1);
				shippedAfterToldMs.add(Date.now() - toldAt);
			} else if (result.outcome === 'CANCELLED') {
				toldCancelled.add(1);
				console.warn(`told waiter ${shopper} checked out and was cancelled`);
			} else if (result.outcome === 'refused') {
				toldRefused.add(1);
				console.warn(`told waiter ${shopper} refused at placing: ${result.status}`);
			} else {
				toldStuck.add(1);
			}
			return;
		}
	}
	notTold.add(1);
}

export function handleSummary(data) {
	const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
	const trend = (name) => (data.metrics[name] ? data.metrics[name].values : {});
	const result = {
		waiters: WAITERS,
		strangers: STRANGERS,
		noShowPercent: NO_SHOW_PERCENT,
		signInFailed: count('sign_in_failed'),
		joined: count('joined'),
		joinFailed: count('join_failed'),
		told: count('told'),
		notTold: count('not_told'),
		noShow: count('no_show'),
		toldShipped: count('told_shipped'),
		toldCancelled: count('told_cancelled'),
		toldRefused: count('told_refused_at_placing'),
		toldStuck: count('told_stuck'),
		strangerShipped: count('stranger_shipped'),
		strangerCancelled: count('stranger_cancelled'),
		strangerOther: count('stranger_other'),
		toldAfterMs: trend('told_after_restock_ms'),
		shippedAfterToldMs: trend('shipped_after_told_ms'),
		durationMs: data.state.testRunDurationMs,
	};
	return {
		stdout: `${JSON.stringify(result, null, 2)}\n`,
		[`/results/${LABEL}.json`]: JSON.stringify(result, null, 2),
	};
}
