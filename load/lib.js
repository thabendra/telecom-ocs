// Shared by the load tests: a gateway's certificate and token, and one call to the charging API.
import http from 'k6/http';

export const GATEWAY = __ENV.GATEWAY || 'pgw-colombo-01';
const BACKEND = __ENV.BACKEND_URL || 'https://backend:8443';
const TOKEN_URL = __ENV.TOKEN_URL || 'https://token-server:9443/oauth2/token';

// k6 needs the key as PEM, and sends exactly the chain given here. The token server's tls_client_auth wants the
// CA in it too, as a gateway's .p12 sends. The CA to trust comes from SSL_CERT_FILE in docker-compose.load.yml.
export const tlsAuth = [{
    domains: ['backend', 'token-server'],
    cert: open(`/certs/${GATEWAY}.pem`) + open('/certs/ca.pem'),
    key: open(`/certs/${GATEWAY}-key.pem`),
}];

// lives 5 minutes, and is bound to the certificate above; every test here is shorter
export function fetchToken() {
    const answer = http.post(TOKEN_URL, {
        grant_type: 'client_credentials',
        client_id: GATEWAY,
        scope: 'charging:write notify:read',
    });
    if (answer.status !== 200) {
        throw new Error(`No token for ${GATEWAY}: ${answer.status} ${answer.body}`);
    }
    return answer.json('access_token');
}

// 2000 subscribers from seed.sql: 94700000001 to 94700002000
export function subscriber(n) {
    return `947${String((n % 2000) + 1).padStart(8, '0')}`;
}

function newKey() {
    return 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => Math.floor(Math.random() * 16).toString(16));
}

function post(path, body, token, op) {
    return http.post(`${BACKEND}${path}`, JSON.stringify(body), {
        headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`,
            'Idempotency-Key': newKey(),
        },
        tags: { op },
    });
}

export function start(msisdn, token) {
    return post('/charging/v1/sessions', { msisdn, app: 'GENERAL', requestedMb: 1 }, token, 'start');
}

export function update(sessionId, requestNumber, token) {
    return post(`/charging/v1/sessions/${sessionId}/update`, { requestNumber, usedMb: 1, requestedMb: 1 }, token, 'update');
}

export function release(sessionId, requestNumber, token) {
    return post(`/charging/v1/sessions/${sessionId}/release`, { requestNumber, usedMb: 1 }, token, 'release');
}

export function pollCommands(token) {
    return http.get(`${BACKEND}/charging/v1/commands`, {
        headers: { Authorization: `Bearer ${token}` },
        tags: { op: 'poll' },
    });
}
