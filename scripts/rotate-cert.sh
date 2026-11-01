#!/usr/bin/env bash
# SE-7: a new private key and certificate for one service or gateway, same name, same CA. The file is
# replaced in one move: the backend and the token server notice and serve it without a restart. A gateway
# reads its file when it starts, and needs a new access token, as a token is bound to the certificate.
#
# Usage: scripts/rotate-cert.sh <backend|token-server|gateway name> [certificates directory]
set -euo pipefail

NAME="${1:?usage: rotate-cert.sh <backend|token-server|gateway name> [certificates directory]}"
DIR="${2:-certs}"
PASS="changeit"
source "$(dirname "$0")/dev-certs-lib.sh"

EXTRA=(-validity 825)
case "$NAME" in
  backend)
    SUBJECT="CN=localhost, O=Telecom Dev"
    EXT="SAN=dns:localhost,ip:127.0.0.1,dns:backend,ip:172.30.2.10"
    EXTRA+=(-ext EKU=serverAuth) ;;
  token-server)
    SUBJECT="CN=localhost, O=Telecom Dev Token Server"
    EXT="SAN=dns:localhost,ip:127.0.0.1,dns:token-server,ip:172.30.2.11"
    EXTRA+=(-ext EKU=serverAuth) ;;
  pgw-*)
    SUBJECT="CN=$NAME, O=Telecom Dev"
    EXT="EKU=clientAuth" ;;
  *)
    echo "Not a service or gateway: $NAME" >&2; exit 1 ;;
esac

OUT="$(mktemp -d "$DIR/rotate.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
issue "$NAME" "$SUBJECT" "$EXT" ca "${EXTRA[@]}"

case "$NAME" in pgw-*)
  gateway_key "$OUT" "$NAME"
  mv "$OUT/$NAME-key.pem" "$DIR/$NAME-key.pem" ;;
esac
mv "$OUT/$NAME.pem" "$DIR/$NAME.pem"
mv "$OUT/$NAME.p12" "$DIR/$NAME.p12"
echo "New certificate for $NAME: serial $(openssl x509 -in "$DIR/$NAME.pem" -noout -serial | cut -d= -f2)"
