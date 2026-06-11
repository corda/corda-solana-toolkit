# Corda Solana Toolkit
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![Maven](https://img.shields.io/maven-metadata/v?metadataUrl=https://download.corda.net/maven/corda-dependencies/com/r3/corda/lib/solana/corda-solana-core/maven-metadata.xml&label=Maven)

The repo contains two independent Gradle builds, each with its own wrapper:

* **Java Solana Library** at the repo root (`./gradlew`) — runs on the latest Gradle and targets JDK 17
  bytecode.
* **[Bridge Authority](bridge-authority)** under `bridge-authority/` (`bridge-authority/gradlew`) — pinned to
  Gradle 7.6.6 because of the Corda 4.x cordapp and quasar plugins. Consumes the Java Solana Library as a
  published Maven artifact rather than via a Gradle project dependency. See its
  [README](bridge-authority/README.md) for build and release instructions.

Both publish to `com.r3.corda.lib.solana` and share the same axion-release tag stream — the latest tag
applies to both builds.

To consume the published artifacts, add the Corda dependencies Maven repo:
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

A proposed solution for bridging (and redeeming) Corda assets to and from Solana tokens. Centers around a
"bridge authority" node introduced into a Corda network that already uses the
[Corda Tokens SDK](https://github.com/corda/token-sdk). Ships as the CorDapp bundle `bridge-authority-contracts`
and `bridge-authority-workflows`. Lives in its own Gradle build under
[`bridge-authority/`](bridge-authority/README.md), with build and design docs there.

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

Pushing the tag triggers both Jenkins pipelines (toolkit and bridge-authority) in parallel, each publishing its
own artifacts under the same version. The release is considered complete once both succeed.

### Bumping the bridge-authority dependency pin

Bridge Authority depends on the Java Solana Library through published Maven coordinates pinned in
`bridge-authority/gradle/libs.versions.toml`. After cutting a toolkit release, bump that
value to the new release so subsequent bridge-authority changes pick it up.

## License

[Apache 2.0](LICENSE)
