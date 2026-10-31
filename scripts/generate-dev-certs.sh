#!/usr/bin/env bash
# Creates the TEST-ONLY certificates for the gateway mTLS port (SE-1) in ./certs.
# Never use these in production: the CA key sits next to everything else and the password is public.
#
#   certs/ca.p12 / ca.pem                 "Telecom Dev CA": signs every gateway and the backend
#   certs/gateway-ca-truststore.p12        trust store with only that CA (backend and gateways use it)
#   certs/backend.p12                      backend server certificate for localhost / 127.0.0.1
#   certs/token-server.p12                 token server (SE-2) certificate for localhost / 127.0.0.1
#   certs/redis.pem + redis-key.pem        Redis TLS (RD-9) certificate for localhost / redis, PEM for redis-server
#   certs/pgw-colombo-01.p12 (.pem)        gateway client certificates
#   certs/pgw-kandy-01.p12   (.pem)
#   certs/pgw-unknown-99.p12 (.pem)        signed by our CA, but not in telecom.gateways.allowed (403)
#   certs/pgw-expired.p12                  signed by our CA, expired (handshake fails)
#   certs/stranger.p12                     CN=pgw-colombo-01 but signed by another CA (handshake fails)
#
# Usage: scripts/generate-dev-certs.sh   (run from the project root; password: changeit)
set -euo pipefail

DIR="${1:-certs}"
PASS="changeit"
KEY=(-keyalg EC -groupname secp256r1 -storetype PKCS12 -storepass "$PASS")

rm -rf "$DIR"
mkdir -p "$DIR"

# make_ca <file> <name>
make_ca() {
  keytool -genkeypair -alias ca -dname "CN=$2" -ext bc:c -validity 3650 -keystore "$DIR/$1.p12" "${KEY[@]}"
  keytool -exportcert -alias ca -rfc -keystore "$DIR/$1.p12" -storepass "$PASS" -file "$DIR/$1.pem"
}

# issue <file> <subject> <extensions> <ca file> [extra gencert options...]
issue() {
  local name="$1" subject="$2" ext="$3" ca="$4"
  shift 4
  keytool -genkeypair -alias "$name" -dname "$subject" -keystore "$DIR/$name.p12" "${KEY[@]}"
  keytool -certreq -alias "$name" -keystore "$DIR/$name.p12" -storepass "$PASS" -file "$DIR/$name.csr"
  keytool -gencert -alias ca -keystore "$DIR/$ca.p12" -storepass "$PASS" -rfc \
    -infile "$DIR/$name.csr" -outfile "$DIR/$name.pem" -ext "$ext" "$@"
  # the CA first, so keytool can build the chain, then the signed certificate over the key pair
  keytool -importcert -noprompt -alias ca -file "$DIR/$ca.pem" -keystore "$DIR/$name.p12" -storepass "$PASS"
  keytool -importcert -noprompt -alias "$name" -file "$DIR/$name.pem" -keystore "$DIR/$name.p12" -storepass "$PASS"
  rm "$DIR/$name.csr"
}

make_ca ca "Telecom Dev CA"
make_ca other-ca "Stranger CA"

keytool -importcert -noprompt -alias ca -file "$DIR/ca.pem" -keystore "$DIR/gateway-ca-truststore.p12" \
  -storetype PKCS12 -storepass "$PASS"

issue backend "CN=localhost, O=Telecom Dev" "SAN=dns:localhost,ip:127.0.0.1" ca -validity 825 -ext EKU=serverAuth
issue token-server "CN=localhost, O=Telecom Dev Token Server" "SAN=dns:localhost,ip:127.0.0.1" ca -validity 825 -ext EKU=serverAuth
issue redis "CN=redis, O=Telecom Dev" "SAN=dns:localhost,dns:redis,ip:127.0.0.1" ca -validity 825 -ext EKU=serverAuth
# redis-server reads PEM files: the certificate is redis.pem already, the key comes out of the PKCS12
openssl pkcs12 -in "$DIR/redis.p12" -nocerts -nodes -passin "pass:$PASS" | openssl pkey -out "$DIR/redis-key.pem"
issue pgw-colombo-01 "CN=pgw-colombo-01, O=Telecom Dev" EKU=clientAuth ca -validity 825
issue pgw-kandy-01 "CN=pgw-kandy-01, O=Telecom Dev" EKU=clientAuth ca -validity 825
issue pgw-unknown-99 "CN=pgw-unknown-99, O=Telecom Dev" EKU=clientAuth ca -validity 825
issue pgw-expired "CN=pgw-expired, O=Telecom Dev" EKU=clientAuth ca -startdate -400d -validity 30
issue stranger "CN=pgw-colombo-01, O=Somebody Else" EKU=clientAuth other-ca -validity 825

echo "Test certificates written to $DIR/ (password: $PASS)"
