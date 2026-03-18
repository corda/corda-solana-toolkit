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

### Phase 1: Transfer

The participant transfers their `FungibleToken` to the bridge authority using the standard Corda Tokens SDK:

```kotlin
// On the participant's node — no special CorDapp required
proxy.startFlow(
    ::MoveFungibleTokens,
    PartyAndAmount(bridgeAuthorityParty, Amount(quantity, tokenType))
)
```

`BridgingService` on the bridge authority detects the incoming token via vault observation and automatically starts the
remaining phases. No further action is needed from the participant.

### Phase 2: Escrow

The Solana mint must happen in a transaction notarized by the Solana notary. However, the Tokens SDK `FungibleToken`
contract does not support notary changes — the state carries a reference to its `TokenType` which ties it to the
original notary. To work around this, the bridge authority escrows the original `FungibleToken` under the escrow
identity and creates a separate bridging state. This bridging state carries the Solana minting metadata (mint account,
mint authority, destination ATA, and the token amounts) but has no `TokenType` dependency, allowing it to move freely
between notaries.

### Phase 3: Notary Move

The bridging state's notary is changed from the general notary to the Solana notary via `MoveNotaryFlow`.

### Phase 4: Mint

The bridging state is consumed in a transaction notarized by the Solana notary. The transaction includes a
`Token2022.mintTo` instruction that the Solana notary verifies against the bridging state and then executes on Solana —
atomically with Corda finality. Only after this phase completes has the token been fully bridged.

The bridging process as a whole is not atomic — it spans multiple Corda transactions across two notaries. However, it
is designed to be eventually consistent: Corda's flow checkpointing ensures that if the bridge authority restarts at any
point, the in-flight flow resumes from where it left off.

---

## Redemption (Solana → Corda)

Redemption is the reverse of bridging. The participant transfers their SPL tokens on Solana to the redemption account
provided by the bridge operator. The bridge authority monitors all configured redemption accounts via websocket
updates (backed by periodic polling). When a non-zero balance is detected the redemption flow begins automatically.

The Solana burn is executed via the Solana notary, which atomically burns the SPL tokens and finalises a burn receipt
state on Corda. This receipt serves as on-ledger proof that the tokens have been destroyed on Solana. In a subsequent
transaction, the bridge authority presents the burn receipt to unlock the corresponding escrowed `FungibleToken` states
and delivers them to the participant mapped to that redemption account in configuration.

Because the burn receipt is immutable once finalised, there is no risk of tokens being burnt without a corresponding
unlock — the receipt guarantees the escrowed tokens will eventually be released. As with bridging, Corda's flow
checkpointing ensures the remaining steps complete even if the bridge authority restarts mid-flow.

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
    #
    # *The mint authority keypair file for each token mint must be custodied to the Solana notary.*
    "participants" : {
        "O=Alice Corp, L=Madrid, C=ES" : "FYPK13XxJKrTLHn15utLmQ5jXVCBSnJQsY21QjNoA8mr",
        "O=Bob Plc, L=Rome, C=IT" : "3tiq47rYYfRTd9ykvMpz6hgN7R8vnYcrdCngdjk93JRH"
    },

    # The Solana token mints for each Corda token type that can be bridged. The key is tokenIdentifier for simple
    # TokenType, or the UUID string for evolvable tokens (TokenPointer).
    "tokens" : {
        "AAPL" : "BrVt1nQFNp8Earh3UZyMf8zA2YtW1iCV8t67atM9JAbY",
        "MSFT" : "AJq271cwC4zsAHhpmek9Czcibv6tpwmCbapd7pzv2xUG"
    },

    # Mapping from Solana redemption wallet address (not ATA) → Corda party X500 name. When SPL tokens are
    # transferred to the ATA derived from one of these wallets, the bridge authority triggers redemption and delivers
    # the unlocked Corda tokens to the mapped party.
    #
    # *The keypair file for each of these wallets must be custodied to the Solana notary.*
    "redemptionWalletAccountToHolder" : {
        "5b9YpAQM6TaD4KmEoUzZpa5dXiFCJ2nGoVqNA6RK2a1T" : "O=Alice Corp, L=Madrid, C=ES",
        "Ho5BBqMsqv8ZjLjVQeuB5p1im1JRngyfsmDpWdjQ4m8t" : "O=Bob Plc, L=Rome, C=IT"
    },

    # Path to the bridge authority's own Solana keypair file. Used for signing Solana
    # transactions initiated by the bridge authority, such as creating missing ATAs.
    "bridgeAuthorityWalletFile" : "bridge-authority-keypair.json",

    # The X500 name of the Solana notary
    "solanaNotaryName" : "O=Solana Notary Service, L=London, C=GB",

    # The X500 name of the existing non-Solana notary
    "generalNotaryName" : "O=Notary Service, L=Zurich, C=CH",

    # The RPC and websocket URLs for interacting with the blockchain. Here the devnet URLs are given.
    "solanaRpcUrl" : "https://api.devnet.solana.com",
    "solanaWebsocketUrl" : "wss://api.devnet.solana.com"

    # (Optional) Solana redemption account polling interval in seconds.
    # Default: 10. Used as a backup to WebSocket event subscriptions.
    # redemptionCheckIntervalSeconds = 10
}
```

### Solana Notary Configuration

The Solana notary requires its own configuration under `notary.solana` in the node config. It must custody the keypair
files for the mint authorities and the redemption wallets — place these in the `custodiedKeysDir` directory.

```hocon
notary {
    validating = false
    solana {
        rpcUrl = "https://api.devnet.solana.com"
        websocketUrl = "wss://api.devnet.solana.com"
        notaryKeypairFile = "/opt/notary/solana-notary-keypair.json"
        custodiedKeysDir = "/opt/notary/custodied-keys"
        trustedCordaSigners = ["O=Bridge Authority, L=London, C=GB"]
    }
}
```

The `custodiedKeysDir` directory should contain the keypair files for:
- The **mint authority** for each token mint (so the notary can execute `mintTo` instructions)
- Each **redemption wallet** (so the notary can execute `burn` instructions on behalf of the redemption accounts)

See the
[Solana notary configuration reference](https://docs.r3.com/en/platform/corda/4.14/enterprise/node/setup/corda-configuration-fields.html#notary)
for the full set of fields.

### Notes

- **`participants` vs `redemptionWalletAccountToHolder`** map in opposite directions. `participants` maps a Corda party
  to the Solana wallet that *receives* bridged tokens. `redemptionWalletAccountToHolder` maps a Solana redemption wallet
  (controlled by the bridge) to the Corda party that receives unlocked tokens.

- **Evolvable tokens**: For `FungibleToken` states backed by a `TokenPointer`, the `tokens` key must be the string
  form of the `LinearState`'s UUID. For simple `TokenType`, use `tokenIdentifier` (e.g. `"MSFT"`).

- **Redemption accounts**: For each participant and each token type, create a Token-2022 ATA owned by the corresponding
  `redemptionWalletAccount`. Provide the ATA address to the participant as their "redemption address".

- **`trustedCordaSigners`**: Must be configured on the Solana notary to restrict which Corda parties can submit Solana
  instructions. See the [Solana Notary Configuration](#solana-notary-configuration) section above.
