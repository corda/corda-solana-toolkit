# Bridge Authority: Corda ↔ Solana Token Bridge

## Overview

The bridge authority is a **Corda node role** that bridges [Corda Tokens SDK](https://github.com/corda/token-sdk) `FungibleToken` states to equivalent [Token-2022](https://spl.solana.com/token-2022) SPL tokens on Solana, and back again.

Two directions are supported:

- **Bridging** (Corda → Solana): A Corda participant transfers their `FungibleToken` to the bridge authority. The bridge authority automatically locks the tokens in escrow and mints an equivalent amount of SPL tokens on Solana into the participant's Solana wallet.
- **Redemption** (Solana → Corda): The participant transfers their SPL tokens to a designated Solana redemption account. The bridge authority detects the balance, burns the SPL tokens, and releases the escrowed Corda tokens back to the participant.

Both directions are automatic. Participant nodes require **no CorDapp changes** — they interact with the bridge using standard Tokens SDK flows (`MoveFungibleTokens`). Only the bridge authority node runs these CorDapps.

### Modules

| Module | Purpose |
|---|---|
| `bridge-authority-contracts` | Corda contract states and transaction verification logic |
| `bridge-authority-workflows` | Corda flows, `BridgingService` (auto-detection), and configuration |

Both modules must be deployed together on the bridge authority node. `bridge-authority-workflows` depends on `bridge-authority-contracts`.

---

## Key Concepts

### Account Nomenclature

The following terminology is used consistently throughout the codebase. The distinction between a *wallet account* (the owner/signing key) and a *token account* (the ATA that holds the balance) is a Solana-specific concept.

| Term | Description |
|---|---|
| `mintAccount` | Solana token mint public key — the asset definition on Solana |
| `mintAuthority` | Solana public key authorized to mint tokens for `mintAccount` (controlled by the bridge authority) |
| `bridgeWalletAccount` | Solana wallet public key that owns the bridge-side token account |
| `bridgeTokenAccount` | Associated Token Account (ATA) owned by `bridgeWalletAccount` — receives minted tokens |
| `redemptionWalletAccount` | Solana wallet public key controlled by the bridge; participants send SPL tokens here to trigger redemption |
| `redemptionTokenAccount` | ATA owned by `redemptionWalletAccount` — the account to which participants transfer tokens for redemption |

### Roles

| Role | Description |
|---|---|
| **Bridge Authority** | The Corda node running these CorDapps; orchestrates all bridging and redemption operations |
| **Locking Identity** | A confidential identity (derived key pair) used as the escrow holder for locked `FungibleToken` states |
| **Participant** | Any Corda party holding `FungibleToken`s; interacts with the bridge using standard Tokens SDK flows |
| **Solana Notary** | A special Corda notary that can validate and execute Solana instructions atomically with transaction finality |
| **General Notary** | The standard Corda notary used for all non-Solana transactions |

### Token-2022 Program

The bridge uses the **Token-2022** program (`TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb`), not the classic SPL token program. This is a hardcoded constant (`tokenProgramId` in `Utils.kt`).

### Amount Representation

Token amounts are raw `Long` values — the `quantity` field of the Tokens SDK `Amount` type maps 1:1 to the Solana raw token amount. No scaling is applied. Token decimal places are defined at the Solana mint level and are the responsibility of the mint creator.

---

## Architecture

### High-Level Topology

```
  CORDA NETWORK                              SOLANA NETWORK
  ─────────────────────────────────────      ──────────────────────────────────
  [Participant Node]
   holds FungibleToken
        │
        │ MoveFungibleTokens (standard Tokens SDK)
        ▼
  [Bridge Authority Node]
   BridgingService detects new token
   → starts BridgeFungibleTokenFlow
        │
        │ Phase 1: Lock token to locking identity
        │ Phase 2: Move proxy to Solana notary
        │ Phase 3: Mint via Solana notary ──────────────► Token-2022 mint
        │                                                 (participant's ATA)
        │
        │ (later) BridgingService detects Solana burn
        │ → starts RedeemFungibleTokenFlow
        │
  [Solana Notary]                           [Redemption Token Account]
   executes Solana instructions              participant transfers SPL tokens here
   atomically with Corda finality                    │
                                             Bridge detects balance via
                                             WebSocket + polling backup
```

### Corda Ledger State Lifecycle

#### Bridging (Corda → Solana)

```
  FungibleToken                    BridgedFungibleTokenProxy
  (holder=bridgeAuthority)
       │
       │  LockToken tx  (general notary)
       ▼
  FungibleToken          +───────► BridgedFungibleTokenProxy
  (holder=lockingId)               (notary=generalNotary)
                                          │
                                          │  MoveNotaryFlow
                                          ▼
                                   BridgedFungibleTokenProxy
                                   (notary=solanaNotary)
                                          │
                                          │  MintToSolana tx (Solana notary)
                                          ▼
                                   [consumed — proxy gone]
                                   Solana: Token-2022 tokens minted
                                   into participant's bridgeTokenAccount
```

#### Redemption (Solana → Corda)

```
  Participant transfers SPL tokens to redemptionTokenAccount
       │
       │  BurnOnSolana tx (Solana notary)
       ▼
  FungibleTokenBurnReceipt
  (notary=solanaNotary)
       │
       │  MoveNotaryFlow
       ▼
  FungibleTokenBurnReceipt
  (notary=generalNotary)
       │
       │  UnlockToken tx (general notary)
       ▼
  [consumed — receipt gone]
  FungibleToken                      [FungibleToken change, if any]
  (holder=bridgeAuthority)           (holder=lockingId)
       │
       │  MoveFungibleTokens (standard Tokens SDK)
       ▼
  FungibleToken
  (holder=participant / redemptionHolder)
```

---

## Bridging: Corda → Solana

### Prerequisite

The participant transfers their `FungibleToken` to the bridge authority's Corda identity using the standard Tokens SDK:

```kotlin
// On the participant's node — no special CorDapp required
proxy.startFlow(
    ::MoveFungibleTokens,
    PartyAndAmount(bridgeAuthorityParty, Amount(quantity, tokenType))
)
```

`BridgingService` on the bridge authority detects the incoming token via vault `trackBy` and automatically starts `BridgeFungibleTokenFlow`. No further action is needed from the participant.

### Phase 1: Lock (`MoveAndLockFungibleTokenFlow`)

1. `BridgingService` retrieves `BridgingCoordinates` from `ConfigHandler`: the Solana `mintAccount`, `mintAuthority`, and the participant's configured `mintWalletAccount`.
2. The bridge authority creates the participant's Associated Token Account (ATA) on Solana if it does not yet exist. This call is idempotent.
3. `MoveAndLockFungibleTokenFlow` builds and finalizes a transaction:

```
INPUTS:                         OUTPUTS:                           COMMANDS:
FungibleToken                   FungibleToken                      MoveTokenCommand
(holder=bridgeAuthority)        (holder=lockingIdentity)           BridgeCommand.LockToken
                                BridgedFungibleTokenProxy
                                  .amount = token quantity
                                  .mintAccount
                                  .mintAuthority
                                  .bridgeTokenAccount (ATA)
                                  .bridgeAuthority
```

No Solana instructions are present in this transaction. Contract enforcement:
- Input `FungibleToken` holder must be the bridge authority.
- Output `FungibleToken` holder must differ from the input holder.
- `BridgedFungibleTokenProxy.amount` must equal `outputToken.amount.quantity`.

### Phase 2: Notary Move

`MoveNotaryFlow` changes the notary of `BridgedFungibleTokenProxy` from the general notary to the Solana notary. This is required because only the Solana notary can validate and execute Solana instructions.

> **Why does `BridgedFungibleTokenProxy` exist?**
> The Tokens SDK's `FungibleToken` contract does not permit notary changes on its states. A separate proxy state is introduced as a workaround. The proxy carries the same amount alongside the Solana minting metadata and can be moved freely between notaries. See [Design Notes](#design-notes).

### Phase 3: Mint (`BridgeFungibleTokenFlow.createMintTransaction`)

`BridgeFungibleTokenFlow` builds and finalizes a transaction with the Solana notary:

```
INPUTS:                         OUTPUTS:    NOTARY INSTRUCTIONS:
BridgedFungibleTokenProxy       (none)      Token2022.mintTo(
(notary=solanaNotary)                         mintAccount,
                                              bridgeTokenAccount,
                                COMMANDS:     mintAuthority,
                                BridgeCommand.MintToSolana
                                              amount)
```

The Solana notary verifies that the `Token2022.mintTo` instruction exactly matches the fields of the consumed `BridgedFungibleTokenProxy`, then executes the instruction on Solana atomically with Corda finality.

Contract enforcement:
- Exactly one `BridgedFungibleTokenProxy` input; no proxy outputs.
- Exactly one Solana instruction; it must match the proxy's `mintAccount`, `bridgeTokenAccount`, `mintAuthority`, and `amount` exactly.
- Single command only (`MintToSolana`).

**Error handling**: If the mint transaction fails with a `NotaryError.Conflict` (the proxy was already consumed by a previous run), the flow logs a warning. All other errors are sent to the Corda flow hospital for operator review.

---

## Redemption: Solana → Corda

### Prerequisite

The participant transfers their SPL tokens on Solana to the `redemptionTokenAccount` provided by the bridge operator. This is a standard Solana token transfer — no Corda involvement.

### Step 1: Detection

`BridgingService` monitors the configured `redemptionWalletAccount` keys using two mechanisms:
- **WebSocket subscription**: Real-time account change notifications via the Solana RPC WebSocket endpoint.
- **Periodic polling**: A backup poller (default interval: 10 seconds, configurable) that queries all redemption token accounts. This ensures no redemption event is missed if a WebSocket message is dropped.

When a non-zero balance is detected on a `redemptionTokenAccount`, the service resolves the Corda party from the `redemptionWalletAccountToHolder` configuration and starts `RedeemFungibleTokenFlow`.

### Step 2: Burn (`BurnTokensOnSolanaFlow`)

```
INPUTS:    OUTPUTS:                       NOTARY INSTRUCTIONS:
(none)     FungibleTokenBurnReceipt       Token2022.burn(
             .redemptionTokenAccount        mintAccount,
             .redemptionWalletAccount       redemptionTokenAccount,
             .mintAccount         COMMANDS: redemptionWalletAccount,
             .amount              RedeemCommand.BurnOnSolana
             .bridgeAuthority               amount)
```

The Solana notary executes the burn and notarizes the `FungibleTokenBurnReceipt`. The bridge authority must sign this transaction.

Contract enforcement:
- Exactly one `FungibleTokenBurnReceipt` output; no receipt inputs.
- Exactly one Solana instruction matching the receipt fields exactly.
- Bridge authority key must be a signer.
- Single command only (`BurnOnSolana`).

### Step 3: Notary Move

`MoveNotaryFlow` changes the notary of `FungibleTokenBurnReceipt` from the Solana notary to the general notary, so subsequent transactions can use the standard notary.

### Step 4: Unlock (`MoveAndUnlockFungibleTokenFlow`)

```
INPUTS:                           OUTPUTS:                          COMMANDS:
FungibleTokenBurnReceipt          FungibleToken                     MoveTokenCommand
(consumed)                        (holder=bridgeAuthority)          RedeemCommand.UnlockToken(
FungibleToken × N                 [FungibleToken change?]             lockingIdentity)
(holder=lockingIdentity)          (holder=lockingIdentity)
```

Contract enforcement:
- All input `FungibleToken` states must be held by `lockingIdentity` (the `UnlockToken` command parameter).
- Output `FungibleToken` states may only be held by `bridgeAuthority` or `lockingIdentity`.
- The sum of tokens moved to `bridgeAuthority` must exactly equal `burnReceipt.amount`.
- No Solana instructions.

### Step 5: Release and Deliver

`RedeemFungibleTokenFlow` releases the Corda soft lock acquired during token selection, then calls `MoveFungibleTokens` to transfer the tokens from the bridge authority to the `redemptionHolder` (the participant's Corda identity).

---

## Configuration

The bridge authority node reads its configuration from the standard Corda CorDapp config file. For a node running in a typical deployment this is:

```
<node-dir>/cordapps/config/bridge-authority-workflows-<version>.conf
```

### Full Configuration Example

```hocon
# Mapping from Corda party X500 name → Solana wallet public key (base58).
# The Solana wallet is where bridged (minted) tokens will be delivered.
# Note: this is the wallet (owner) key, not the ATA. The ATA is derived automatically.
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

# UUID label used to derive and retrieve the locking confidential identity.
# MUST remain the same across node restarts. Generate once and store permanently.
# If this value changes, previously locked tokens become inaccessible.
lockingIdentityLabel = "550e8400-e29b-41d4-a716-446655440000"

# X500 name of the Solana-capable notary.
solanaNotaryName = "O=Solana Notary Service, L=London, C=GB"

# X500 name of the standard Corda notary.
generalNotaryName = "O=Notary Service, L=Zurich, C=CH"

# Solana JSON-RPC HTTP endpoint.
solanaRpcUrl = "http://localhost:8899"

# Solana JSON-RPC WebSocket endpoint.
solanaWsUrl = "ws://localhost:8900"

# Path to the Solana filesystem wallet JSON (array of 64 bytes).
# This key is used to sign Solana transactions and must be the mintAuthority for all
# configured mints. Generate with: solana-keygen new --outfile /path/to/keypair.json
bridgeAuthorityWalletFile = "/opt/bridge/keypair.json"

# (Optional) Solana redemption account polling interval in seconds.
# Default: 10. Used as a backup to WebSocket event subscriptions.
# redemptionCheckIntervalSeconds = 10
```

### Configuration Notes

- **`participants` vs `redemptionWalletAccountToHolder`**: These map in opposite directions. `participants` maps a Corda party to the Solana wallet that *receives* bridged tokens. `redemptionWalletAccountToHolder` maps a Solana redemption wallet (controlled by the bridge) to the Corda party that receives unlocked tokens.

- **`mintsWithAuthorities` key for evolvable tokens**: For `FungibleToken` states backed by a `TokenPointer` (evolvable tokens), the key must be the string form of the `LinearState`'s UUID (e.g. `"550e8400-e29b-41d4-a716-446655440000"`). For simple `TokenType`, use `tokenIdentifier` (e.g. `"MSFT"`).

- **`lockingIdentityLabel`**: On first startup the bridge authority generates a new confidential identity key pair and stores it under this UUID label. On subsequent startups it reuses the existing key pair. If you change this value or lose the node's key store, tokens locked under the old identity cannot be unlocked.

- **`bridgeAuthorityWalletFile`**: The public key in this file must match the `mintAuthority` value for every entry in `mintsWithAuthorities`. The wallet must also hold sufficient SOL to pay Solana transaction fees.

- **Redemption accounts**: For each participant and each token type, create a Token-2022 ATA owned by the corresponding `redemptionWalletAccount`. Provide the ATA address to the participant as their "redemption address".

---

## API Reference

### `BridgeFungibleTokenFlow`

```kotlin
@StartableByService
@InitiatingFlow
class BridgeFungibleTokenFlow(
    val lockingHolder: Party,               // Confidential identity used as the escrow holder
    val originalHolder: Party,              // Party that sent the FungibleToken to the bridge authority
    val token: StateAndRef<FungibleToken>,  // The token state to bridge
    val solanaNotary: Party,                // Solana-capable notary
    val observers: List<Party>,             // Optional observer parties
) : FlowLogic<SignedTransaction>()
```

Normally started automatically by `BridgingService`. Can be started manually via `CordaRPCOps.startFlow` on the bridge authority node.

Returns the `SignedTransaction` for the final mint transaction (Phase 3).

---

### `RedeemFungibleTokenFlow`

```kotlin
@StartableByService
@InitiatingFlow
class RedeemFungibleTokenFlow(
    val redemptionCoordinates: RedemptionCoordinates, // Solana burn metadata
    val redemptionHolder: Party,    // Corda party to receive the unlocked tokens
    val amount: Long,               // Raw token amount to redeem
    val solanaNotary: Party,
    val generalNotary: Party,
    val lockingHolder: Party,       // Confidential identity holding the escrowed tokens
) : FlowLogic<SignedTransaction>()
```

Normally started automatically by `BridgingService`. Returns the `SignedTransaction` for the final `MoveFungibleTokens` (Step 5).

---

### `BridgingService`

```kotlin
@CordaService
class BridgingService(appServiceHub: AppServiceHub) : SingletonSerializeAsToken()
```

Automatically started when the Corda node starts. Not intended for direct invocation. Responsibilities:

- Subscribes to vault updates for `FungibleToken` states and starts `BridgeFungibleTokenFlow` for each new token received.
- Subscribes to Solana token account changes (WebSocket) and polls all configured redemption accounts (backup), starting `RedeemFungibleTokenFlow` when a non-zero balance is detected.
- Manages Solana RPC connection lifecycle.

---

### Data Classes

#### `BridgingCoordinates`

Returned by `ConfigHandler.getBridgingCoordinates`. Carries the Solana metadata needed for Phase 1 and Phase 3 of bridging.

```kotlin
data class BridgingCoordinates(
    val mintAccount: Pubkey,        // Solana token mint
    val mintAuthority: Pubkey,      // Key authorized to mint
    val mintWalletAccount: Pubkey,  // Participant's Solana wallet (ATA is derived from this)
)
```

#### `RedemptionCoordinates`

Carries the Solana metadata needed for `BurnTokensOnSolanaFlow`.

```kotlin
data class RedemptionCoordinates(
    val mintAccount: Pubkey,
    val redemptionWalletAccount: Pubkey,  // Bridge-controlled wallet
    val redemptionTokenAccount: Pubkey,   // ATA where participant sent SPL tokens
    val tokenId: String,                  // Corda token type identifier
)
```

---

## Contract Reference

### `FungibleTokenBridgeContract`

Contract ID: `com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenBridgeContract`

The contract is exhaustive over non-reference states: every input and output must be either a Tokens SDK `FungibleToken` or a `BridgedFungibleTokenProxy`. No other state types are permitted in the same transaction.

| Command | Inputs | Outputs | Solana Instructions | Key Invariants |
|---|---|---|---|---|
| `LockToken` | 1 `FungibleToken` (held by bridge authority) | 1 `FungibleToken` (held by locking identity) + 1 `BridgedFungibleTokenProxy` | None | Holder must change; proxy `amount` must equal output token `quantity`; also requires `MoveTokenCommand`; 2 commands total |
| `MintToSolana` | 1 `BridgedFungibleTokenProxy` | None | Exactly 1: `Token2022.mintTo` | Instruction must match proxy fields exactly (`mintAccount`, `bridgeTokenAccount`, `mintAuthority`, `amount`); single command only |

---

### `FungibleTokenRedemptionContract`

Contract ID: `com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract`

| Command | Inputs | Outputs | Solana Instructions | Key Invariants |
|---|---|---|---|---|
| `BurnOnSolana` | None | 1 `FungibleTokenBurnReceipt` | Exactly 1: `Token2022.burn` | Instruction must match receipt fields exactly; bridge authority must sign; single command only |
| `UnlockToken(lockingIdentity)` | 1 `FungibleTokenBurnReceipt` (consumed) + ≥1 `FungibleToken` (all held by `lockingIdentity`) | ≥1 `FungibleToken` | None | All outputs must go to bridge authority or locking identity; sum of tokens to bridge authority must equal `burnReceipt.amount`; also requires `MoveTokenCommand`; 2 commands total |

---

## Design Notes

### Why `BridgedFungibleTokenProxy` exists

The Tokens SDK `FungibleToken` contract does not allow its states to change notary (it does not implement the notary change protocol). However, the Solana notary must be the notary of any transaction that executes a Solana instruction. `BridgedFungibleTokenProxy` is a separate `ContractState` that holds the same `amount` alongside the Solana minting metadata. Because it is not governed by the Token SDK contract, it can be freely moved between notaries using `MoveNotaryFlow`.

### Why a locking identity

The bridge authority holds tokens in escrow on behalf of participants. Using the bridge authority's own key directly would make it impossible to distinguish "tokens locked for participant Alice" from "tokens the bridge authority owns outright". The locking identity is a confidential identity (a derived key pair) that acts as the escrow address. From a ledger perspective, the bridge authority controls this key, but the identity is opaque to other network participants, providing privacy between participants.

### Why `lockingIdentityLabel` must be a stable UUID

On startup, `ConfigHandler` calls `identityService.publicKeysForExternalId(lockingIdentityLabel)` to look up an existing key pair. If found, it reuses it; if not, it generates a new one. If the label changes between restarts, a new key pair is generated and the old locking identity — along with all tokens held by it — becomes permanently inaccessible to the bridge.

Generate the UUID once:
```shell
python3 -c "import uuid; print(uuid.uuid4())"
```
Store it in configuration and never change it.

### Why polling supplements WebSocket subscriptions

Solana WebSocket subscriptions can be silently dropped on network interruptions. `BridgingService` maintains a backup polling loop (interval configurable via `redemptionCheckIntervalSeconds`) that periodically queries all configured redemption token accounts. This ensures that no redemption event is missed even if the WebSocket connection was broken during the token transfer.

### Why `FungibleTokenLockCapture` is a mutable holder

The Tokens SDK's `AbstractMoveTokensFlow` acquires a Corda soft lock on selected token states but does not expose the `TransactionBuilder` — and therefore the lock UUID — to the caller. `RedeemFungibleTokenFlow` needs the UUID to release the soft lock after the unlock transaction finalizes. `FungibleTokenLockCapture` is a mutable holder that is passed by reference into `MoveAndUnlockFungibleTokenFlow`, which writes the UUID once. This is a workaround for the SDK's encapsulation boundary.
