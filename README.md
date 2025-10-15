# Corda Solana Toolkit

## Tokens SDK Bridging CorDapp

## Using the Development Key Pair

A development key pair, pre-whitelisted in the relevant Corda program on the Solana **Devnet**, is bundled with the `bridging-token-workflows` CorDapp as a resource.
Any Corda node that interacts with the Solana network (the “Bridging Authority”) must use a whitelisted key. 
When running against Solana Devnet, you can reuse the bundled dev key by extracting it with the sample Gradle task below.

```bash
dependencies {
    libJar 'com.r3.corda.lib.solana:bridging-token-workflows:0.1.0-SNAPSHOT@jar'
}

def solanaNotaryKeyFileName = 'Dev7chG99tLCAny3PNYmBdyhaKEVcZnSTp3p1mKVb5m5.json'
#The path is ste to follow other provides scripts or Gradle tasks for running nodes locally
def solanaNotaryKeyPath = "$buildDir/extracted/dev-keys/$solanaNotaryKeyFileName"

tasks.register('extractDevKey', Copy) {
    from({ zipTree(configurations.libJar.singleFile) }) {
        include "dev-keys/$solanaNotaryKeyFileName"
    }
    into "$buildDir/extracted"
}
```

