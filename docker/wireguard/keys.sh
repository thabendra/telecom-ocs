#!/bin/sh
# VP-4: makes the tunnel's keys once, into the shared /keys volume: a key pair per side and one
# pre-shared key (an extra symmetric key on top of the key exchange). Private keys never leave the volume.
set -eu
umask 077
cd /keys
for side in operator backend; do
    [ -f "$side.key" ] || wg genkey > "$side.key"
    wg pubkey < "$side.key" > "$side.pub"
done
[ -f tunnel.psk ] || wg genpsk > tunnel.psk
echo "WireGuard keys ready in /keys"
