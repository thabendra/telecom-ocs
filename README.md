# Telecom Subscriber Service

A small mobile operator backend: subscribers, plans, data packages (YouTube Unlimited, WhatsApp,
Extra 25GB) and **usage charging** that decides which package pays for which app's traffic and what
speed limit applies. The same business logic is exposed as a **contract first SOAP web service**
(Spring-WS) and a **REST API**, on an **Oracle Database** with **PL/SQL stored procedures**.

**Stack:** Java 21 · Spring Boot 3.4 · Spring Web Services (SOAP, WSDL, XSD validation) · JAXB ·
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
docker compose up -d          # Oracle Database Free, ready in about a minute
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
- `UsageEndpointTest`: usage charging against Oracle: app packages, add-ons, splitting across buckets,
  pay as you go, fair usage throttling, quota order and plan data refill.

The Oracle tests are skipped when Docker isn't running.
