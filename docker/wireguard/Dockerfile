# VP-4: a small VPN router for the local demo of the site-to-site link (docker-compose.vpn.yml).
# Uses the kernel's WireGuard (in Docker Desktop's VM, and in Linux kernels since 5.6).
FROM alpine:3.20
RUN apk add --no-cache wireguard-tools iptables iproute2 netcat-openbsd
COPY keys.sh peer.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/keys.sh /usr/local/bin/peer.sh
