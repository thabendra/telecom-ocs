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
- `/oauth2/jwks` publishes the public keys, readable without a certificate (`client-auth: want`).

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
| `gateways.crl`, `crl/` | the revocation list, and what signs it (SE-6) |

In production the files come from a secret store (`TLS_DIR`, `TLS_KEYSTORE_PASSWORD`,
`TLS_TRUSTSTORE_PASSWORD`). Revoking one gateway and rotating certificates: see group 13 below.

## Revoking a gateway and rotating keys (group 13)

### Revoking one gateway (SE-6)

A gateway's private key was stolen, or the gateway was retired. Its certificate is valid for 825 days, so
it has to be turned off some other way:

```bash
scripts/revoke-gateway.sh pgw-colombo-01
```

This writes `certs/gateways.crl`, a standard certificate revocation list signed by our CA. The backend and the
token server read it every 30 s, so **within 30 s** this gateway gets `403` from the backend, even with a valid
token, and no new tokens. No other gateway is touched and nothing restarts.

| Case | What happens |
|---|---|
| A broken file, or one not signed by a CA we trust | Refused; the last good list stays, so a bad file can't un-revoke anybody |
| No list at all | Nobody is revoked, an error is logged and `GatewayRevocationListNotLoading` fires. Refusing every gateway would turn one missing file into a network outage |
| The gateway gets a new certificate | `scripts/rotate-cert.sh`: a new serial number, not on the list, so it works again |
| Locking it out for good | Also remove it from `telecom.gateways.allowed` and set `enabled: false` on its token server client, at the next deploy |

The check runs per request, not in the TLS handshake, because Tomcat only reads a CRL when the connector restarts.

### Rotating the signing key (SE-7)

The token server keeps its keys in a directory (`SIGNING_KEY_DIR`), so **a restart keeps the key**. Every 30 days
it rotates, in an order where no token is ever refused:

```
day 0     new key: published in /oauth2/jwks, not used yet
+15 min   it starts signing: every backend has read it by now (they read the keys every 10 minutes)
+25 min   the old key goes: the last token it signed (5 minutes) has expired
```

Every token names its key in `kid`. The backend's `SigningKeys` needs no change: it follows `/oauth2/jwks`. If a key
is stolen, delete its file: the token server stops publishing it within a minute, and the backends stop trusting
it at their next read (up to 10 minutes), which also refuses tokens it already signed.

### Rotating certificates (SE-7)

```bash
scripts/rotate-cert.sh backend        # or token-server, or a gateway's name
```

A new private key and certificate replace the old file in one move. The backend's gateway port and the token server
serve it **without a restart** (`reload-on-update`), 10 s after the file stops changing, and connections already
open stay up. A gateway reads its file when it starts, and then needs a new access token, as tokens are bound to the
certificate; the simulator already fetches a new one on a `401`.

**Changing the CA itself** takes three steps, each one safe on its own:

1. Add the new CA to every trust store, next to the old one. Services now trust both.
2. Issue new certificates from the new CA, with `rotate-cert.sh`, one at a time.
3. When nothing uses the old CA, remove it from the trust stores.

The backend's clients (token server keys, Redis) read their trust store at start, so step 1 and step 3 take a rolling
restart for those; with more than one copy that is still no downtime. Inside Docker on Windows or Mac, a file changed
on the host may not wake the file watcher, so try this on Linux or Kubernetes.

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

**Load shedding (RL-5).** Update and release report usage that has to be charged and free reserved
balance; a refused new session only tries again. So `reserved-slots` (3 of the 10) are kept for those two,
over REST and SOAP. Everything else, new sessions first of all, is refused with `503` + `Retry-After` at
once when it would need one of them, and counted in `ocs_load_shed_total`. With little traffic an update
simply takes any free slot. When Oracle is saturated, slots clear slowly, so an update may wait 150 ms for one
(`ongoing-wait`, under the gateway's 200 ms per try), and **while any update waits, no new work starts**. The gateway doesn't count a `503` with `Retry-After` towards its circuit
breaker: the backend is up, and an open breaker would stop the updates these slots are kept for.

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

## Customers and provider staff (group 6)

The public port (8080) serves people, not gateways. Two kinds of people log in, each with a token of
its own; the token's `iss` decides which checker reads it (`PublicApiSecurity`).

```
customer app ──POST /api/auth/otp──► SMS code ──POST /api/auth/otp/verify──► customer JWT (15 min)
admin portal ──Keycloak login (SSO, code + PKCE)────────────────────────────► staff JWT (roles)
```

**Customer login (CU-1).** `POST /api/auth/otp {"msisdn":"94771234567"}` sends a 6-digit code by
SMS (`SmsSender`; in dev `LoggingSmsSender` writes it to the log). `POST /api/auth/otp/verify
{"msisdn":..., "code":...}` answers `{accessToken, tokenType, expiresIn}`, a JWT this backend signs
itself (HS256, `sub` = the number, role `CUSTOMER`).

| Guard | How |
|---|---|
| Code not readable from Redis | Redis keeps `HMAC(msisdn:code)` for 5 minutes, never the code |
| Guessing | 5 wrong codes delete the code (`redis/otp-verify.lua`, one atomic step); a code works once |
| SMS flooding | one new code per number per minute (`429` + `Retry-After`), plus the per-IP limit |
| Finding customers | an unknown number gets the same `202`, just no SMS |
| Someone else's number | `/api/subscribers/{msisdn}/**` needs the token's own number (`403` otherwise) |

**Customer features (CU-2)**, with the customer token, for their own number only:
`GET /quota`, `POST /top-ups`, `POST /packages` (both with `Idempotency-Key`), `PUT /plan`,
`GET /usage?from=&to=&page=&size=` (usage history, newest first, 20 a page, at most 100).

**Staff login (PR-1).** Keycloak (`docker compose`, realm `keycloak/telecom-realm.json`) signs the
staff token; the backend checks it with Keycloak's public keys, `iss`, and `aud = telecom-ocs`, and
takes only `ADMIN` / `CARE_AGENT` from `realm_access.roles`. The caller's name is
`preferred_username`, which is recorded as "changed by".

| | ADMIN | CARE_AGENT | Customer |
|---|---|---|---|
| Read a subscriber | any | any | own number only |
| Register a SIM | yes | yes | no |
| Suspend / unsuspend (pushes BLOCK / UNBLOCK, group 5) | yes | yes | no |
| Catalog: packages, prices, speeds (`/api/admin/**`) | yes | no | no |

**Catalog (PR-2)**, ADMIN only:

```
GET  /api/admin/packages                 every package, on sale or not
POST /api/admin/packages                 create
PUT  /api/admin/packages/{code}/price    {"price": 790.00}
PUT  /api/admin/packages/{code}/speed    {"maxBandwidthKbps": 4096, "throttledBandwidthKbps": 512}
PUT  /api/admin/packages/{code}/status   {"active": false}       stop / start selling
GET  /api/admin/packages/{code}/prices   price history
PUT  /api/admin/plans/{code}/speed       {"maxBandwidthKbps": 20480}
```

V9 adds `active`, `updated_by` / `updated_at`, `status_changed_by` on the subscriber, and the table
`data_package_price`, filled by a trigger so no price change is missed. A package is never deleted;
one that is no longer sold can't be bought (`ACTIVATE_PACKAGE`, ORA-20010) but works until it expires.
A new speed is used from the next charging grant.

**Rate limits (PR-3)** use the group 4 Lua token bucket with other keys:

| Caller | Bucket | Limit |
|---|---|---|
| nobody logged in (login, catalog) | `ip:{address}` | 30 burst, 1 every 2 s |
| customer | `user:{msisdn}` | 60 burst, 1 per s |
| staff | `staff:{login}` | 200 burst, 10 per s |

A logged-in customer is counted by number, not IP: many phones share one IP behind the mobile
network's NAT. `X-Forwarded-For` is only believed from `trusted-proxies`.

## Audit, metrics and Redis security (group 7, part 1)

**Audit trail (OB-1).** `audit_event` (V10) gets a row for every charging decision and every command
to the gateways. The database writes them itself, in the same transaction as the change, so no code
path can skip one and a rolled-back request leaves none:

| Event | Written by | Carries |
|---|---|---|
| `GRANT` / `DENY` | the CHARGING package (`audit_decision`) | gateway, session, requested / granted MB, bucket, speed |
| `BLOCK` / `UNBLOCK` | trigger on `gateway_command` insert | target gateway, reason, speed, command id |
| `ACKED` / `FAILED` | trigger on `gateway_command` status | the gateway (ACKED) or `system` |
| `SUSPEND` / `UNSUSPEND` | trigger on `subscriber` status | the staff login (`status_changed_by`, V9) |

The PL/SQL learns which gateway is calling from the Oracle client identifier: `AuditContext` sets it
with `DBMS_SESSION.SET_IDENTIFIER` inside the charging transaction, the package reads
`SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')`, and it is cleared again before the pooled connection
serves anyone else. The table is append-only (a trigger refuses `UPDATE` / `DELETE`, ORA-20011) and
partitioned by month (interval partitioning on `event_time`, UTC), so old months are dropped whole.
Staff read it at `GET /api/subscribers/{msisdn}/audit?from=&to=&page=&size=` (newest first).

**Metrics (OB-2)** are on a third, internal port: `http://localhost:8081/actuator/prometheus` and
`/actuator/health` (Oracle + Redis). No login there, so it must only be reachable from the
monitoring network; nothing of it is served on 8443 or 8080.

| Metric | Shows |
|---|---|
| `http_server_requests_seconds_*` | requests per second, and p99 latency from the histogram buckets |
| `ocs_charging_decisions_total{operation, result, gateway}` | grants and denies per gateway |
| `ocs_ratelimit_rejected_total{api, caller}` | `429`s: gateway, ip, customer, staff |
| `ocs_bulkhead_rejected_total`, `ocs_bulkhead_free_slots` | `503`s and free database slots |
| `ocs_push_commands{status}` | gateway commands PENDING / SENT / FAILED |
| `ocs_otp_sent_total`, `ocs_otp_checked_total{result}` | logins; many `wrong` = someone guessing |

`docker compose` also starts Prometheus (`localhost:9090`) and Grafana (`localhost:3001`,
admin / admin) with the "Telecom OCS" dashboard (`monitoring/`).

**Redis is private (RD-9).** The compose Redis has no plain port, only TLS (certificate from the
dev CA), and the users in `docker/redis/users.acl`: `default` is off, the backend's own user
`telecom-ocs` may only touch its keys (`idem:*`, `ratelimit:*`, `otp:*`) and no dangerous commands
(`FLUSHALL`, `KEYS`, `CONFIG`, ...), `ops` is for an operator with `redis-cli`. It is published on
`127.0.0.1` only; in production it sits in a private subnet only the backend can reach. The backend
connects with `spring.data.redis.username` / `password` and `ssl.bundle: redis-client`.

```bash
redis-cli --tls --cacert certs/ca.pem --user ops --pass dev-only-ops-password ping
```

## Tracing, Redis failover and the VPN (group 7, part 2)

**Tracing (OB-3).** OpenTelemetry through Micrometer Tracing. The gateway simulator makes each charging
session one trace and sends the W3C `traceparent` header; the backend continues it, adds a span for the
CHARGING procedure in Oracle (`ocs.charging.oracle`), and sends everything over OTLP to Jaeger
(`docker compose` starts it; UI at `http://localhost:16686`). Every answer carries `X-Trace-Id`, error
bodies have a `traceId`, and log lines show `[telecom-ocs,<traceId>,<spanId>]`, so one id finds the
gateway's call, the backend's work and its logs. The simulator prints `TRACE <id>` with a Jaeger link.
`TRACING_SAMPLING` (default 1.0) sets how many requests are traced; in production a few percent.

**Redis failover (RD-7).** Profile `redis-sentinel` (`application-redis-sentinel.yml`) points the backend
at three Sentinels instead of one Redis; they watch the primary and promote a replica when two of them
agree it is down. `docker-compose.ha.yml` runs it: primary, two replicas, three Sentinels (all TLS + ACL,
replication with its own `replica` user, Sentinel with its own `sentinel` user) and the backend in a
container.

```bash
mvn -DskipTests package
docker compose -f docker-compose.ha.yml up -d --build
docker compose -f docker-compose.ha.yml pause redis     # the primary hangs, like a crash
curl -s localhost:8081/actuator/health                  # Redis UP again a few seconds later
```

The backend follows the switch by itself (`RedisSentinelConfig`: Lettuce in master/replica mode
listens to the Sentinels' `+switch-master`). Use `pause`, not `stop`: a stopped container loses its
DNS name, and Sentinel stops failing over (TILT mode) while a name it watches doesn't resolve.
A replica can be a moment behind, so a failover may lose the last few writes; Oracle stays the source
of truth (the `charging_request` check catches a retried charge whose idempotency key was lost).

**VPN and firewall (VP-1 to VP-4).** `docs/vpn-design.md` is the production design: site-to-site IPsec
(IKEv2, AES-256-GCM, DH group 20, PFS), two tunnels over two ISPs with BGP failover, and the firewall
allow list (gateways → 8443 and 9443 only). `docker-compose.vpn.yml` builds it locally with WireGuard:

```
operator-net ── wg-operator ═══ WireGuard tunnel (transit-net) ═══ wg-backend (firewall) ── backend-net
 (simulator)     10.99.0.1                                          10.99.0.2                (backend, token
                                                                                              server, Oracle, Redis)
```

```bash
mvn -DskipTests package && mvn -f token-server/pom.xml -DskipTests package
mvn -f gateway-simulator/pom.xml -DskipTests package
docker compose -f docker-compose.vpn.yml up -d --build
scripts/vpn-check.sh
```

`vpn-check.sh` checks from the operator side: the WireGuard handshake, 8443 and 9443 open through the
tunnel, 8080, 8081, Oracle and Redis closed, and one full charging session by the simulator.

## Session ownership, expiry and report-only updates (group 8)

**A session belongs to its gateway (CH-7).** `charging_session.gateway` (V11) is the certificate name of
the gateway that started it. An update or release from any other gateway gets `404`, with the same
message as a session that doesn't exist, so a gateway can't even find out which session ids are in use.
The CHARGING package checks it under the subscriber lock (ORA-20012), before the request number is
used up or anything is charged.

**Session cache (RD-4).** `session:{id}` -> `gateway|msisdn` in Redis, kept as long as the session can
live. A call from the wrong gateway is refused from the cache, without touching Oracle. A missing entry
(Redis down, key gone) just means Oracle checks; the cache never allows anything on its own.

**Forgotten sessions expire (CH-8).** A gateway that crashes never sends the release, and the grant's
quota would stay held forever. `CHARGING.EXPIRE_STALE_SESSIONS` closes sessions with no update for
65 minutes (the 1 h grant validity + 5 min): the held quota goes back to the bucket, nothing is charged
(no usage was reported), `end_reason = EXPIRED`, and the audit gets an `EXPIRED` row. The backend calls
it every minute (`telecom.charging.expire-sessions`); several copies may, because the procedure locks
with `SKIP LOCKED` and never waits behind live charging.

**Report-only update.** `requestedMb: 0` means "here is my usage, I need nothing more now": the usage is
charged and the answer is `REPORTED` (REST and SOAP), with no grant and **no BLOCK**. Before group 8 it
answered DENY and blocked the user on every gateway.

`charging_session.end_reason` says how each session ended: `RELEASE`, `DENY` (nothing left at start) or
`EXPIRED`.

## Grant sizes and heavy users (group 9)

**Grant size (CH-5).** `requestedMb` is optional in start and update (REST and SOAP). Left out, the
backend picks it (`GrantPolicy`):

| Case | Grant |
|---|---|
| the gateway asked for an amount | that amount, as before (`0` in an update = `REPORTED`) |
| left to us, normal user | **100 MB** |
| left to us, heavy user (RL-3) | **250 MB**: half as many updates for the same traffic |
| little left in the bucket | what is left: a smaller final grant (`reserve` takes `LEAST(asked, left)`) |

**Heavy users (RL-3).** `redis/usage-window.lua` keeps each subscriber's usage reports in a sorted set
`usage:{msisdn}` scored by arrival time, and sums the last 15 minutes in one atomic step: a true
sliding window, no bucket edge for a burst to hide behind. 2 GB or more in that window = heavy: bigger
grants, `ocs_heavy_users_flagged_total` counts the moment someone crosses the line (once), and the log
says so with the number masked (`9477123****`). Being heavy **never blocks**; the quota decides that, as
for everybody. Without Redis nobody looks heavy, which only means normal grants.

**Alerts.** `monitoring/alerts.yml` (loaded by Prometheus): new heavy users, gateway commands that
failed, many expired sessions, and the database bulkhead refusing requests.

## Circuit breakers in the backend (group 11)

A circuit breaker lives in the **caller**: it counts the results of the calls it makes and stops calling
when the other side is in trouble. The gateway has one towards this backend (section I); these are the
backend's own, towards what **it** calls. Resilience4j keeps the state; our code asks permission before a
call (`tryAcquirePermission`) and reports the result (`onSuccess` / `onError`).

| | Redis (CB-1) | Signing keys (CB-2), one per server |
|---|---|---|
| **Calls** | many per second | rare |
| **Opens when** | In the last 10 s, 50 % of the calls failed or 80 % were slower than 300 ms (at least 10 calls) | 3 failures in a row |
| **Stays open** | 5 s | 30 s |
| **Half-open** | 3 test calls | 1 test call |
| **Counts as failure** | connection errors, timeouts (an error answer like `NOPERM` means Redis is alive) | no answer, 5xx (a 404 means the server is alive) |
| **While open** | each caller's fallback, at once | the keys already in memory |

**Redis (CB-1).** Without it, a hanging Redis costs each request 500 ms per Redis call, far above a
gateway's 200 ms limit. `RedisBreaker` wraps every Redis call. When open it throws
`RedisCircuitOpenException`, a `DataAccessException`, so the fallbacks that already existed take over
without waiting:

| Caller | Fallback |
|---|---|
| Rate limit | this copy's local bucket |
| Idempotency | runs without Redis; Oracle's request number still stops a double charge |
| Session cache | Oracle checks the owner |
| Usage window | nobody looks heavy: normal grants |
| Customer login (OTP) | none, the codes live in Redis: `503` + `Retry-After` |

**Signing keys (CB-2).** Spring's default key cache forgets its keys after 15 minutes, so a longer outage
of the token server would refuse every token although the keys hadn't changed. `SigningKeys` keeps the
last good keys. It fetches when it has none yet, when a token has an unknown key id (at most once per
30 s, so made-up key ids can't flood that server), and every 10 minutes, so a key removed at the server
stops being trusted. A failed fetch keeps the keys. The token server and Keycloak have one breaker each.

**Seeing them (CB-3).** `ocs_circuit_state{name, state}` is 1 for the current state,
`ocs_circuit_rejected_total{name}` counts refused calls, `/actuator/health` shows `circuit`, Grafana has a
panel, and the `CircuitBreakerOpen` alert fires after 2 minutes open. Every change of state is logged
once. The health entry stays UP with a breaker open: the copy is working on its fallback, and restarting
it would help nobody.

## Caching and Redis eviction (group 12)

Customers read the same things again and again: the plans, the packages, their own profile. These go
through a **cache-aside** cache (`ReadCache`): read Redis; on a miss read Oracle and store the result.

**What is cached, and what never is (CA-1).** `GET /api/plans`, `GET /api/packages`, and the stable part of
`GET /api/subscribers/{msisdn}`: name, NIC, status and plan code. The **balance is always read from Oracle**,
because every charge changes it, and so are quota and usage. A profile takes its plan from the cached plan
list, so a new plan speed reaches every profile by deleting one key. Charging and SOAP never read this cache.

**Two Redis servers (RD-10).** A full Redis has to do something, and the right thing depends on the key:

| | Data Redis (`redis`) | Cache Redis (`redis-cache`) |
|---|---|---|
| **Holds** | Idempotency keys, rate limit buckets, login codes, session owners | `cache:*` |
| **When full** | `noeviction`: writes fail, no key vanishes | `allkeys-lru`: the oldest key goes |
| **A lost key means** | A retry could run twice, a login code stops working | One extra read of Oracle |
| **User** | `telecom-ocs` | `telecom-cache`, only `cache:*` |
| **Breaker** | `redis` | `redis-cache`, so a cache outage doesn't touch idempotency |
| **Saved to disk, replicas** | As before | None: it can always be rebuilt |

Alerts: `RedisDataMemoryHigh` (over 80 %, well before writes fail), `RedisDataKeysEvicted` (must never
fire) and `RedisCacheEvictingALot` (the cache is too small). The backend reads the numbers with `INFO` every
30 s (`RedisMemoryMonitor`), so a hanging Redis can't hold up `/actuator/prometheus`.

**Staff changes reach customers at once (CA-2).** The backend writes Oracle and, **after the commit**, deletes
the key; a rollback deletes nothing. It deletes rather than updates, because an update can store an old value.
One second later it deletes once more: a read that loaded the old row just before the commit may store it just
after the first delete.

**No spike on Oracle (CA-3, CA-4).** Each value lives 10 minutes +/- 20 %, so keys stored together don't expire
together. When a key is missing, one request reloads it under a short lock (`SET NX`, 3 s). The others check
the cache for up to 200 ms, then read Oracle themselves without storing.

**Redis down (CA-5).** Any Redis error, or the breaker open: straight to Oracle, behind the database bulkhead
as always. If a delete can't reach Redis, the old value lives until its TTL, so **12 minutes is the longest a
customer can see an old price**. Staff screens read Oracle, and charging uses the real price whatever a
customer's screen shows.

`ocs_cache_reads_total{name, result}` counts each answer (`hit`, `miss`, `waited`, `wait-gave-up`,
`redis-down`). The certificate needs the name `redis-cache`: run `scripts/generate-dev-certs.sh` once more.

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
| Circuit breaker | 5 failed tries in a row (network errors or 5xx, but not a `503` with `Retry-After`) open it for 10 s: calls fail open at once |
| Metrics | `gateway.retries`, `gateway.retries.succeeded`, `gateway.retries.exhausted`, `gateway.circuit.rejected` (printed at the end of a run) |

When the retries, the time budget or the open circuit give up, the simulator **fails open**: it
lets the user's traffic through instead of blocking a paying customer because the backend is slow.

**The failure contract (RL-6).** Fail open has limits, per user (`simulator.fail-open.*`):

| Rule | Behaviour |
|---|---|
| Limits | At most **5 minutes** from the first moment, and at most **50 MB** |
| Limit reached | The user's traffic is blocked until the backend answers |
| Meanwhile | The same request (same key, same number) is tried again every 2 s, so it can't charge twice |
| Backend back | The session goes on, and the MB used meanwhile are charged in a short session of their own (start + release): Oracle charges at most what is reserved, so adding them to the next report would lose them |
| Can't be charged | No balance: the user is blocked, and the MB are lost. That loss is what the limits keep small |

The worst case is known in advance: 50 MB per user per outage. The simulator is a one-shot run, so it
ends at the 5-minute limit and logs what is still owed; a real gateway keeps blocking, and keeps the
owed MB on disk so a restart doesn't lose them.

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

| Method | Path | Who |
|---|---|---|
| POST | `/api/auth/otp`, `/api/auth/otp/verify` | anyone |
| GET | `/api/plans`, `/api/packages` | anyone |
| POST | `/api/subscribers` | staff |
| GET | `/api/subscribers/{msisdn}` | staff, the customer |
| POST | `/api/subscribers/{msisdn}/top-ups` | staff, the customer |
| PUT | `/api/subscribers/{msisdn}/plan` | staff, the customer |
| POST | `/api/subscribers/{msisdn}/packages` | staff, the customer |
| GET | `/api/subscribers/{msisdn}/quota` | staff, the customer |
| GET | `/api/subscribers/{msisdn}/usage` | staff, the customer |
| PUT / DELETE | `/api/subscribers/{msisdn}/suspension` | staff |
| POST | `/api/subscribers/{msisdn}/usage` | ADMIN (test tool) |
| any | `/api/admin/**` | ADMIN |

## Run locally

```bash
scripts/generate-dev-certs.sh          # test certificates (once)
docker compose up -d                   # Oracle (ready in about a minute), Redis (TLS), Keycloak, Prometheus, Grafana, Jaeger
mvn -f token-server/pom.xml spring-boot:run   # token server on 9443
mvn spring-boot:run                    # gateway port 8443 (mTLS + token), public port 8080, management port 8081
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
```

The public port needs a login (group 6). A customer, with the code from the backend's log:

```bash
curl -s -H "Content-Type: application/json" -d '{"msisdn":"94771234567"}' http://localhost:8080/api/auth/otp
CUSTOMER=$(curl -s -H "Content-Type: application/json" -d '{"msisdn":"94771234567","code":"123456"}' \
        http://localhost:8080/api/auth/otp/verify | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
curl -s -H "Authorization: Bearer $CUSTOMER" http://localhost:8080/api/subscribers/94771234567/quota
```

A staff member (dev users `admin1` / `admin1` and `agent1` / `agent1` in the Keycloak realm; the
password grant is enabled only for this kind of local testing, the admin portal uses code + PKCE):

```bash
STAFF=$(curl -s -d grant_type=password -d client_id=admin-portal -d username=admin1 -d password=admin1 \
        http://localhost:8180/realms/telecom/protocol/openid-connect/token | sed 's/.*"access_token":"\([^"]*\)".*/\1/')
curl -s -H "Authorization: Bearer $STAFF" http://localhost:8080/api/admin/packages
```

## Load tests (group 14)

Three [k6](https://k6.io) tests, each over real mTLS with a real token, against real Oracle and Redis:

```bash
scripts/load-test.sh charging      # TE-2, CH-9: p99 of every charging call under 50 ms at the target load
scripts/load-test.sh rate-limit    # TE-2, RL-1: over its limit a gateway gets 429; the other gateway isn't touched
scripts/load-test.sh overload      # RL-5: new sessions are refused, updates keep working
```

Each one starts `docker-compose.load.yml`, fills the database with 2000 subscribers, runs, and stops. The exit code is
0 only when its limits held, and the summary goes to `load/results/`.

| Test | Load | Fails when |
|---|---|---|
| `charging` | `TARGET_RPS` calls per second (300), as start + update + release, for 2 minutes after a 30 s warm-up | p99 of all calls, or of any one kind, is over `P99_MS` (50); over 0.1 % fail; k6 couldn't keep the rate |
| `rate-limit` | A limit of burst 100 + 50 per second for this run, as one computer can't reach the default 1000 per second. Colombo 150 per second, Kandy 20 per second, for 60 s | Colombo passes more than burst + refill (+5 %) or under 90 % of the refill; no `429`; Kandy gets any `429` or over 1 % fail |
| `overload` | 900 new sessions per second and 50 updates per second, for 60 s | No new session was refused (so it wasn't an overload), or over 1 % of updates got `503` |

**The target load is a choice, not a fact.** CH-9 says "at the target load", and no number was ever agreed, so 300 calls
per second is a starting point for one laptop. A real operator would size it: subscribers online x how often each
reports. **These tests measure one computer** running k6, the backend and Oracle together, so a result says how this
setup did, not what production will do. A failed `charging` run on a laptop may mean the laptop is the limit; `DB_POOL_SIZE`
and `TARGET_RPS` show where the limit moves.

## Kubernetes (group 15)

`k8s/` holds the files for a cluster. **Nothing has been installed**; they are checked without a cluster:

```bash
scripts/k8s-check.sh    # kustomize + kubeconform in containers, and the K8-3 rule; needs only Docker
```

| | |
|---|---|
| `k8s/base` | Deployments, Services, Ingress, autoscaler, NetworkPolicies, ConfigMaps, PodDisruptionBudgets, placeholder Secrets |
| `k8s/overlays/dev` | One copy of each on a local cluster, node ports |
| `k8s/overlays/prod` | 3 to 6 copies, internal load balancers, secrets from a secret store |

**The three doors (K8-2).**

| Port | Goes through | Why |
|---|---|---|
| 8443, 9443 | A plain TCP `LoadBalancer` | **TLS passes through to the pod**: the certificate check (SE-1) and the token's binding (SE-4) need the gateway's own handshake. An Ingress would end TLS, and the backend would never see a certificate |
| 8080 | An `Ingress` with HTTPS, only `/api` | Customers and staff log in with a token, so TLS can end at the edge |
| 8081 | `ClusterIP`, monitoring only | It has no login |

**Lost Oracle: no traffic, no restart (K8-1).** The liveness probe only asks if the JVM is stuck. Readiness also asks about
Oracle, not Redis, which has fallbacks. If liveness looked at Oracle, a database outage would restart every copy, and
add an outage of our own to it. On shutdown a pod keeps answering for 10 s while the load balancer catches up (`preStop`), then
finishes what is running (`server.shutdown: graceful`, up to 20 s).

**How many copies (K8-3).** More copies don't make Oracle bigger, so `DB_POOL_SIZE x maxReplicas` must fit in Oracle's
sessions, and `MAX_INSTANCES` must equal `maxReplicas` (with Redis down each copy allows `limit / MAX_INSTANCES`).
`k8s-check.sh` fails on either. Group 14 showed Oracle is the limit: past it, more copies only queue.

**Who reaches what (K8-4).** Everything is refused unless allowed: only the VPN reaches 8443 and 9443, only the ingress
reaches 8080, only monitoring reaches 8081, and pods may only call Oracle, Redis, Keycloak, Jaeger and the token server.
This needs a network plugin that enforces it: without one the policies are silently ignored.

**Worth knowing.**

- The token server needs a `ReadWriteMany` volume for its signing keys, as its copies share them (SE-7).
- `TELECOM_PUBLICRATELIMIT_TRUSTEDPROXIES_0` must be the ingress pods' range. Too narrow, and all customers share one
  per-IP bucket. Too wide, and a caller can pick an address with `X-Forwarded-For`.
- `gateways.crl` and new certificates reach a pod through its Secret, which takes up to about a minute, on top of the 30 s
  read: a revocation works within about 2 minutes here.
- Ranges, host names and registry names are examples. The load balancer annotations in `prod` are for AWS.

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
  for the in-memory bucket, the fallback share and overrides, and the `503` bulkhead with its load
  shedding (RL-5). `FailOpenTest` in the simulator: the limits, the same request sent again, the usage
  charged afterwards (RL-6).
- `RevocationListTest`, `RevokedGatewayTest`: real CRL files: one gateway refused and the other not, also over real
  mTLS; a broken or foreign list can't un-revoke. In the token server: `SigningKeyStoreTest` (the rotation steps with
  a moved clock), `CertificateReloadTest` (a new key store served with no restart), and in `TokenServerTest` the
  key named in the token, a revoked gateway and a disabled client.
- `HealthProbesTest`: liveness looks only at the JVM, readiness also at Oracle (K8-1).
- `CacheTest`: the cache against Oracle + Redis: the second read is a hit, a staff change shows at once, the
  balance is live, a rollback deletes nothing, the delayed delete, one reload for many requests, Redis paused.
  `RedisEvictionTest`: real Redis servers with the two policies, filled up: the cache evicts, data refuses.
- `IdempotencyTest`: retries against Oracle + Redis: replay, 400 / 422 / 409, keys per client, TTL,
  two copies of a request at the same moment run once, and the Oracle request-number check when
  Redis has lost the key or is unreachable (the Redis container is paused).
- `gateway-simulator`: `GatewaySimulatorTest` checks the simulator's requests against a mocked backend;
  `ChargingClientRetryTest` checks the retry policy (backoff, jitter, budget, circuit breaker, metrics);
  `SoapChargingClientTest` and `RestChargingClientTest` check each protocol client,
  `ChargingClientsTest` the choice between them, `CommandPollerTest` the push channel (apply once,
  ack, retry), `GatewayHttpTest` the HTTPS client with the
  gateway certificate, and `TokenClientTest` getting, caching and refreshing access tokens.
- `CustomerLoginTest`: the SMS code login against Redis + Oracle with real customer tokens: own
  number only (`403` for another), no staff APIs, `401` without a token or with a forged one, a code
  works once, 5 wrong codes delete it, only its HMAC is in Redis, one code a minute, unknown numbers
  get no SMS; top-up, package and the paged usage history with the customer's token.
  `CustomerTokensTest` (unit): claims, role, foreign key, expiry, short secret.
- `StaffAccessTest`: ADMIN / CARE_AGENT rules, creating, pricing (history by the V9 trigger), speeds,
  stopping a sale (hidden, can't be bought), a plan speed used by UNBLOCK, and who suspended.
  `StaffTokensTest` (unit): Keycloak roles and name, wrong audience or realm. MockMvc tests log in
  with `TestUsers`; login SMS are caught by `TestSms`.
- `PublicRateLimitTest`: `429` + `Retry-After` per IP, per customer and per staff login.
  `ClientAddressTest` (unit): when `X-Forwarded-For` is believed.
- `AuditTest`: the audit trail against Oracle: GRANT with the calling gateway, DENY and a BLOCK per
  gateway, one ACKED for two acks, SUSPEND / UNSUSPEND with the staff login, no row for a refused
  request, `UPDATE` / `DELETE` refused, and the staff-only read API.
- `MetricsTest`: real HTTP to the management port: the business counters and the latency histogram
  in the Prometheus output, health with Oracle and Redis, and no actuator on 8443 or 8080.
- `RedisSecurityTest`: its own Redis with the compose TLS + ACL setup: the backend's Lua scripts and
  health check work, `FLUSHALL` / `KEYS` / other keys are `NOPERM`, no user, a wrong password or plain
  text gets nothing. (The shared test Redis is a plain container.)
- `RedisSentinelTest`: a primary, a replica and three Sentinels like `docker-compose.ha.yml` (TLS + ACL);
  the backend's Sentinel settings find the primary, and after it is stopped they find the promoted
  replica with the data written before.
- `TraceIdFilterTest` and `ErrorResponsesTest` (unit): `X-Trace-Id` and `traceId` in error bodies, and
  nothing breaks without tracing. In the simulator, `GatewaySimulatorTest` checks a session is one
  observation (`gateway.session`).
- The VPN demo is checked by `scripts/vpn-check.sh` against the running `docker-compose.vpn.yml`.
- `SessionOwnershipTest`: another gateway's update and release get `404` (from the cache, and from
  Oracle when the cache has no entry), the same answer as an unknown session, without using up a request
  number; the cache entry and its lifetime; `end_reason`; and a 0 MB update is `REPORTED`, charged, with
  no BLOCK.
- `SessionExpiryTest`: a quiet session gives its quota back and charges nothing, an active one or one
  quiet for 30 minutes is kept (the time zone check), an expired one can't be updated, and a second run
  finds nothing new.
- `GrantSizeTest`: 100 MB when the size is left to us, 250 MB for a heavy user, an asked amount
  honoured, a smaller final grant, the report that crosses the line already getting the big grant, never
  a block, the alert counted once, old usage sliding out, release usage counting. `GrantPolicyTest`
  (unit) checks the rules with a faked window, also with Redis down; `ChargingEndpointTest` a SOAP start
  without `requestedMb`.
- `RedisCircuitTest`: real Redis paused: the breaker opens, charging goes on without the timeouts, login
  says 503, the health page shows it; Redis back: test calls close it. `RedisBreakerTest` (unit): what
  counts as a failure, slow calls, half-open both ways, metrics. `SigningKeysTest`: the last good keys
  during an outage (also with the real decoder over HTTP), made-up key ids, a removed key, the breaker.
- `UsageEndpointTest`: usage charging against Oracle: app packages, add-ons, splitting across buckets,
  pay as you go, fair usage throttling, quota order and plan data refill.

All integration tests share one Oracle and one Redis container (`@IntegrationTest`, `TestcontainersConfig`), and are
skipped when Docker isn't running.
