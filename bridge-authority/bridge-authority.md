# Bridge Authority: Corda ↔ Solana Token Bridge

## Overview

The bridge authority is a **Corda node role** that bridges [Corda Tokens SDK](https://github.com/corda/token-sdk)
`FungibleToken` states to equivalent [Token-2022](https://spl.solana.com/token-2022) SPL tokens on Solana, and back
again:

- **Bridging** (Corda → Solana): A Corda participant transfers their `FungibleToken` to the bridge authority. The
  bridge authority automatically locks the tokens in escrow and mints an equivalent amount of SPL tokens on Solana into
  the participant's Solana wallet.
- **Redemption** (Solana → Corda): The participant transfers their SPL tokens to a designated Solana redemption account.
  The bridge authority detects the balance, burns the SPL tokens, and releases the escrowed Corda tokens back to the
  participant.

Both directions are automatic. Participant nodes require **no CorDapp changes** — they interact with the bridge using
standard Tokens SDK flows (`MoveFungibleTokens`). Only the bridge authority node runs these CorDapps. The bridge
authority is designed as a drop-in addition to an existing Corda network: no existing CorDapps need modification and
participant nodes do not need to upgrade to Corda 4.14.

### Modules

| Module                       | Purpose                                                            |
|------------------------------|--------------------------------------------------------------------|
| `bridge-authority-contracts` | Corda contract states and transaction verification logic           |
| `bridge-authority-workflows` | Corda flows, `BridgingService` (auto-detection), and configuration |

Both modules must be deployed together on the bridge authority node.

---

## Key Concepts

### Roles

| Role                 | Description                                                                                                 |
|----------------------|-------------------------------------------------------------------------------------------------------------|
| **Bridge Authority** | The Corda node running these CorDapps; orchestrates all bridging and redemption operations                  |
| **Escrow Identity**  | A confidential identity used by the bridge authority to hold escrowed `FungibleToken` states                |
| **Participant**      | Any Corda party holding `FungibleToken`s; interacts with the bridge using standard Tokens SDK flows         |
| **Solana Notary**    | A Corda notary that can validate and execute Solana instructions atomically with Corda transaction finality |
| **General Notary**   | The existing standard Corda notary used for all non-Solana transactions                                     |

### Solana Accounts

The bridge operates with several Solana account types. The distinction between a *wallet account* (the owner/signing
key) and a *token account* (the Associated Token Account / ATA that holds the balance) is a Solana-specific concept:

- **Mint account**: The token definition on Solana (equivalent to a Corda `TokenType`).
- **Mint authority**: The key authorized to mint new tokens — controlled by the bridge authority.
- **Participant wallet** (`mintWalletAccount`): The participant's Solana wallet. The bridge derives the ATA from this and
  mints bridged tokens into it.
- **Redemption wallet** (`redemptionWalletAccount`): A Solana wallet controlled by the bridge. Participants transfer SPL
  tokens to the ATA of this wallet to trigger redemption.

### Token-2022 Program

The bridge is currently hardcoded to use the **Token-2022** program, not the classic SPL token program. This could
be relaxed in a future version to support either token programs.

### Amount Handling

Corda and Solana may represent the same token with different decimal precisions (e.g. 3 decimal places on Corda, 4 on
Solana). The bridge handles this transparently: both the bridging and burn receipt contract states carry two amounts — one
at Corda precision and one at Solana precision — which must be numerically equal. During bridging, the Corda amount is
rescaled to the Solana mint's decimals. During redemption, the Solana amount is truncated to Corda's precision. Any
fractional remainder below Corda's precision is left in the redemption account.

---

## Bridging (Corda → Solana)

The participant transfers their `FungibleToken` to the bridge authority using the standard Corda Tokens SDK:

```kotlin
// On the participant's node — no special CorDapp required
proxy.startFlow(
    ::MoveFungibleTokens,
    PartyAndAmount(bridgeAuthorityParty, Amount(quantity, tokenType))
)
```

`BridgingService` on the bridge authority detects the incoming token via vault observation and automatically starts the
bridging flow. No further action is needed from the participant.

### Phase 1: Lock

The bridge authority moves the `FungibleToken` from its own identity to the escrow identity (a confidential identity
dedicated to holding escrowed tokens). In the same transaction, a bridging state is created carrying the Solana minting
metadata (mint account, mint authority, destination ATA, and the token amounts at both Corda and Solana precision).

This transaction uses the general notary and contains no Solana instructions.

### Phase 2: Notary Move

The bridging state's notary is changed from the general notary to the Solana notary via `MoveNotaryFlow`. This is
necessary because only the Solana notary can validate and execute Solana instructions.

> **Why a separate bridging state?** The Tokens SDK `FungibleToken` contract does not support notary changes. A separate
> bridging state is used so that it can move freely between notaries.

### Phase 3: Mint

The bridging state is consumed in a transaction notarized by the Solana notary. The transaction includes a
`Token2022.mintTo` instruction that the Solana notary verifies against the bridging state's fields and then executes on
Solana — atomically with Corda finality.

---

## Redemption (Solana → Corda)

The participant transfers their SPL tokens on Solana to the redemption ATA provided by the bridge operator. This is a
standard Solana token transfer — no Corda involvement.

### Step 1: Detection

`BridgingService` monitors configured redemption wallets using two mechanisms:
- **WebSocket subscription** for real-time account change notifications.
- **Periodic polling** (default: every 10 seconds) as a backup in case a WebSocket event is missed.

When a non-zero balance is detected, the service starts the redemption flow.

### Step 2: Burn

The bridge authority creates a transaction with a `Token2022.burn` instruction, notarized by the Solana notary. This
burns the SPL tokens on Solana and produces a `FungibleTokenBurnReceipt` on the Corda ledger.

### Step 3: Notary Move

The burn receipt's notary is changed from the Solana notary to the general notary.

### Step 4: Unlock

The burn receipt is consumed alongside the escrowed `FungibleToken` states (held by the escrow identity). The tokens
are moved back to the bridge authority. The contract verifies that the unlocked amount matches the burn receipt.

### Step 5: Deliver

The bridge authority transfers the tokens to the participant using standard Tokens SDK `MoveFungibleTokens`.

---

## Configuration

The bridge authority reads its configuration from the standard Corda CorDapp config file:

```
<node-dir>/cordapps/config/bridge-authority-workflows-<version>.conf
```

### Example

```hocon
# Mapping from Corda party X500 name → Solana wallet public key (base58).
# This is the wallet (owner) key, not the ATA. The ATA is derived automatically.
participants {
    "O=Alice, L=London, C=GB"    = "4uQeVj5tqViQh7yWWGStvkEG1Zmhx6uasJtWCJziofM"
    "O=Bob, L=New York, C=US"    = "HN7cABqLq46Es1jh92dQQisAq662SmxELLLsHHe4YWrH"
}

# Mapping from Corda token type identifier → Solana mint configuration.
# Key: tokenIdentifier for simple TokenType, or UUID string for evolvable tokens (TokenPointer).
mintsWithAuthorities {
    "MSFT" {
        tokenMint     = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        mintAuthority = "4uQeVj5tqViQh7yWWGStvkEG1Zmhx6uasJtWCJziofM"
    }
    "AAPL" {
        tokenMint     = "So11111111111111111111111111111111111111112"
        mintAuthority = "4uQeVj5tqViQh7yWWGStvkEG1Zmhx6uasJtWCJziofM"
    }
}

# Mapping from Solana redemption wallet public key (base58) → Corda party X500 name.
# Direction is reversed from `participants`: Solana wallet → Corda party.
# When a balance appears on an ATA owned by this wallet, redemption is triggered for that party.
redemptionWalletAccountToHolder {
    "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV" = "O=Alice, L=London, C=GB"
    "HN7cABqLq46Es1jh92dQQisAq662SmxELLLsHHe4YWrH" = "O=Bob, L=New York, C=US"
}

# X500 name of the Solana-capable notary.
solanaNotaryName = "O=Solana Notary Service, L=London, C=GB"

# X500 name of the standard Corda notary.
generalNotaryName = "O=Notary Service, L=Zurich, C=CH"

# Solana JSON-RPC HTTP endpoint.
solanaRpcUrl = "http://localhost:8899"

# Solana JSON-RPC WebSocket endpoint.
solanaWebsocketUrl = "ws://localhost:8900"

# Path to the Solana filesystem wallet JSON (array of 64 bytes).
# This key is used to sign Solana transactions and must be the mintAuthority for all
# configured mints. Generate with: solana-keygen new --outfile /path/to/keypair.json
bridgeAuthorityWalletFile = "/opt/bridge/keypair.json"

# (Optional) Solana redemption account polling interval in seconds.
# Default: 10. Used as a backup to WebSocket event subscriptions.
# redemptionCheckIntervalSeconds = 10
```

### Notes

- **`participants` vs `redemptionWalletAccountToHolder`** map in opposite directions. `participants` maps a Corda party
  to the Solana wallet that *receives* bridged tokens. `redemptionWalletAccountToHolder` maps a Solana redemption wallet
  (controlled by the bridge) to the Corda party that receives unlocked tokens.

- **Evolvable tokens**: For `FungibleToken` states backed by a `TokenPointer`, the `mintsWithAuthorities` key must be
  the string form of the `LinearState`'s UUID. For simple `TokenType`, use `tokenIdentifier` (e.g. `"MSFT"`).

- **Escrow identity**: The escrow identity UUID is derived deterministically — it is not configurable. On first startup
  the bridge authority generates a new confidential identity key pair under this UUID. On subsequent startups it reuses
  the existing key pair. If the node's key store is lost, escrowed tokens cannot be unlocked.

- **`bridgeAuthorityWalletFile`**: The public key in this file must match the `mintAuthority` value for every entry in
  `mintsWithAuthorities`. The wallet must also hold sufficient SOL to pay Solana transaction fees.

- **Redemption accounts**: For each participant and each token type, create a Token-2022 ATA owned by the corresponding
  `redemptionWalletAccount`. Provide the ATA address to the participant as their "redemption address".

---

## Backwards Compatibility

The `bridge-authority-contracts` module is designed to run on **pre-4.14** Corda nodes. Participant nodes that receive
states governed by these contracts (e.g. during transaction verification) do not need to be on Corda 4.14.

To achieve this, the contracts module avoids types introduced in Corda 4.14 (such as `Pubkey`). Solana public keys are
stored as base58-encoded `String` fields in the contract states.

Solana instruction verification in the contracts is also guarded by a runtime classpath check (`isSolanaSupported`). On a
pre-4.14 node where the Solana classes are not present, instruction verification is skipped — the contract still
validates state shapes and amounts, but defers Solana-specific checks to the bridge authority.

---

## Design Notes

### Why a separate bridging state

The Tokens SDK `FungibleToken` contract does not support notary changes. The Solana notary must be the notary for any
transaction that executes a Solana instruction. A separate bridging state works around this by carrying the token amounts
and minting metadata in a state that can be freely moved between notaries.

### Why an escrow identity

Using the bridge authority's own key as the escrow holder would make it impossible to distinguish escrowed tokens from
tokens the bridge authority owns outright. A confidential identity (the escrow identity) acts as the escrow address.
The bridge authority controls this key, but it is opaque to other network participants.

The escrow identity UUID is derived deterministically from a fixed constant — no configuration is required. On first
startup, a new key pair is generated under this UUID. On subsequent startups, the existing key pair is reused.

