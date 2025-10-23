import net.corda.plugins.Cordform
import net.corda.plugins.Node
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.kotlin.dsl.register
import org.gradle.api.provider.Provider


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

    cordapp(libs.samples.kotlin.stockpaydividend.contracts)
    cordapp(libs.samples.kotlin.stockpaydividend.workflows)
}

// Adds passing TOML references for Cordform.nodeDefaults.cordapp property
fun Node.cordapp(dep: Provider<MinimalExternalModuleDependency>) {
    val resolvedDep : MinimalExternalModuleDependency = dep.get()
    cordapp("${resolvedDep.module.group}:${resolvedDep.module.name}:${resolvedDep.versionConstraint.requiredVersion}")
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
        cordapp(libs.tokens.contracts)
        cordapp(libs.tokens.workflows)

        cordapp(libs.samples.kotlin.stockpaydividend.contracts)
        cordapp(libs.samples.kotlin.stockpaydividend.workflows)

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
