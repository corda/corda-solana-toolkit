#!/usr/bin/env bash

cd ..
mkdir tmp
cd tmp
git clone https://github.com/corda/samples-kotlin.git
cd samples-kotlin
cd Tokens/stockpaydividend

# Amend build file to publish locally  ~/.m2/repository/com/stockpaydividend/contracts/1.0/contracts-1.0.jar
cat >> "contracts/build.gradle" << 'EndOfTextToAppend'

apply plugin: 'maven-publish'

publishing {
    publications {
        maven(MavenPublication) {
            artifactId 'contracts'
            from components.cordapp
        }
    }
}
EndOfTextToAppend

# Amend build file to publish locally  ~/.m2/repository/com/stockpaydividend/workflows/1.0/workflows-1.0.jar
cat >> "workflows/build.gradle" << 'EndOfTextToAppend'

apply plugin: 'maven-publish'

publishing {
    publications {
        maven(MavenPublication) {
            artifactId 'workflows'
            from components.cordapp
        }
    }
}
EndOfTextToAppend

./gradlew publishToMavenLocal

