# Shared by generate-dev-certs.sh and rotate-cert.sh. Expects DIR (the certificates) and PASS.
# OUT is where a new key store and certificate go; it is DIR unless a script sets it.

KEY=(-keyalg EC -groupname secp256r1 -storetype PKCS12 -storepass "$PASS")

# issue <file> <subject> <extensions> <ca file> [extra gencert options...]
issue() {
  local name="$1" subject="$2" ext="$3" ca="$4" out="${OUT:-$DIR}"
  shift 4
  keytool -genkeypair -alias "$name" -dname "$subject" -keystore "$out/$name.p12" "${KEY[@]}"
  keytool -certreq -alias "$name" -keystore "$out/$name.p12" -storepass "$PASS" -file "$out/$name.csr"
  keytool -gencert -alias ca -keystore "$DIR/$ca.p12" -storepass "$PASS" -rfc \
    -infile "$out/$name.csr" -outfile "$out/$name.pem" -ext "$ext" "$@"
  # the CA first, so keytool can build the chain, then the signed certificate over the key pair
  keytool -importcert -noprompt -alias ca -file "$DIR/$ca.pem" -keystore "$out/$name.p12" -storepass "$PASS"
  keytool -importcert -noprompt -alias "$name" -file "$out/$name.pem" -keystore "$out/$name.p12" -storepass "$PASS"
  rm "$out/$name.csr"
}

# gateway_key <directory> <gateway>: its private key as PEM, for k6 (TE-2)
gateway_key() {
  openssl pkcs12 -in "$1/$2.p12" -nocerts -nodes -passin "pass:$PASS" | openssl pkey -out "$1/$2-key.pem"
}

# make_crl_db <directory> <ca file>: what "openssl ca" needs to keep and sign a revocation list (SE-6)
make_crl_db() {
  local db="$1" ca="$2"
  mkdir -p "$db"
  : > "$db/index.txt"
  echo 1000 > "$db/crlnumber"
  openssl pkcs12 -in "$DIR/$ca.p12" -nocerts -nodes -passin "pass:$PASS" | openssl pkey -out "$db/ca-key.pem"
  cat > "$db/openssl.cnf" <<EOF
[ca]
default_ca = crl
[crl]
database = $db/index.txt
crlnumber = $db/crlnumber
certificate = $DIR/$ca.pem
private_key = $db/ca-key.pem
default_md = sha256
default_crl_days = 3650
unique_subject = no
EOF
}

# revoke <crl db directory> <certificate name>
revoke() {
  openssl ca -config "$1/openssl.cnf" -revoke "$DIR/$2.pem" 2>/dev/null
}

# write_crl <crl db directory> <file>: to a new file first, so a reader never sees half a list
write_crl() {
  openssl ca -config "$1/openssl.cnf" -gencrl -out "$2.new" 2>/dev/null
  mv "$2.new" "$2"
}
