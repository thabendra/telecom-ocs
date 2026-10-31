# Site-to-site VPN and firewall (VP-1 to VP-4)

The network gateways (PGW / GGSN) sit in the **operator's** data centre; telecom-ocs runs in **ours**.
Their charging calls must never cross the open internet in the clear, and nobody but those gateways
should even be able to reach the charging port. This is a second layer under the application's own
security (mTLS + certificate-bound tokens, group 3): if one layer fails, the other still holds
("defense in depth").

```
 Operator site                                                         Our site
 10.20.0.0/24 (gateways)                                               10.40.0.0/24 (backend subnet)
 ┌────────────────┐    ┌──────────────┐  tunnel A (ISP 1)  ┌──────────────┐    ┌──────────────────────┐
 │ pgw-colombo-01 │────│ operator VPN │════════════════════│ our VPN      │────│ backend :8443 (mTLS) │
 │ pgw-kandy-01   │    │ gateway A, B │════════════════════│ gateway A, B │    │ token server :9443   │
 └────────────────┘    └──────────────┘  tunnel B (ISP 2)  └──────┬───────┘    ├──────────────────────┤
                                                                  │ firewall   │ Oracle, Redis:       │
                                                                  └────────────│ private, no route in │
                                                                               └──────────────────────┘
```

## VP-1: the IPsec tunnel

A site-to-site IPsec VPN with IKEv2. Both sides agree on these settings (the operator's network team
owns their end, we own ours):

| Setting | Value | Why |
|---|---|---|
| Key exchange | IKEv2 | IKEv1 is old: weaker, more round trips, no built-in liveness check |
| Authentication | Certificates (or a 32+ character pre-shared key as a fallback) | certificates can be revoked and rotated without both teams sharing a secret |
| IKE (phase 1) | AES-256-GCM, PRF SHA-384, DH group 20 (ECP-384) | authenticated encryption, strong curve |
| ESP (phase 2) | AES-256-GCM, PFS with DH group 20 | each child key is new, so one leaked key doesn't open older traffic |
| Lifetimes | IKE SA 8 h, child SA 1 h (rekey before expiry) | keys change often |
| Dead peer detection | every 10 s, peer down after 3 misses | a dead tunnel is noticed in ~30 s, then traffic moves to the other one |
| Traffic selectors | 10.20.0.0/24 ⇄ 10.40.0.0/24 only | the tunnel carries the gateway subnets to the backend subnet, nothing else |
| NAT traversal | on (UDP 4500) | works through any NAT on the way |

Only UDP 500 (IKE), UDP 4500 (NAT-T) and ESP between the two VPN gateways' public addresses are open
on the internet side.

## VP-2: two tunnels for failover

One tunnel is one cable that can be cut. There are two, each through **a different ISP** and ending on
**a different VPN gateway** on both sides, so no single box or line takes charging down.

| | Tunnel A | Tunnel B |
|---|---|---|
| Path | operator gateway A ⇄ our gateway A, ISP 1 | operator gateway B ⇄ our gateway B, ISP 2 |
| Role | active | standby (or both active, ECMP) |
| Routing | BGP over each tunnel (private ASNs); B advertised with a longer AS path | |

**How a failover goes:** dead peer detection or the BGP hold timer (e.g. 9 s) sees tunnel A is gone
→ the route through A is withdrawn → traffic uses B. During those seconds a gateway's calls time out;
its retry policy (section I: 3 retries, 1.5 s budget) and **fail open** keep subscribers online. Calls
that did reach us before the cut are safe to repeat, thanks to the `Idempotency-Key` (section H).

Both tunnels are monitored: a standby that is silently down is only found when it is needed. Alert when
either tunnel is down, not only when both are.

## VP-3: firewall allow list

Default deny, on our VPN gateways (traffic from the tunnel) and on the backend subnet's security
group. Only these flows exist:

| From | To | Port | Why |
|---|---|---|---|
| operator gateways 10.20.0.0/24, via the tunnel | backend | TCP 8443 | gateway API: charging, SOAP, push channel (mTLS) |
| operator gateways 10.20.0.0/24, via the tunnel | token server | TCP 9443 | gateway access tokens |
| internet / load balancer | backend | TCP 8080 (behind HTTPS on the balancer) | customers and staff (group 6) |
| monitoring subnet | backend | TCP 8081 | Prometheus, health (OB-2) |
| backend | Oracle | TCP 1521 | |
| backend | Redis | TCP 6379 (TLS) | |
| backend | Keycloak, Jaeger | TCP 443 / 4318 | staff tokens, traces |

Everything else is dropped and logged, in particular: the tunnel to 8080 / 8081 / Oracle / Redis, and
the internet to 8443. The backend also refuses the wrong port itself (`PortRuleFilter`, 404), so a
firewall mistake alone doesn't open the gateway API.

## VP-4: the local demo

`docker-compose.vpn.yml` builds the same picture on one laptop. **WireGuard** stands in for IPsec: a
small, modern VPN (one UDP port, Curve25519 keys, ChaCha20-Poly1305) that runs in any Docker VM, while
IPsec needs host-level setup. The ideas are the same: encrypted tunnel, each side's key, allowed
subnets, and a firewall behind it.

| Production | Local demo |
|---|---|
| operator gateway subnet 10.20.0.0/24 | `operator-net` 172.30.1.0/24 (the simulator) |
| the internet between the sites | `transit-net` 172.30.0.0/24 |
| our backend subnet 10.40.0.0/24 | `backend-net` 172.30.2.0/24, `internal` (no way out, nothing published) |
| IPsec, IKEv2, AES-256-GCM | WireGuard, UDP 51820, ChaCha20-Poly1305 + pre-shared key |
| traffic selectors | `allowed-ips`: each side only accepts the other site's subnet |
| firewall allow list | `iptables` on `wg-backend`: only 172.30.2.10:8443 and 172.30.2.11:9443 from `wg0` |
| two tunnels (VP-2) | one tunnel; failover is shown by the gateway's retries and fail open instead |

`scripts/vpn-check.sh` proves it from the operator side: the handshake happened, 8443 and 9443 are open
through the tunnel, 8080, 8081, Oracle and Redis are closed, and a full charging session works.

One difference on purpose: `wg-backend` hides the operator's addresses behind its own (NAT), because
Docker gives the backend container no route back to 172.30.1.0/24. In production the backend subnet
routes the gateway subnet to the VPN gateways instead, so the backend logs see the real gateway
addresses; the gateway's identity never depends on its address anyway, only on its certificate.
