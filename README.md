# Telecom Subscriber Service

A small mobile operator backend: subscribers, plans, data packages (YouTube Unlimited, WhatsApp,
Extra 25GB) and **usage charging** that decides which package pays for which app's traffic and what
speed limit applies. The same business logic is exposed as a **contract first SOAP web service**
(Spring-WS) and a **REST API**, on an **Oracle Database** with **PL/SQL stored procedures**.

**Stack:** Java 21 · Spring Boot 3.4 · Redis · Spring Web Services (SOAP, WSDL, XSD validation) · JAXB ·
Spring Data JPA / Hibernate · Spring JDBC (SimpleJdbcCall) · Oracle Database Free 23ai · PL/SQL · Flyway ·
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
docker compose up -d          # Oracle Database Free (ready in about a minute) + Redis
mvn spring-boot:run           # Flyway creates the tables, seed data and the procedures
```

Open the WSDL at http://localhost:8080/ws/subscribers.wsdl, or import it into SoapUI / Postman.
Sample requests are in `requests/`:

```bash
curl -s -H "Content-Type: text/xml" -d @requests/create-subscriber.xml http://localhost:8080/ws
curl -s -H "Content-Type: text/xml" -d @requests/activate-package.xml http://localhost:8080/ws
curl -s -H "Content-Type: text/xml" -d @requests/record-usage.xml http://localhost:8080/ws
curl -s http://localhost:8080/api/subscribers/94771234567/quota
```

## Tests

```bash
mvn test
```

- `SubscriberServiceTest`, `OracleErrorsTest`: unit tests with Mockito, including mapping of Oracle errors.
- `SubscriberEndpointTest`: SOAP tests against a real Oracle container (Testcontainers): XSD validation,
  faults, plan change and the audit table.
- `ChargingControllerTest` (REST) and `ChargingEndpointTest` (SOAP): online charging against Oracle:
  grants per bucket, reserve → commit, DENY + BLOCK, session errors.
- `IdempotencyTest`: retries against Oracle + Redis: replay, 400 / 422 / 409, keys per client, TTL,
  two copies of a request at the same moment run once, and the Oracle request-number check when
  Redis has lost the key or is unreachable (the Redis container is paused).
- `gateway-simulator`: `GatewaySimulatorTest` checks the simulator's requests against a mocked backend;
  `ChargingClientRetryTest` checks the retry policy (backoff, jitter, budget, circuit breaker, metrics);
  `SoapChargingClientTest` and `RestChargingClientTest` check each protocol client, and
  `ChargingClientsTest` the choice between them.
- `UsageEndpointTest`: usage charging against Oracle: app packages, add-ons, splitting across buckets,
  pay as you go, fair usage throttling, quota order and plan data refill.

All integration tests share one Oracle and one Redis container (`TestcontainersConfig`), and are
skipped when Docker isn't running.
