#!/usr/bin/env bash
set -euxo pipefail

CORDA_ENTERPRISE_HOME="$1"
ADMIN_CLI=$1"/solana-aggregator/admin-cli/build/libs/admin-cli-4.14-SNAPSHOT.jar"
KEY_FILE=$1"/solana-aggregator/notary-program/dev-keys/DevAD5S5AFhTTCmrD8Jg58bDhbZabSzth7Bu6rG4HFYo.json"
solana airdrop -k $KEY_FILE --commitment confirmed 10

java -jar $ADMIN_CLI initialize -u http://localhost:8899 -v -k $KEY_FILE
java -jar $ADMIN_CLI create-network -u http://localhost:8899 -v -k $KEY_FILE
java -jar $ADMIN_CLI authorize --address Dev7chG99tLCAny3PNYmBdyhaKEVcZnSTp3p1mKVb5m5 --network 0 -u http://localhost:8899 -k $KEY_FILE
java -jar $ADMIN_CLI list-notaries -u http://localhost:8899 -v -k $KEY_FILE
