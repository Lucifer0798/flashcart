// The flash sale, as a load test.
//
// Every virtual user goes for the same SKU at the same moment. That is the whole point: this is not
// a throughput benchmark that happens to use the reserve endpoint, it is the contention scenario the
// platform was built around, and the number that matters is not latency.
//
// The number that matters is: how many succeeded?
//
// Exactly `stock` reservations must be granted, no matter how many thousands are attempted. Every
// other request must be refused cleanly with 409 INSUFFICIENT_STOCK. A single success over the
// stock level means the platform oversold, and no latency figure would redeem it.
//
// Run through run.sh, which seeds the stock, reads the strategy and gate settings back from the
// service so the results are labelled with what actually ran, and checks the ledger afterwards.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const GATEWAY = __ENV.GATEWAY || 'http://gateway:8080';
const SKU = __ENV.SKU;
const VUS = Number(__ENV.VUS || 200);
const ITERATIONS = Number(__ENV.ITERATIONS || 2000);

// Counted separately rather than read off the status codes afterwards, because "refused" and
// "failed" are different outcomes and conflating them is how a broken run looks like a busy one.
// A 409 is the platform working correctly. A 500 is not.
const granted = new Counter('reservations_granted');
const refused = new Counter('reservations_refused');
// Shed is its own outcome, not an error. The pool deliberately times out after two seconds rather
// than queueing a buyer for thirty behind a connection that will not arrive in time to matter, so a
// 503 here is the platform working as designed under more load than it can serve. Counting it as a
// failure would make correct load-shedding look like a fault; counting it as a refusal would be
// worse, because it would claim the SKU was sold out when nobody actually looked.
const shed = new Counter('reservations_shed');
const errored = new Counter('reservations_errored');
const grantLatency = new Trend('reservation_grant_ms', true);
const refuseLatency = new Trend('reservation_refuse_ms', true);

// Per-VU: k6 gives every VU its own JS runtime, so this counts only this VU's own requests.
let iteration = 0;

export const options = {
	scenarios: {
		// A shared-iteration burst rather than a ramp: a flash sale is not a gradual increase in
		// interest, it is everyone arriving at once because a timer hit zero.
		stampede: {
			executor: 'shared-iterations',
			vus: VUS,
			iterations: ITERATIONS,
			maxDuration: '2m',
		},
	},
	thresholds: {
		// The only hard threshold is correctness. Latency is reported, not enforced: this runs on a
		// laptop beside eleven other containers, and a p95 gate here would be measuring the machine.
		reservations_errored: ['count==0'],
		checks: ['rate==1.0'],
	},
	summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
	// A plain per-VU counter, because each VU runs its own JS instance and nothing else increments
	// this. Two cleverer attempts both produced keys that repeated:
	//
	//   scenario.iterationInTest + VU id  -- collided twice in 2000
	//   vu.idInTest + vu.iterationInInstance -- the iteration part sat at 1 for most requests, so
	//                                           each VU hammered one key over and over
	//
	// A repeated key is not harmless here. The service is idempotent on reservationKey, so it
	// correctly returns the *existing* hold with 201 -- and k6 then counts a replay as a sale. That
	// is how a run reported 424 grants against 100 units while the database held exactly 100 and was
	// right the whole time. The bug was always in the measurement, never in the platform.
	// The SKU is in the key, and that is not decoration. reservationKey is idempotent *globally*,
	// not per SKU: a key already used by an earlier run matches that run's hold and comes back 201
	// for a completely different product. Keys that restarted at load-1-1 every run therefore made
	// each run's early requests replay the previous run's reservations -- reporting 425, 325, 626
	// and 526 grants against 100 units while the database was correct throughout. The SKU carries a
	// timestamp, so it scopes the key to this run.
	const key = `load-${SKU}-${__VU}-${++iteration}`;

	const response = http.post(
		`${GATEWAY}/api/v1/inventory/reservations`,
		JSON.stringify({
			reservationKey: key,
			customerId: `shopper-${__VU}`,
			ttlSeconds: 900,
			lines: [{ sku: SKU, quantity: 1 }],
		}),
		{ headers: { 'Content-Type': 'application/json' }, tags: { name: 'reserve' } },
	);

	if (response.status === 201) {
		granted.add(1);
		grantLatency.add(response.timings.duration);
	} else if (response.status === 409) {
		refused.add(1);
		refuseLatency.add(response.timings.duration);
	} else if (response.status === 503) {
		shed.add(1);
	} else {
		errored.add(1);
	}

	// 201, 409 and 503 are all correct answers: sold, sold out, and too busy to look. What must never
	// happen is a 500 or a 400 — those mean the platform buckled rather than answered.
	check(response, {
		'answered cleanly (201, 409 or 503)': (r) =>
			r.status === 201 || r.status === 409 || r.status === 503,
	});
}
