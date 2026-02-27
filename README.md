# Corda Solana Toolkit
[![Maven](https://img.shields.io/maven-metadata/v?metadataUrl=https://download.corda.net/maven/corda-dependencies/com/r3/corda/lib/solana/corda-solana-core/maven-metadata.xml&label=Maven)]()

The Gradle modules in this repo are split into two main groups:

* Java utility library for Solana
* Corda to Solana Bridge Authority

To use them specify `com.r3.corda.lib.solana` for the Maven group and add the following Maven repo:
```kotlin
repositories {
    maven {
        url = uri("https://download.corda.net/maven/corda-dependencies")
    }
}
```

## Java Solana Library

This is composed of three separate modules `corda-solana-core`, `corda-solana-cordapp-utils`, and `corda-solana-testing`.

### `corda-solana-core`

Collection of Corda-compatible Solana utilities built upon [Sava](https://github.com/corda/sava). This includes
[`FileSigner`](corda-solana-core/src/main/kotlin/com/r3/corda/lib/solana/core/FileSigner.kt) for supporting the Solana filesystem
wallet and [`SolanaClient`](corda-solana-core/src/main/kotlin/com/r3/corda/lib/solana/core/SolanaClient.kt) which is an RPC
client that honours rate limits and uses the websocket API for efficient waiting of transaction confirmation.

### `corda-solana-testing`

Contains a wrapper around the
[`solana-test-validator`](corda-solana-testing/src/main/java/com/r3/corda/lib/solana/testing/SolanaTestValidator.java) which
makes it easy to configure and spin up a validator from Java/Kotlin code. There is also
[`SolanaTestClass`](corda-solana-testing/src/main/kotlin/com/r3/corda/lib/solana/testing/SolanaTestClass.kt) which wraps this
inside a JUnit extension making it trivial to write Solana-based tests.

### `corda-solana-cordapp-utils`

Collection of Solana utilities for CorDapps.

## Bridge Authority

This consists of the CorDapp bundle `bridge-authority:contracts` and `bridge-authority:workflows` which is a proposed
solution for the bridging (and redemption) of Corda assets into Solana tokens. It centers around a "bridge
authority" node which can be introduced into a Corda network that already uses the
[Corda Tokens SDK](https://github.com/corda/token-sdk). More information about how it works and how to use it can be
found [here](bridge-authority/README.md).

## Project maintenance
The project dependencies are listed in [libs.versions.toml](gradle/libs.versions.toml).
Update them as needed and perform a release when the changes are ready to be published.
To create a release perform:
```
./gradlew currentVersion
```
This will print the current snapshot version, for example:
```
Task :currentVersion

Project version: 0.1.9-SNAPSHOT
```
Add ``v`` prefix and remove the `-SNAPSHOT` suffix to create new tag, for example:
```
git tag v0.1.9
git push origin v0.1.9
```
In a build system navigate to a page for the project and use ``Build With paramaters`` providing a name of a tag (for example `v0.1.9`).

The next SNAPSHOT version will be created automatically, when the main branch advances past the tagged commit.
