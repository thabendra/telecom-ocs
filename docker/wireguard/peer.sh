#!/bin/sh
# VP-4: one end of the site-to-site tunnel.
#   SIDE / PEER       operator | backend      whose keys are mine / the other side's
#   TUNNEL_IP         my address inside the tunnel (10.99.0.1 or .2)
#   PEER_TUNNEL_IP    the other side's tunnel address
#   REMOTE_SUBNET     the other site's subnet, reached through the tunnel
#   PEER_ENDPOINT     host:port of the other side (only the operator side dials; the backend side waits)
# The backend side also is the firewall (VP-3): from the tunnel only the gateway API and the token
# server, nothing else of the backend subnet.
set -eu

ip link add wg0 type wireguard
wg set wg0 listen-port 51820 private-key "/keys/$SIDE.key" \
    peer "$(cat "/keys/$PEER.pub")" preshared-key /keys/tunnel.psk \
    allowed-ips "$PEER_TUNNEL_IP/32,$REMOTE_SUBNET" persistent-keepalive 25 \
    ${PEER_ENDPOINT:+endpoint "$PEER_ENDPOINT"}
ip address add "$TUNNEL_IP/30" dev wg0
ip link set wg0 up
ip route add "$REMOTE_SUBNET" dev wg0

if [ "$SIDE" = backend ]; then
    # VP-3 allow list: drop everything that isn't asked for
    iptables -P FORWARD DROP
    iptables -A FORWARD -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
    iptables -A FORWARD -i wg0 -p tcp -d "$BACKEND_IP" --dport 8443 -j ACCEPT        # gateway API (mTLS)
    iptables -A FORWARD -i wg0 -p tcp -d "$TOKEN_SERVER_IP" --dport 9443 -j ACCEPT   # gateway tokens
    # the tunnel may not talk to this router itself either (only WireGuard comes in, on the outside)
    iptables -A INPUT -i wg0 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
    iptables -A INPUT -i wg0 -j DROP
    # the backend answers this router, which sends it back through the tunnel
    iptables -t nat -A POSTROUTING -d "$LOCAL_SUBNET" -j MASQUERADE
fi

echo "tunnel up: $TUNNEL_IP -> $REMOTE_SUBNET"
exec sleep infinity
