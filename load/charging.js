// TE-2, CH-9: at the target load, the 99th percentile of every charging call stays under 50 ms.
// One session is 3 calls: start, update, release. The first 30 s warm the JVM up and don't count.
import { check } from 'k6';
import exec from 'k6/execution';
import { fetchToken, release, start, subscriber, tlsAuth, update } from './lib.js';

const TARGET_RPS = Number(__ENV.TARGET_RPS || 100);
const P99_MS = Number(__ENV.P99_MS || 50);
const SESSIONS_PER_SECOND = Math.ceil(TARGET_RPS / 3);

const limit = [`p(99)<${P99_MS}`];

export const options = {
    tlsAuth,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    scenarios: {
        warmup: {
            executor: 'ramping-arrival-rate',
            startRate: 1,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 200,
            stages: [{ target: SESSIONS_PER_SECOND, duration: '30s' }],
        },
        target: {
            executor: 'constant-arrival-rate',
            startTime: '30s',
            rate: SESSIONS_PER_SECOND,
            timeUnit: '1s',
            duration: __ENV.DURATION || '2m',
            preAllocatedVUs: 100,
            maxVUs: 500,
        },
    },
    thresholds: {
        'http_req_duration{scenario:target}': limit,
        'http_req_duration{scenario:target,op:start}': limit,
        'http_req_duration{scenario:target,op:update}': limit,
        'http_req_duration{scenario:target,op:release}': limit,
        'http_req_failed{scenario:target}': ['rate<0.001'],
        // k6 couldn't keep the rate: then the number above isn't "at the target load"
        'dropped_iterations{scenario:target}': ['count==0'],
    },
};

export function setup() {
    return { token: fetchToken() };
}

export default function ({ token }) {
    const started = start(subscriber(exec.scenario.iterationInTest), token);
    if (!check(started, { 'start is 201': (r) => r.status === 201 })) {
        return;
    }
    const sessionId = started.json('sessionId');
    check(update(sessionId, 1, token), { 'update is 200': (r) => r.status === 200 });
    check(release(sessionId, 2, token), { 'release is 200': (r) => r.status === 200 });
}
