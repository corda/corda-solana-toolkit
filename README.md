# Corda Solana Toolkit

The Gradle modules in this repo are split into two main groups:

* Java utility library for Solana
* Corda to Solana Bridge Authority

All the modules have the same Maven group of `com.r3.corda.lib.solana` and are available from
`https://download.corda.net/maven/corda-dependencies`.

## Java Solana Library

This is composed of three separate modules `core`, `corda-utils`, and `testing`.

### `core`

Collection of Corda-compatible Solana utilities built upon [Sava](https://github.com/corda/sava). This includes
[`FileSigner`](core/src/main/kotlin/com/r3/corda/lib/solana/core/FileSigner.kt) for supporting the Solana filesystem
wallet and [`SolanaClient`](core/src/main/kotlin/com/r3/corda/lib/solana/core/SolanaClient.kt) which is an RPC
client that honours rate limits and uses the websocket API for efficient waiting of transaction confirmation.

### `testing`

Contains a wrapper around the
[`solana-test-validator`](testing/src/main/java/com/r3/corda/lib/solana/testing/SolanaTestValidator.java) which
makes it easy to configure and spin up a validator from Java/Kotlin code. There is also
[`SolanaTestClass`](testing/src/main/kotlin/com/r3/corda/lib/solana/testing/SolanaTestClass.kt) which wraps this
inside a JUnit extension making it trivial to write Solana-based tests.

### `corda-utils`

Collection of Solana utilities for CorDapps.

## Bridge Authority

This consists of the CorDapp bundle `bridge-authority-contracts` and `bridge-authority-workflows` which form the
backbone of a proposed solution for the bridging (and redemption) of Corda assets onto Solana. This design centers
around a "bridge authority" node which can be introduced into a Corda network that already uses the
[Corda Tokens SDK](https://github.com/corda/token-sdk). The network CorDapp does not need to modified and
instead Corda participants simply "send" their asset to be bridged to the bridge authority which takes care of
minting the revelant SPL token on Solana. It also listens for redemption requests and automatically burns the tokens
before returning the Corda asset back to the original owner.

See this [sample](https://github.com/corda/samples-kotlin/tree/release/ent/4.14/Solana/bridging-token) which uses
this against an existing CorDapp.
