# Bridge Authority: Corda ↔ Solana Token Bridge

## Overview

The bridge authority is a **Corda node** that bridges [Corda Tokens SDK](https://github.com/corda/token-sdk)
`FungibleToken` states to equivalent [Token-2022](https://spl.solana.com/token-2022) SPL tokens on Solana, and back
again.

- **Bridging** (Corda → Solana): A Corda participant transfers their `FungibleToken` to the bridge authority. The
  bridge authority automatically locks the tokens in escrow and mints an equivalent amount of SPL tokens on Solana into
  the participant's Solana wallet.
- **Redemption** (Solana → Corda): The participant transfers their SPL tokens to a designated Solana redemption account.
  The bridge authority detects the balance, burns the SPL tokens, and releases the escrowed Corda tokens back to the
  participant.

The bridge authority is designed as a drop-in addition to an existing Corda network: no existing CorDapps need
modification and participant nodes do not need to upgrade to Corda 4.14. They interact with the bridge using
standard Tokens SDK flows (`MoveFungibleTokens`).

See the [sample](https://github.com/corda/samples-kotlin/tree/release/ent/4.14/Solana/bridge-authority) for an
end-to-end example using these CorDapps against an existing network.

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
key) and a *token account* (the Associated Token Account (ATA) that holds the balance) is a Solana-specific concept:

- **Mint account**: The token definition on Solana (equivalent to a Corda `TokenType`).
- **Mint authority**: The key authorized to mint new tokens — controlled by the bridge authority.
- **Participant wallet** (`mintWalletAccount`): The participant's Solana wallet. The bridge derives the ATA from this and
  mints bridged tokens into it.
- **Redemption wallet** (`redemptionWalletAccount`): A Solana wallet controlled by the bridge. Participants transfer SPL
  tokens to the ATA of this wallet to trigger redemption.

You can find more information on token accounts in the Solana [docs](https://solana.com/docs/tokens).

### Amount Handling

Corda and Solana may represent the same token with different decimal precisions. For example, the Corda `TokenType`
might have 3 decimal places, but the equivalent Solana token mint has 4. The bridge handles this transparently: both
the bridging and burn receipt contract states carry two amounts — one at Corda precision and one at Solana precision
— which must be numerically equal. During bridging, the Corda amount is rescaled to the Solana mint's decimals.
During redemption, the Solana amount is truncated to Corda's precision. Any fractional remainder below Corda's
precision is left in the redemption account.

---

## Bridging (Corda → Solana)

// TODO This transfer should be phase 1

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

### Phase 1: Escrow

The Solana mint must happen in a transaction notarized by the Solana notary. However, the Tokens SDK `FungibleToken`
contract does not support notary changes — the state carries a reference to its `TokenType` which ties it to the
original notary. To work around this, the bridge authority escrows the original `FungibleToken` under the escrow
identity and creates a separate bridging state. This bridging state carries the Solana minting metadata (mint account,
mint authority, destination ATA, and the token amounts) but has no `TokenType` dependency, allowing it to move freely
between notaries.

### Phase 2: Notary Move

The bridging state's notary is changed from the general notary to the Solana notary via `MoveNotaryFlow`.

### Phase 3: Mint

The bridging state is consumed in a transaction notarized by the Solana notary. The transaction includes a
`Token2022.mintTo` instruction that the Solana notary verifies against the bridging state and then executes on Solana —
atomically with Corda finality.

// TODO add that it's only after this completes has the Corda state/token been bridged. Whilst the entire process
isn't atomic, it is eventually consistent (or whatever the correct term is) bc of recoverability features either
builtin into Corda or via bridge authority.

---

## Redemption (Solana → Corda)

Redemption is the reverse of bridging. The participant transfers their SPL tokens on Solana to the redemption account
provided by the bridge operator. The bridge authority monitors all configured redemption accounts via websocket
updates (and backed by periodic polling). When a non-zero balance is detected, the bridge authority burns the SPL tokens
on Solana (via the Solana notary), unlocks the corresponding escrowed `FungibleToken` states on Corda, and delivers them
to the participant mapped to that redemption account in configuration.

// TODO the wording in the above paragraph implies the tokens are burnt and and then unlocks the escrowed states,
etc. it gives the impression that it's disconnected and possible to fail and leave burnt but unredeemed states. I
believe the burning is done atomically with the "un-escrowing" to the bridge authority identity? so even if the
original particpant only gets it after the burn, there's no chance of failure here bc ... (can't remember why
exactly). With these changes, it probably makes for this Redemption section to be more than one paragraph.

---

## Configuration

The bridge authority reads its configuration from its CorDapp config file:

```
<node-dir>/cordapps/config/bridge-authority-workflows-<version>.conf
```

### Example

```hocon
{
    # The Solana wallet address (not ATA) of each Corda participant who will want to bridge. The token account (ATA)
    # is automatically dervied for each token type and is where the tokens will be minted to.
    "participants" : {
        "O=Alice Corp, L=Madrid, C=ES" : "FYPK13XxJKrTLHn15utLmQ5jXVCBSnJQsY21QjNoA8mr",
        "O=Bob Plc, L=Rome, C=IT" : "3tiq47rYYfRTd9ykvMpz6hgN7R8vnYcrdCngdjk93JRH"
    },

    # For each Corda token type that can be bridged,
    "mintsWithAuthorities" : {
        "AAPL" : {
            "mintAuthority" : "7qCT2LVPabqXAXGsDj9QA3dbY9ipGtEg24si3RdjDEB1",
            "tokenMint" : "8nxAfmCghD21MqxcNocxaNLPrmdkD54QYgB31wvsNbHX"
        },
        "MSFT" : {
            "mintAuthority" : "7qCT2LVPabqXAXGsDj9QA3dbY9ipGtEg24si3RdjDEB1",
            "tokenMint" : "CRUvwFea8BSL6B4CHfZxMTsnredtnvEANYsca7QN9mE4"
        }
    },

    #
    "redemptionWalletAccountToHolder" : {
        "5b9YpAQM6TaD4KmEoUzZpa5dXiFCJ2nGoVqNA6RK2a1T" : "O=Alice Corp, L=Madrid, C=ES",
        "Ho5BBqMsqv8ZjLjVQeuB5p1im1JRngyfsmDpWdjQ4m8t" : "O=Bob Plc, L=Rome, C=IT"
    },

    #
    "bridgeAuthorityWalletFile" : "bridge-authority-keypair.json",

    # The X500 name of the Solana notary
    "solanaNotaryName" : "O=Solana Notary Service, L=London, C=GB",

    # The X500 name of the existing non-Solana notary
    "generalNotaryName" : "O=Notary Service, L=Zurich, C=CH",

    # The RPC and websocket URLs for interacting with the blockchain. Here the devnet URLs are given.
    "solanaRpcUrl" : "https://api.devnet.solana.com",
    "solanaWebsocketUrl" : "wss://api.devnet.solana.com"
}

```

```hocon
# Provide the Solana wallet address (not ATA) of each Corda participant who will bridge. The token account (ATA) is
automatically derived for each token type.
participants {
    "O=Alice, L=London, C=GB" = "4uQeVj5tqViQh7yWWGStvkEG1Zmhx6uasJtWCJziofM"
    "O=Bob, L=New York, C=US" = "HN7cABqLq46Es1jh92dQQisAq662SmxELLLsHHe4YWrH"
}

////// PICK UP FROM HERE!!!!!


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

- **`bridgeAuthorityWalletFile`**: The public key in this file must match the `mintAuthority` value for every entry in
  `mintsWithAuthorities`. The wallet must also hold sufficient SOL to pay Solana transaction fees.

- **Redemption accounts**: For each participant and each token type, create a Token-2022 ATA owned by the corresponding
  `redemptionWalletAccount`. Provide the ATA address to the participant as their "redemption address".

- **Solana notary — `trustedCordaSigners`**: Corda notaries are typically non-validating and will execute any Solana
  instruction in a transaction they notarize, without verifying it against the Corda state transition. The bridge
  authority contracts do enforce this consistency, but only on the submitting node. To prevent other network
  participants from submitting arbitrary Solana instructions through the notary, `trustedCordaSigners` must be
  configured on the Solana notary with the bridge authority's X.500 name. See the
  [Solana notary configuration reference](https://docs.r3.com/en/platform/corda/4.14/enterprise/node/setup/corda-configuration-fields.html#solana)
  for details.

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

### Why an escrow identity

Using the bridge authority's own key as the escrow holder would make it impossible to distinguish escrowed tokens from
tokens the bridge authority owns outright. A confidential identity (the escrow identity) acts as the escrow address.
The bridge authority controls this key, but it is opaque to other network participants.

The escrow identity UUID is derived deterministically from a fixed constant — no configuration is required. On first
startup, a new key pair is generated under this UUID. On subsequent startups, the existing key pair is reused.
