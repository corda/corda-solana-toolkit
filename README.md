# Corda Solana Toolkit

## Tokens SDK Bridging CorDapp

### Using the Development Key Pair

A development key pair, pre-whitelisted in the relevant Corda program on the Solana **Devnet**, is bundled with the `bridging-token-workflows` CorDapp as a resource.
Any Corda node that interacts with the Solana network (the “Bridge Authority”) must use a whitelisted key.
When running against Solana Devnet, you can reuse the bundled dev key by extracting it with the sample Gradle task below.
