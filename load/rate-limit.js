// TE-2, RL-1: one gateway over its limit gets 429 + Retry-After and no more than its share; another gateway
// isn't touched. scripts/load-test.sh runs this twice at once: ROLE=flood and ROLE=steady.
// Reads the push channel: it passes the same token bucket as charging, and charges nobody.
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { fetchToken, pollCommands, tlsAuth } from './lib.js';

const ROLE = __ENV.ROLE || 'flood';
const SECONDS = Number(__ENV.SECONDS || 60);
const RATE = Number(__ENV.RATE || (ROLE === 'flood' ? 150 : 20));

// what scripts/load-test.sh sets for this test: under what one computer can serve, or the limit is never reached
const BURST = Number(__ENV.RATE_LIMIT_BURST || 100);
const REFILL_PER_SECOND = Number(__ENV.RATE_LIMIT_REFILL || 50);

const passed = new Counter('passed_the_limiter');
const limited = new Counter('limited_429');

const flood = {
    // a full bucket, plus what refills, and a margin for timing either way
    passed_the_limiter: [`count<=${Math.ceil((BURST + REFILL_PER_SECOND * SECONDS) * 1.05)}`,
        `count>=${Math.floor(REFILL_PER_SECOND * SECONDS * 0.9)}`],
    limited_429: ['count>0'],
};
const steady = {
    limited_429: ['count==0'],
    http_req_failed: ['rate<0.01'],
};

export const options = {
    tlsAuth,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    scenarios: {
        calls: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: `${SECONDS}s`,
            preAllocatedVUs: ROLE === 'flood' ? 200 : 20,
            maxVUs: ROLE === 'flood' ? 1000 : 100,
        },
    },
    thresholds: ROLE === 'flood' ? flood : steady,
};

export function setup() {
    return { token: fetchToken() };
}

export default function ({ token }) {
    const answer = pollCommands(token);
    if (answer.status === 429) {
        limited.add(1);
        check(answer, { '429 says when to retry': (r) => r.headers['Retry-After'] !== undefined });
    } else {
        passed.add(1);
    }
}
