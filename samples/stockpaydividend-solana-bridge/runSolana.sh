#!/bin/bash

#build
./gradlew solana-aggregator:admin-cli:build
cd solana-aggregator/notary-program
anchor build

#start
solana-test-validator --reset --ledger ../admin-cli/build/test-ledger --bpf-program target/deploy/corda_notary-keypair.json target/deploy/corda_notary.so

#new terminal
ADMIN_CLI="solana-aggregator/admin-cli/build/libs/admin-cli-4.14-SNAPSHOT.jar"
solana airdrop -k solana-aggregator/notary-program/dev-keys/DevAD5S5AFhTTCmrD8Jg58bDhbZabSzth7Bu6rG4HFYo.json --commitment confirmed 10
#solana airdrop -k solana-aggregator/notary-program/dev-keys/DevNMdtQW3Q4ybKQvxgwpJj84h5mb7JE218qTpZQnoA3.json --commitment confirmed 10
java -jar $ADMIN_CLI initialize -u http://localhost:8899 -v -k solana-aggregator/notary-program/dev-keys/DevAD5S5AFhTTCmrD8Jg58bDhbZabSzth7Bu6rG4HFYo.json
java -jar $ADMIN_CLI create-network -u http://localhost:8899 -v -k solana-aggregator/notary-program/dev-keys/DevAD5S5AFhTTCmrD8Jg58bDhbZabSzth7Bu6rG4HFYo.json

#Add Corda Notary
java -jar $ADMIN_CLI authorize --address Dev7chG99tLCAny3PNYmBdyhaKEVcZnSTp3p1mKVb5m5 --network 0 -u http://localhost:8899 -k solana-aggregator/notary-program/dev-keys/DevAD5S5AFhTTCmrD8Jg58bDhbZabSzth7Bu6rG4HFYo.json
#Verify
java -jar $ADMIN_CLI list-notaries -u http://localhost:8899 -v -k solana-aggregator/notary-program/dev-keys/DevAD5S5AFhTTCmrD8Jg58bDhbZabSzth7Bu6rG4HFYo.json
