import net.corda.plugins.Cordform

plugins {
    id("default-kotlin")
    alias(libs.plugins.cordformation)
}

dependencies {
    corda(libs.corda)
    cordaBootstrapper(libs.corda.node.api)
    cordaDriver(libs.corda.shell)

    cordapp(project(":bridging-token-contracts"))
    cordapp(project(":bridging-token-workflows"))

    cordapp(libs.tokens.contracts)
    cordapp(libs.tokens.workflows)

    cordapp(libs.samples.kotlin.contracts)
    cordapp(libs.samples.kotlin.workflows)
}

tasks.register<Cordform>("deployNodes") {
    dependsOn(
        project(":bridging-token-contracts").tasks.named("jar"),
        project(":bridging-token-workflows").tasks.named("jar")
    )

    nodeDefaults {
        projectCordapp {
            deploy = false
        }
        cordapp("com.r3.corda.lib.tokens:tokens-contracts:1.3.2")
        cordapp("com.r3.corda.lib.tokens:tokens-workflows:1.3.2")
        //cordapp("${libs.tokens.contracts}") //TODO cant resolve toml variables from within deployNodes config
        //cordapp("${libs.tokens.workflows}")

        cordapp("com.github.corda.samples-kotlin:contracts:94f90ceb81cba943f196b9acfba55ade6701f131")
        cordapp("com.github.corda.samples-kotlin:workflows:94f90ceb81cba943f196b9acfba55ade6701f131")
        //cordapp("${libs.samples.kotlin.contracts}")
        //cordapp("${libs.samples.kotlin.workflows}")

        runSchemaMigration = true
    }
    node {
        name("O=Notary,L=London,C=GB")
        notary = mapOf(
            "validating" to "false",
            "serviceLegalName" to "O=Notary Service,L=Zurich,C=CH"
        )
        p2pPort(10002)
        rpcSettings {
            address("localhost:10003")
            adminAddress("localhost:10033")
        }
    }
    node {
        name("O=Solana Notary,L=London,C=GB")
        notary = mapOf(
            "validating" to "false",
            "serviceLegalName" to "O=Solana Notary Service,L=Ashburn,ST=Virginia,C=US"
            // TODO add Solana settings
        )
        p2pPort(10004)
        rpcSettings {
            address("localhost:10005")
            adminAddress("localhost:10035")
        }
    }
    node {
        name("O=Bank,L=Chicago,C=US")
        p2pPort(10012)
        rpcSettings {
            address("localhost:10013")
            adminAddress("localhost:10043")
        }
        rpcUsers = listOf(
            mapOf(
                "user" to "user1",
                "password" to "test",
                "permissions" to listOf("ALL")
            )
        )
    }
    node {
        name("O=Bridging Authority,L=New York,C=US")
        p2pPort(10014)
        rpcSettings {
            address("localhost:10015")
            adminAddress("localhost:10044")
        }
        rpcUsers = listOf(
            mapOf(
                "user" to "user1",
                "password" to "test",
                "permissions" to listOf("ALL")
            )
        )
        cordapp(project(":bridging-token-contracts"))
        //cordapp(project(":bridging-token-workflows")) //TODO endable once there is a cordapp
    }
}
