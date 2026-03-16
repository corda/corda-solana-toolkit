# Corda Solana Toolkit
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![Maven](https://img.shields.io/maven-metadata/v?metadataUrl=https://download.corda.net/maven/corda-dependencies/com/r3/corda/lib/solana/corda-solana-core/maven-metadata.xml&label=Maven)

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

### [`corda-solana-core`](corda-solana-core)

Collection of Corda-compatible Solana utilities built upon [Sava](https://github.com/corda/sava). This includes
[`FileSigner`](corda-solana-core/src/main/kotlin/com/r3/corda/lib/solana/core/FileSigner.kt) for supporting the Solana filesystem
wallet and [`SolanaClient`](corda-solana-core/src/main/kotlin/com/r3/corda/lib/solana/core/SolanaClient.kt) which is an RPC
client that honours rate limits and uses the websocket API for efficient waiting of transaction confirmation.

### [`corda-solana-testing`](corda-solana-testing)

Contains a wrapper around the
[`solana-test-validator`](corda-solana-testing/src/main/java/com/r3/corda/lib/solana/testing/SolanaTestValidator.java) which
makes it easy to configure and spin up a validator from Java/Kotlin code. There is also
[`SolanaTestClass`](corda-solana-testing/src/main/kotlin/com/r3/corda/lib/solana/testing/SolanaTestClass.kt) which wraps this
inside a JUnit extension making it trivial to write Solana-based tests.

### [`corda-solana-cordapp-utils`](corda-solana-cordapp-utils)

Collection of Solana utilities for CorDapps.

## [Bridge Authority](bridge-authority)

This consists of the CorDapp bundle `bridge-authority:contracts` and `bridge-authority:workflows` which is a proposed
solution for the bridging (and redemption) of Corda assets into Solana tokens. It centers around a "bridge
authority" node which can be introduced into a Corda network that already uses the
[Corda Tokens SDK](https://github.com/corda/token-sdk). More information about how it works and how to use it can be
found [here](bridge-authority/README.md).

## Publishing a release

The [axion-release-plugin](https://axion-release-plugin.readthedocs.io/en/latest/) is used for managing the version
via git tags. Run the following to get the current
[SNAPSHOT](https://maven.apache.org/guides/getting-started/#what-is-a-snapshot-version) version:

```shell
./gradlew -q currentVersion
```

Assuming the version is `0.1.9-SNAPSHOT`. Add a `v` prefix and remove the `-SNAPSHOT` suffix for the next version tag:

```shell
git tag v0.1.9
git push origin v0.1.9
```

Running `./gradlew -q currentVersion` again will now print

```
Project version: 0.1.9
```

This is because the current commit is now on a version tag.

The next SNAPSHOT version (`0.1.10-SNAPSHOT` going with our example) will occur automatically when the main branch
advances past this tag.

## License

[Apache 2.0](LICENSE)
