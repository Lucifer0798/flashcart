// Do pooled gateway connections die after sitting idle? Fills the gateway's pool to the order service with VUS
// concurrent requests, leaves them idle for IDLE seconds, then sends the same burst again and counts what fails.
// See load/idle-race.sh and ADR 0076.

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://gateway:8080';
const VUS = Number(__ENV.VUS || 200);
const IDLE = Number(__ENV.IDLE || 60);
const START_AT = Number(__ENV.START_AT);

const firstFailed = new Counter('first_failed');
const secondFailed = new Counter('second_failed');

export const options = {
	scenarios: { race: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '5m' } },
};

function burst(at, failed) {
	const wait = (at - Date.now()) / 1000;
	if (wait > 0) {
		sleep(wait);
	}
	const response = http.get(`${GATEWAY}/api/v1/order/_info`);
	if (response.status !== 200) {
		failed.add(1);
	}
}

export default function () {
	// Everybody at once, so the gateway opens a connection for each.
	burst(START_AT, firstFailed);
	burst(START_AT + IDLE * 1000, secondFailed);
}

export function handleSummary(data) {
	const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
	return { stdout: `idle ${IDLE}s: first burst failed ${count('first_failed')}, second burst failed ${count('second_failed')} of ${VUS}\n` };
}
