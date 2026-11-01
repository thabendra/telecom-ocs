// RL-5: far more new sessions than the backend can take, while sessions already running keep reporting.
// New sessions may be refused; update must keep working: its usage has to be charged.
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import { fetchToken, start, subscriber, tlsAuth, update } from './lib.js';

const SESSIONS = 100;
const NEW_PER_SECOND = Number(__ENV.NEW_PER_SECOND || 900);   // load-test.sh raises the rate limit: this tests the slots
const UPDATES_PER_SECOND = Number(__ENV.UPDATES_PER_SECOND || 50);
const DURATION = __ENV.DURATION || '60s';

const newRefused = new Counter('new_sessions_refused');
const updateRefused = new Rate('updates_refused');

export const options = {
    tlsAuth,
    setupTimeout: '120s',
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    scenarios: {
        newSessions: {
            executor: 'constant-arrival-rate',
            exec: 'newSession',
            rate: NEW_PER_SECOND,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 200,
            maxVUs: 500,
        },
        ongoing: {
            executor: 'constant-arrival-rate',
            exec: 'ongoing',
            rate: UPDATES_PER_SECOND,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: SESSIONS,
            maxVUs: SESSIONS,
        },
    },
    thresholds: {
        new_sessions_refused: ['count>0'],   // otherwise this wasn't an overload at all
        updates_refused: ['rate<0.01'],
    },
};

export function setup() {
    const token = fetchToken();
    const sessions = [];
    for (let i = 0; i < SESSIONS; i++) {
        const answer = start(subscriber(i), token);
        if (answer.status !== 201) {
            throw new Error(`Session ${i} not started: ${answer.status} ${answer.body}`);
        }
        sessions.push(answer.json('sessionId'));
    }
    return { token, sessions };
}

export function newSession({ token }) {
    // from the other end of the subscribers, so they never wait for a lock an update holds
    const answer = start(subscriber(SESSIONS + (exec.scenario.iterationInTest % 1900)), token);
    if (answer.status === 503) {
        newRefused.add(1);
    }
}

// the sessions in turn: at 50 per second one comes round every 2 s, so each one's numbers stay in order
export function ongoing({ token, sessions }) {
    const iteration = exec.scenario.iterationInTest;
    const answer = update(sessions[iteration % SESSIONS], Math.floor(iteration / SESSIONS) + 1, token);
    updateRefused.add(answer.status === 503);
    check(answer, { 'update is 200': (r) => r.status === 200 });
}
