#!/usr/bin/env bash
set -euxo pipefail

mkdir -p build/tmp
cd build/tmp
git clone https://github.com/corda/samples-kotlin.git -b release/4.12 --single-branch
cd samples-kotlin/Tokens/stockpaydividend

# Appends the text below to Tokens/stockpaydividend/contracts/build.gradle file.
# This allows a gradle task to publish locally CordApp to ~/.m2/repository/com/stockpaydividend/contracts/1.0/contracts-1.0.jar
cat >> "contracts/build.gradle" << 'EOF'

apply plugin: 'maven-publish'

publishing {
    publications {
        maven(MavenPublication) {
            artifactId 'contracts'
            from components.cordapp
        }
    }
}
EOF

# Appends the text below to Tokens/stockpaydividend/workflows/build.gradle file.
# This allows a gradle task to publish locally CordApp to ~/.m2/repository/com/stockpaydividend/workflows/1.0/workflows-1.0.jar
cat >> "workflows/build.gradle" << 'EOF'

apply plugin: 'maven-publish'

publishing {
    publications {
        maven(MavenPublication) {
            artifactId 'workflows'
            from components.cordapp
        }
    }
}
EOF

./gradlew publishToMavenLocal

