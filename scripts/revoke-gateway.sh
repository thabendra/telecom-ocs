#!/usr/bin/env bash
# SE-6: revokes one gateway's current certificate. The backend and the token server read certs/gateways.crl
# again every 30 s, so within that time this gateway gets no token and no answer, and nothing restarts.
# Its name stays usable: rotate-cert.sh gives it a new certificate, with a new serial number.
#
# Usage: scripts/revoke-gateway.sh <gateway> [certificates directory]   (from the project root)
# To lock a gateway out for good, also remove it from telecom.gateways.allowed, and set enabled: false on its
# client in the token server (next deploy, one copy at a time).
set -euo pipefail

NAME="${1:?usage: revoke-gateway.sh <gateway> [certificates directory]}"
DIR="${2:-certs}"
PASS="changeit"
source "$(dirname "$0")/dev-certs-lib.sh"

[ -f "$DIR/$NAME.pem" ] || { echo "No certificate $DIR/$NAME.pem" >&2; exit 1; }

revoke "$DIR/crl" "$NAME"
write_crl "$DIR/crl" "$DIR/gateways.crl"
echo "Revoked $NAME (serial $(openssl x509 -in "$DIR/$NAME.pem" -noout -serial | cut -d= -f2)); list: $DIR/gateways.crl"
