#!/bin/bash
solana config set --url localhost

NOTARY_ACCOUNT=`solana address -k $1`
solana airdrop 10 $NOTARY_ACCOUNT

bridgeAuthorityWallet=./build/nodes/custodied-keys/bridge-authority-wallet.json
solana-keygen new -o $bridgeAuthorityWallet --no-bip39-passphrase -f

participantWallet=./build/nodes/solana-keys/big-corp-wallet.json
solana-keygen new -o $participantWallet --no-bip39-passphrase -f

bridgeAuthorityAccount=`solana address -k $bridgeAuthorityWallet`
funderKeyFile=$1
solana transfer $bridgeAuthorityAccount 0.1 --fee-payer $funderKeyFile --from $funderKeyFile --allow-unfunded-recipient

participantAccount=`solana address -k $participantWallet`
solana transfer $participantAccount 0.1 --fee-payer $funderKeyFile --from $funderKeyFile --allow-unfunded-recipient

tokenMintFile=./build/nodes/solana-keys/token-mint.json
solana-keygen new -o $tokenMintFile --no-bip39-passphrase

MINT_ACCOUNT=$(spl-token create-token \
  --program-id TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb \
  --mint-authority $bridgeAuthorityAccount \
  --fee-payer $bridgeAuthorityWallet \
  --decimals 9 \
  --output json \
  $tokenMintFile | jq -r '.commandOutput.address')

TOKEN_ACCOUNT=$(spl-token create-account \
  --program-id TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb \
  --owner $participantAccount \
  --fee-payer $funderKeyFile \
  $MINT_ACCOUNT  | awk '/^Creating account / {print $3}')

participantPubKeyFile=./build/nodes/solana-keys/O=WayneCo,L=SF,C=US.pub
echo $TOKEN_ACCOUNT >> $participantPubKeyFile

tokenMintPubKeyFile=./build/nodes/solana-keys/token-mint.pub
echo $MINT_ACCOUNT >> $tokenMintPubKeyFile
