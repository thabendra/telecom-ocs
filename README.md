# Telecom Online Charging System (telecom-ocs)

A mobile operator's **online charging system (OCS)**: the network gateway asks before it lets data
through, gets a quota grant (MB, speed, validity) from the right bucket (YouTube Unlimited, WhatsApp,
Extra 25GB, plan data or pay as you go), reports what was used, and is told to block the user when
nothing is left. It also manages subscribers, plans and data packages. Everything is exposed as a
**contract first SOAP web service** (Spring-WS) and a **REST API**, on an **Oracle Database** with
**PL/SQL stored procedures**, and the gateway API is protected with **mTLS, OAuth2 tokens, idempotent
retries and rate limiting**.

**Stack:** Java 21 · Spring Boot 3.4 · Spring Web Services (SOAP, WSDL, XSD validation) · JAXB ·
Spring Data JPA / Hibernate (`@Procedure`) · Oracle Database Free 23ai · PL/SQL · Flyway · Redis (Lua) ·
Spring Security (X.509, OAuth2 resource server) · Spring Authorization Server · Resilience4j · Micrometer ·
JUnit 5 · Mockito · Spring-WS Test · Testcontainers · Docker Compose · GitHub Actions

## Architecture

```
SOAP  /ws  (WSDL: /ws/subscribers.wsdl) ──► SubscriberEndpoint, UsageEndpoint ──┐
                                                                                 ├──► SubscriberService ─┐
REST  /api/...                         ──► SubscriberController, UsageController ┘    UsageService ──────┤
                                                                                                         ▼
                                                     Oracle: tables + change_plan / activate_package / record_usage (PL/SQL)
```

- `src/main/resources/xsd/subscriber.xsd` is the SOAP contract. `jaxb2-maven-plugin` generates the
  Java request/response classes from it, and Spring-WS builds the WSDL from it.
- Every SOAP request is validated against the XSD (MSISDN `947XXXXXXXX`, NIC format, app names,
  money with 2 decimals) before it reaches the endpoint.
- The PL/SQL procedures lock the subscriber row with `SELECT ... FOR UPDATE`, so two requests for the
  same subscriber can't spend the same balance or data twice. Their `ORA-20001..20006` errors become
  SOAP **Client** faults (REST: 404 / 409).

## How usage charging works

In a real network, the gateway's **DPI** detects which app traffic belongs to, the **OCS** (online
charging system) takes it from the right package, and the **PCRF** decides the speed. This service
plays the OCS + PCRF part: `RecordUsage` receives the app the network detected and the MB used.

`record_usage` (Flyway `V5`) takes the data from buckets in this order, splitting one event across
several buckets when one runs out:

1. Valid packages for that app (YouTube traffic → YouTube Unlimited), soonest expiring first
2. Valid add-on packages for any app (Extra 25GB), soonest expiring first
3. The base plan's data
4. Pay as you go from the money balance (0.50 LKR per MB)

If even the balance can't cover the rest, nothing is charged and a fault is returned. The response
lists each bucket's share and the **speed limit** the network should apply now:

| Bucket | Speed |
|---|---|
| Plan | BASIC 10 Mbps · SMART 20 Mbps · MAX 50 Mbps |
| YouTube Unlimited | 2 Mbps, then **512 kbps after 50GB** (fair usage) |
| WhatsApp 2GB | 1 Mbps |
| Facebook 5GB | 2 Mbps |
| Extra 25GB | 20 Mbps |
| Pay as you go | 1 Mbps |

Example: Kamal is on BASIC with a WhatsApp 2GB pack and uses 2100 MB of WhatsApp. 2048 MB comes from
the WhatsApp pack and the last 52 MB from his plan data.

## Online charging API (for the network gateway)

`RecordUsage` charges *after* traffic is used. The online charging API lets the gateway ask
*before*: it gets a grant, lets that much traffic through, reports what was used and asks again.
When nothing is left the answer is `DENY` + `BLOCK`, and the gateway stops the traffic before it
reaches the destination server.

The gateway can call it over **REST or SOAP**. Both adapters implement the same `ChargingApi`
interface and get the same `ChargingService` bean by constructor injection:

```
            ChargingApi<request/response types>     (charging/ChargingApi.java)
               ▲                        ▲
 ChargingController (REST, JSON)   ChargingEndpoint (SOAP, XML from the XSD)
               └──────────┬─────────────┘
                   ChargingService  (one Spring bean)  ──►  CHARGING PL/SQL package
```

| Step | REST | SOAP operation | Answer |
|---|---|---|---|
| Start | `POST /charging/v1/sessions` (`msisdn`, `app`, `requestedMb`) | `StartSession` | `sessionId` + grant |
| Update | `POST /charging/v1/sessions/{id}/update` (`requestNumber`, `usedMb`, `requestedMb`) | `UpdateSession` | charges `usedMb`, next grant |
| Release | `POST /charging/v1/sessions/{id}/release` (`requestNumber`, `usedMb`) | `ReleaseSession` | charges `usedMb`, `totalUsedMb` |

`requestNumber` is the gateway's counter for the session (start is 0, then 1, 2, ...).

A grant is `{result: ALLOW, grantedMb, bandwidthKbps, source, packageCode, validitySeconds}` or
`{result: DENY, grantedMb: 0, action: BLOCK}`.

```
Gateway                                  Backend (CHARGING PL/SQL package, V7)
  │ start  GENERAL, want 100 MB  ───────► reserve 100 MB of plan data (held, not spent)
  │ ◄── ALLOW 100 MB, 10 Mbps ──────────
  │ update used 100, want 100    ───────► commit 100 MB, reserve next 100 MB
  │ ◄── ALLOW 100 MB ───────────────────
  │ release used 37              ───────► commit 37 MB, free the other 63 MB
```

- **Reserve → commit:** granted MB (or money, on pay as you go) goes into the bucket's `*_reserved`
  column. Only the reported `usedMb` is charged; the rest is given back. Reserved quota can't be
  used by another session (YouTube and WhatsApp at the same time) or by `RecordUsage`.
- **One bucket per grant**, chosen in the same order as `record_usage`, so each grant has one speed.
  If the bucket has less left than requested, the grant is smaller and the next request moves on.
- A report above the grant is capped at the grant (the gateway never lets more through).
- Every procedure locks the subscriber row first, so charging for one subscriber runs one at a time.

## Gateway security (group 3: SE-1 to SE-5)

The certificate says **who** the gateway is; the access token says **what it may do**. A gateway
call passes four checks:

```
1. Gateway ──mTLS, client_id──► token-server :9443 /oauth2/token        (SE-2, client credentials)
           ◄── JWT { iss, sub=pgw-colombo-01, aud=charging-api, exp=+5 min,
                     scope=charging:write, cnf.x5t#S256 = certificate thumbprint }
2. Gateway ══mTLS══ Authorization: Bearer JWT ══► backend :8443
     a. TLS handshake: certificate from our CA, not expired                  SE-1
     b. JWT: signature (token server's /oauth2/jwks), iss, aud, exp          SE-3
     c. certificate name allowed, token issued to it, cnf = this certificate SE-1, SE-3, SE-4
     d. scope charging:write                                                 SE-5
```

| Problem | Answer |
|---|---|
| No / foreign / expired certificate | TLS handshake fails |
| No token, expired, wrong `iss` or `aud`, bad signature | `401` |
| Token bound to another certificate, or not bound (`cnf`) | `401`: a stolen token is useless |
| Certificate name not allowed, or a token issued to another gateway | `403` |
| Token without `charging:write` | `403` |

### Token server (SE-2, SE-4)

`token-server/` is a separate Spring Boot app built on **Spring Authorization Server**:

- Client credentials grant only, one client per gateway (`telecom.token.clients`).
- Clients authenticate with **`tls_client_auth`** (RFC 8705): the certificate's subject must match the
  client, so there is no shared secret to leak or rotate.
- Access tokens are JWTs signed with RSA, valid 5 minutes, with `aud=charging-api` and the bound
  certificate thumbprint `cnf.x5t#S256`. Asking for a scope the client doesn't have is `invalid_scope`.
- `/oauth2/jwks` publishes the public key, readable without a certificate (`client-auth: want`).

The gateway simulator fetches a token at the start, reuses it until 30 s before it expires, and on a
`401` fetches a new one once and sends the call again.

### mTLS on its own port (SE-1)

The application has two doors:

```
Gateways ──mTLS──► :8443  /charging/**  and  /ws (SOAP)    server.port, client-auth: need
Customers/admin ─► :8080  /api/**                           telecom.public-port, plain HTTP for now
```

- **Port 8443 (gateway port)** needs a client certificate signed by our private CA. A missing,
  expired or foreign certificate fails in the TLS handshake, before any code runs.
- **Who is calling** comes from the certificate: its name (`CN=pgw-colombo-01`) must be on
  `telecom.gateways.allowed` and be the gateway the token was issued to. That name also scopes the
  idempotency keys, so an `X-Client-Id` header can't borrow another gateway's keys.
- **Each API only on its own door** (`PortRuleFilter`): the gateway API on 8080, or the public API
  on 8443, answers `404`.

The certificates are test-only and are created by a script (they are not in git):

```bash
scripts/generate-dev-certs.sh   # certs/: dev CA, backend cert for localhost, gateway certs, bad certs for tests
```

| File in `certs/` | Used by |
|---|---|
| `backend.p12` | backend key store: its certificate + private key for the gateway port |
| `token-server.p12` | token server key store (HTTPS on 9443) |
| `gateway-ca-truststore.p12` | trust store with only the dev CA (backend and gateways) |
| `pgw-colombo-01.p12`, `pgw-kandy-01.p12` | gateway key stores (allowed gateways) |
| `pgw-unknown-99.p12` | signed by our CA but not allowed (`403`) |
| `pgw-expired.p12`, `stranger.p12` | expired / signed by another CA (handshake fails) |

In production the files come from a secret store (`TLS_DIR`, `TLS_KEYSTORE_PASSWORD`,
`TLS_TRUSTSTORE_PASSWORD`), and revoking a single gateway (SE-6) is a later step.

## Safe retries: idempotency

A gateway that times out doesn't know if its request was charged, so it retries. Two layers make
sure a retry is never charged twice:

```
Gateway ── Idempotency-Key: K ──► IdempotencyFilter ──► Redis  idem:{client}:{K}
                                     │ first time: lock (SET NX), run, store the answer (24h)
                                     │ same K again: replay the stored answer (Idempotent-Replayed: true)
                                     ▼
                                 ChargingService ──► Oracle CHARGING_REQUEST (session, requestNumber) PK
                                                     a repeated number is refused (ORA-20009 → 409)
```

**Layer 1: Idempotency-Key in Redis** (`idempotency/IdempotencyFilter.java`), on charging start /
update / release, package purchase and top-up:

| Case | Answer |
|---|---|
| No `Idempotency-Key` header | `400` |
| First request with a key | runs; its response is stored in Redis for 24h |
| Same key, same request again | the stored response, with `Idempotent-Replayed: true` |
| Same key, different method, path or body | `422` |
| Same key while the first is still running | `409` + `Retry-After: 1` |
| First request ended in a `5xx` | nothing stored, the retry runs again |

The SOAP charging operations get the same protection. All SOAP calls are `POST /ws`, so the filter
reads the operation inside `soapenv:Body`: `StartSessionRequest`, `UpdateSessionRequest` and
`ReleaseSessionRequest` need the key (set in `telecom.idempotency.soap-operations`); reads such as
`GetQuota` don't. A SOAP caller gets a SOAP Client fault instead of problem JSON, with the same HTTP
status (`400` / `409` + `Retry-After` / `422`). SOAP faults are HTTP `500`, so like any `5xx`
they aren't stored and a retry runs again (safe: Oracle's request numbers still apply).

Keys are per caller (`X-Client-Id` header, e.g. `pgw-colombo-01`). Every Redis key has a TTL.
Redis holds only request state: balances and charges live in Oracle. If Redis is down the
request still runs and layer 2 still protects charging.

**Layer 2: request numbers in Oracle** (`CHARGING_REQUEST`, V6/V7): each update and release is
stored under a unique `(session_id, request_number)` key, so even if Redis has lost a key the same
request fails with `409` instead of charging again.

## Rate limiting and overload (group 4)

Each request passes the filters in this order (`web/FilterOrder.java`):

```
PortRuleFilter     right door?                         404
Spring Security    who is calling?                     401 / 403
RateLimitFilter    token left for this gateway?        429 + Retry-After   (RL-1, RL-2)
IdempotencyFilter  replay, or run once                 400 / 409 / 422
ConcurrencyLimit   free database slot in this copy?    503 + Retry-After   (RL-4)
controller / SOAP endpoint -> Oracle
```

**Token bucket per gateway (RL-1, RD-3).** Every gateway has one bucket in Redis
(`ratelimit:{gateway}`): up to `capacity` tokens (the burst), refilled at `refill-per-second` (the
steady rate), one token per request. The refill is worked out when a request comes in, in one
atomic Lua script (`redis/token-bucket.lua`) using Redis' clock, so all backend copies share one
exact count and two copies can never both take the last token. An empty bucket answers `429` with
`Retry-After` (a SOAP fault for SOAP callers); the gateway's retry policy waits and tries again.
Retries count too, so a retry storm is limited as well. Limits per gateway can be overridden.

**Redis down (RD-6).** Rate limiting never stops traffic because Redis is down: each copy then
counts in memory with its share, `limit / instances`. Set `instances` (`MAX_INSTANCES`) to the most
copies the autoscaler can start, so all copies together never go above the limit.

**Database bulkhead (RL-4).** Each copy runs at most `max-in-flight` requests at the same moment, the
same number as its connection pool (`DB_POOL_SIZE`, one setting for both). A request that finds no
free slot within 50 ms gets `503` + `Retry-After` at once, instead of waiting for a connection and
holding a thread while Oracle is slow. The database itself only gets more load when copies are added,
so size it as `pool size x most copies <= sessions Oracle can take` and cap the autoscaler.

```yaml
telecom:
  rate-limit:
    capacity: 2000            # burst per gateway
    refill-per-second: 1000   # steady rate per gateway
    instances: 3              # most backend copies (fallback share)
    overrides: { pgw-kandy-01: { capacity: 500, refill-per-second: 250 } }
    max-in-flight: 10         # = spring.datasource.hikari.maximum-pool-size
    in-flight-wait: 50ms
```

## Push channel: commands for the gateways (group 5)

The backend sometimes has to tell the gateways to act without being asked: block a user who was
suspended, or let a user through again after a top-up.

| When | Command (to every gateway) |
|---|---|
| A charging DENY: nothing left anywhere (once, not on every DENY) | `BLOCK` `OUT_OF_QUOTA` |
| `PUT /api/subscribers/{msisdn}/suspension {"reason":"SUSPENDED"}` / `"FRAUD"` | `BLOCK` `SUSPENDED` / `FRAUD` |
| A top-up after running out | `UNBLOCK` `TOP_UP`, at the pay as you go speed (1024 kbps) |
| A package purchase after running out | `UNBLOCK` `PACKAGE`, at the package's speed |
| `DELETE /api/subscribers/{msisdn}/suspension` | `UNBLOCK` `UNSUSPENDED`, at the plan's speed |

A top-up never lifts a suspension or a fraud block.

**Outbox (PU-4).** Commands are rows in `gateway_command` (V8), written in the same transaction as the
change that causes them, so a command exists exactly when that change was committed.

**Channel (PU-3).** The gateway polls over its own outbound mTLS connection with a `notify:read`
token, and acks each command once it applied it:

```
GET  /charging/v1/commands            → [{id, msisdn, action, reason, bandwidthKbps, attempt}]
POST /charging/v1/commands/{id}/ack   → 204
```

```
PENDING ──poll──► SENT ──ack──► ACKED
                   │ no ack within 10 s, 20 s, 40 s, 60 s (+ up to 20 % jitter)
                   └──► delivered again (attempt 2, 3, 4) ──► FAILED after the 4th
```

The `id` is the gateway's idempotency key: a command delivered again because its ack was lost is
applied once and acked again. `SELECT ... FOR UPDATE SKIP LOCKED` lets two pollers of one gateway
take different commands.

## Gateway simulator

`gateway-simulator/` is a separate Spring Boot command line app that plays the network gateway: it
starts a session, keeps updating while the user "uses" each grant, blocks on `DENY`, and releases.
Every request has its own `Idempotency-Key`, the gateway's `X-Client-Id` and a growing `requestNumber`.

```bash
mvn -f gateway-simulator/pom.xml spring-boot:run
mvn -f gateway-simulator/pom.xml spring-boot:run -Dspring-boot.run.arguments="--simulator.protocol=SOAP"
mvn -f gateway-simulator/pom.xml spring-boot:run -Dspring-boot.run.arguments="--simulator.app=WHATSAPP --simulator.max-updates=30"
mvn -f gateway-simulator/pom.xml spring-boot:run -Dspring-boot.run.arguments="--simulator.replay-each-request=true"
```

`replay-each-request` sends every request twice with the same key, like a retry after a timeout,
and fails if the backend doesn't replay the first answer (REST and SOAP). Settings are in
`gateway-simulator/src/main/resources/application.yml`.

The simulator calls the gateway port `https://localhost:8443` with the certificate
`certs/pgw-colombo-01.p12` (`simulator.tls-bundle: gateway`; another gateway with
`GATEWAY_CERT=pgw-kandy-01`). It trusts only the dev CA and checks that the backend's certificate
is for `localhost`.

### REST or SOAP

The simulator only talks to the `ChargingClient` interface; `simulator.protocol` picks the transport:

```
GatewaySimulator ──► ChargingClient (interface)
                          ▲
             RetryingChargingClient   (section I retry policy, wraps either one)
                          │
              ┌───────────┴───────────┐
     RestChargingClient       SoapChargingClient
   /charging/v1/sessions     /ws StartSession / UpdateSession / ReleaseSession
```

Both turn their errors into one `BackendCallException` (status 0 = no answer, otherwise the HTTP
status; a SOAP Client fault counts as 400, a Server fault as 500), so the retry rules are the same.
Both protocols send the `Idempotency-Key`, and the backend replays both, so a retried start never
opens a second session.

### Retry policy (section I)

Every call goes through `RetryingCaller`:

| Rule | Behaviour |
|---|---|
| Tries | First try + at most **3 retries**, each with the same `Idempotency-Key` and body |
| Retried | Timeouts, connection errors, `502` / `503` / `504`, `429`, and `409` with `Retry-After` (still in progress) |
| Not retried | Any other answer (`400`, `401`, `403`, `404`, `422`, `500`, `409` "already processed") |
| Wait | Full jitter: random(0, backoff), backoff 100 ms → 200 ms → 400 ms … capped at 1 s; never less than `Retry-After` |
| Time limits | 200 ms per try (connect + read), **1.5 s** for all tries and waits together |
| Circuit breaker | 5 failed tries in a row (network errors or 5xx) open it for 10 s: calls fail open at once |
| Metrics | `gateway.retries`, `gateway.retries.succeeded`, `gateway.retries.exhausted`, `gateway.circuit.rejected` (printed at the end of a run) |

When the retries, the time budget or the open circuit give up, the simulator **fails open**: it
lets the user's traffic through and would charge it later, instead of blocking a paying customer
because the backend is slow.

## SOAP operations

| Operation | What it does |
|---|---|
| `ListPlans` | All plans (BASIC, SMART, MAX are seeded) |
| `CreateSubscriber` | New subscriber on a plan with an optional opening balance |
| `GetSubscriber` | Subscriber with plan details |
| `GetBalance` | Current balance |
| `TopUp` | Add credit |
| `ChangePlan` | Move to another plan, charging its monthly fee and refilling plan data (PL/SQL) |
| `ListPackages` | All data packages |
| `ActivatePackage` | Buy a package from the balance; valid for its number of days (PL/SQL) |
| `RecordUsage` | Charge MB used by an app and return the speed limit (PL/SQL) |
| `GetQuota` | Data left in every bucket, in charging order, with each bucket's current speed |
| `StartSession` / `UpdateSession` / `ReleaseSession` | Online charging for the gateway (see above) |

## REST endpoints

| Method | Path |
|---|---|
| GET | `/api/plans` |
| POST | `/api/subscribers` |
| GET | `/api/subscribers/{msisdn}` |
| POST | `/api/subscribers/{msisdn}/top-ups` |
| PUT | `/api/subscribers/{msisdn}/plan` |
| GET | `/api/packages` |
| POST | `/api/subscribers/{msisdn}/packages` |
| POST | `/api/subscribers/{msisdn}/usage` |
| GET | `/api/subscribers/{msisdn}/quota` |

## Run locally

```bash
scripts/generate-dev-certs.sh          # test certificates (once)
docker compose up -d                   # Oracle Database Free (ready in about a minute) + Redis
mvn -f token-server/pom.xml spring-boot:run   # token server on 9443
mvn spring-boot:run                    # gateway port 8443 (mTLS + token) + public port 8080
```

The SOAP API (WSDL at https://localhost:8443/ws/subscribers.wsdl) is on the gateway port, so a
client needs a gateway certificate and an access token. Sample requests are in `requests/`:

```bash
TLS="--cert-type P12 --cert certs/pgw-colombo-01.p12:changeit --cacert certs/ca.pem"
TOKEN=$(curl -s $TLS -d grant_type=client_credentials -d client_id=pgw-colombo-01 -d scope=charging:write \
        https://localhost:9443/oauth2/token | sed 's/.*"access_token":"\([^"]*\)".*/\1/')
AUTH="Authorization: Bearer $TOKEN"
curl -s $TLS -H "$AUTH" -H "Content-Type: text/xml" -d @requests/create-subscriber.xml https://localhost:8443/ws
curl -s $TLS -H "$AUTH" -H "Content-Type: text/xml" -d @requests/activate-package.xml https://localhost:8443/ws
curl -s $TLS -H "$AUTH" -H "Content-Type: text/xml" -d @requests/record-usage.xml https://localhost:8443/ws
curl -s $TLS -H "$AUTH" -H "Content-Type: text/xml" -H "Idempotency-Key: $(uuidgen)" \
     -d @requests/start-session.xml https://localhost:8443/ws
curl -s http://localhost:8080/api/subscribers/94771234567/quota
```

## Tests

```bash
scripts/generate-dev-certs.sh   # once: the tests use the certificates in certs/
mvn test
```

- `SubscriberServiceTest`, `OracleErrorsTest`: unit tests with Mockito, including mapping of Oracle errors.
- `GatewayTlsTest`: real HTTPS on both ports: allowed gateway over REST and SOAP, no certificate,
  foreign CA and expired certificate (handshake fails), not-allowed gateway (`403`), each API refused
  on the other port (`404`). MockMvc tests skip TLS and send a gateway certificate plus token with
  `TestCerts.gateway(...)`.
- `GatewayTokenTest`: access tokens over real mTLS: valid, missing, expired, wrong `aud` / `iss`,
  foreign signing key (`401`), no `charging:write` (`403`), another gateway's token (`403`), bound to
  another certificate or unbound (`401`), SOAP needs a token too. Tokens come from `TestTokens`,
  signed with a test key that the test context trusts.
- `token-server`: `TokenServerTest` gets real tokens over mTLS: claims (`sub`, `aud`, `scope`, `cnf`,
  5 min), another gateway's certificate, no certificate and an unknown client (`401`), a scope it
  doesn't have (`invalid_scope`), and the public key set.
- `SubscriberEndpointTest`: SOAP tests against a real Oracle container (Testcontainers): XSD validation,
  faults, plan change and the audit table.
- `ChargingControllerTest` (REST) and `ChargingEndpointTest` (SOAP): online charging against Oracle:
  grants per bucket, reserve → commit, DENY + BLOCK, session errors.
- `PushCommandTest`: the push channel against Oracle: BLOCK on DENY (once), suspension and fraud,
  UNBLOCK + speed after a top-up, a package or unsuspending, no unblock of a suspended user,
  polling per gateway with `notify:read`, acks, redelivery with a longer wait, `FAILED` after 4,
  and another gateway's command can't be acked. `PushPropertiesTest` checks the redelivery waits.
- `RateLimitTest`: the Lua bucket against real Redis (burst, refusal, refill, key TTL), `429` +
  `Retry-After` over REST and SOAP, other gateways unaffected, a retry that would be replayed is
  limited too, and charging keeps working on the local bucket while Redis is paused.
  `LocalRateLimiterTest`, `GatewayRateLimiterTest` and `ConcurrencyLimitFilterTest` are unit tests
  for the in-memory bucket, the fallback share and overrides, and the `503` bulkhead.
- `IdempotencyTest`: retries against Oracle + Redis: replay, 400 / 422 / 409, keys per client, TTL,
  two copies of a request at the same moment run once, and the Oracle request-number check when
  Redis has lost the key or is unreachable (the Redis container is paused).
- `gateway-simulator`: `GatewaySimulatorTest` checks the simulator's requests against a mocked backend;
  `ChargingClientRetryTest` checks the retry policy (backoff, jitter, budget, circuit breaker, metrics);
  `SoapChargingClientTest` and `RestChargingClientTest` check each protocol client,
  `ChargingClientsTest` the choice between them, `CommandPollerTest` the push channel (apply once,
  ack, retry), `GatewayHttpTest` the HTTPS client with the
  gateway certificate, and `TokenClientTest` getting, caching and refreshing access tokens.
- `UsageEndpointTest`: usage charging against Oracle: app packages, add-ons, splitting across buckets,
  pay as you go, fair usage throttling, quota order and plan data refill.

All integration tests share one Oracle and one Redis container (`@IntegrationTest`, `TestcontainersConfig`), and are
skipped when Docker isn't running.
