// Everyone signs in at once, the way they do when a sale opens. See load/sign-in.sh and ADR 0071.
//
// Each virtual user is one shopper with their own account, signing in once and then refreshing once. A share of
// them mistype their password first, as real shoppers do, and then type it right. Every request comes from this
// one container, so to the platform they all share one client address: an office, or a mobile carrier's NAT.

import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://gateway:8080';
const PREFIX = __ENV.PREFIX;
const SHOPPERS = Number(__ENV.SHOPPERS || 1000);
const TYPO_PERCENT = Number(__ENV.TYPO_PERCENT || 0);
const PASSWORD = __ENV.PASSWORD;
const LABEL = __ENV.LABEL || 'sign-in';

if (!PREFIX || !PASSWORD) {
	throw new Error('PREFIX and PASSWORD are required; run this through load/sign-in.sh, which seeds the accounts');
}

// What happened to the sign-in with the right password, which is the one every shopper needs to work.
const signedIn = new Counter('sign_in_ok');
const rightPasswordRefused = new Counter('sign_in_right_password_throttled');
const shed = new Counter('sign_in_shed');
const errored = new Counter('sign_in_errored');
// The mistyped first attempt.
const typoRefused = new Counter('typo_refused');
const typoThrottled = new Counter('typo_throttled');
// A shopper whose typo was refused and who then got in: their success clears the one failure row that counts
// against them from this client (ADR 0058), which the runner's books have to allow for.
const typoThenSignedIn = new Counter('typo_then_signed_in');
// The refresh after it.
const refreshed = new Counter('refresh_ok');
const refreshErrored = new Counter('refresh_errored');

const signInMs = new Trend('sign_in_ok_ms', true);
const refreshMs = new Trend('refresh_ok_ms', true);

export const options = {
	scenarios: {
		sale_opens: {
			executor: 'per-vu-iterations',
			vus: SHOPPERS,
			iterations: 1,
			maxDuration: '3m',
		},
	},
	// Nothing here fails the run: a refusal is a finding to count, not a broken harness. The runner checks the
	// counts against PostgreSQL instead.
	summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function signIn(email, password, name) {
	return http.post(`${GATEWAY}/api/v1/users/sessions`, JSON.stringify({ email, password }), {
		headers: { 'Content-Type': 'application/json' },
		tags: { name },
		responseCallback: http.expectedStatuses(200, 401, 429, 503),
	});
}

export default function () {
	const shopper = __VU;
	const email = `${PREFIX}-${shopper}@example.test`;

	// Spread evenly rather than at random, so a 5% run is exactly 5% and two runs are the same experiment.
	let typoRecorded = false;
	if (shopper % 100 < TYPO_PERCENT) {
		const typo = signIn(email, `${PASSWORD}-typo`, 'sign-in-typo');
		if (typo.status === 401) {
			typoRefused.add(1);
			typoRecorded = true;
		} else if (typo.status === 429) {
			typoThrottled.add(1);
		} else {
			errored.add(1);
			console.warn(`typo attempt for shopper ${shopper}: ${typo.status} ${String(typo.body).slice(0, 120)}`);
		}
	}

	const response = signIn(email, PASSWORD, 'sign-in');
	if (response.status === 200) {
		signedIn.add(1);
		signInMs.add(response.timings.duration);
		if (typoRecorded) {
			typoThenSignedIn.add(1);
		}
	} else if (response.status === 429) {
		rightPasswordRefused.add(1);
		return;
	} else if (response.status === 503) {
		shed.add(1);
		return;
	} else {
		errored.add(1);
		console.warn(`sign-in for shopper ${shopper}: ${response.status} ${String(response.body).slice(0, 120)}`);
		return;
	}

	const refreshToken = response.json('refreshToken');
	const refresh = http.post(`${GATEWAY}/api/v1/users/sessions/refresh`, JSON.stringify({ refreshToken }), {
		headers: { 'Content-Type': 'application/json' },
		tags: { name: 'refresh' },
	});
	if (refresh.status === 200) {
		refreshed.add(1);
		refreshMs.add(refresh.timings.duration);
	} else {
		refreshErrored.add(1);
		console.warn(`refresh for shopper ${shopper}: ${refresh.status} ${String(refresh.body).slice(0, 120)}`);
	}
}

export function handleSummary(data) {
	const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
	const trend = (name) => (data.metrics[name] ? data.metrics[name].values : {});
	const result = {
		shoppers: SHOPPERS,
		typoPercent: TYPO_PERCENT,
		signInOk: count('sign_in_ok'),
		rightPasswordThrottled: count('sign_in_right_password_throttled'),
		shed: count('sign_in_shed'),
		errored: count('sign_in_errored'),
		typoRefused: count('typo_refused'),
		typoThrottled: count('typo_throttled'),
		typoThenSignedIn: count('typo_then_signed_in'),
		refreshOk: count('refresh_ok'),
		refreshErrored: count('refresh_errored'),
		signInMs: trend('sign_in_ok_ms'),
		refreshMs: trend('refresh_ok_ms'),
		durationMs: data.state.testRunDurationMs,
	};
	return {
		stdout: `${JSON.stringify(result, null, 2)}\n`,
		[`/results/${LABEL}.json`]: JSON.stringify(result, null, 2),
	};
}
