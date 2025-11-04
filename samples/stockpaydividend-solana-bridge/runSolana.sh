#!/bin/bash

cd "$1"
#./gradlew solana-aggregator:admin-cli:build
cd solana-aggregator/notary-program
anchor build

solana-test-validator --reset --ledger ../admin-cli/build/test-ledger --bpf-program target/deploy/corda_notary-keypair.json target/deploy/corda_notary.so &

