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

tasks.register<Cordform>("deployNodes") {
    dependsOn(
        project(":bridging-token-contracts").tasks.named("jar"),
        project(":bridging-token-workflows").tasks.named("jar")
    )
    val commonRpcUser =  listOf(
        mapOf(
            "user" to "user1",
            "password" to "test",
            "permissions" to listOf("ALL")
        )
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
            "serviceLegalName" to "O=Notary Service,L=London,C=GB"
        )
        p2pPort(10002)
        rpcSettings {
            address("localhost:10003")
            adminAddress("localhost:10033")
        }
    }
    node {
        name("O=Solana Notary,L=Ashburn,ST=Virginia,C=US")
        notary = mapOf(
            "validating" to "false",
            "serviceLegalName" to "O=Solana Notary Service,L=Washington,C=US"
            // TODO add Solana settings
        )
        p2pPort(10019)
        rpcSettings {
            address("localhost:10020")
            adminAddress("localhost:10048")
        }
    }
    node {
        name("O=WayneCo,L=SF,C=US")
        p2pPort(10005)
        rpcSettings {
            address("localhost:10006")
            adminAddress("localhost:10036")
        }
        rpcUsers = commonRpcUser
        cordapp(libs.tokens.workflows) {
            // Config to set a default notary to prevent the stock issuance flow to choose the second notary (Solana Notary)
            config("""notary = "O=Notary Service,L=London,C=GB"""")
        }
    }
    node {
        name("O=Shareholder,L=New York,C=US")
        p2pPort(10008)
        rpcSettings {
            address("localhost:10009")
            adminAddress("localhost:10039")
        }
        rpcUsers = commonRpcUser
    }
    node {
        name("O=Bank,L=Washington DC,C=US")
        p2pPort(10012)
        rpcSettings {
            address("localhost:10013")
            adminAddress("localhost:10043")
        }
        rpcUsers = commonRpcUser
    }
    node {
        name("O=Observer,L=Washington DC,C=US")
        p2pPort(10015)
        rpcSettings {
            address("localhost:10016")
            adminAddress("localhost:10046")
        }
        rpcUsers = commonRpcUser
    }
    node {
        name("O=Bridging Authority,L=New York,C=US")
        p2pPort(10017)
        rpcSettings {
            address("localhost:10018")
            adminAddress("localhost:10047")
        }
        rpcUsers = commonRpcUser
        cordapp(project(":bridging-token-contracts"))
        //cordapp(project(":bridging-token-workflows")) //TODO endable once there is a cordapp
    }
}

// Adds Cordform 'cordapp' method override accept TOML reference
fun Node.cordapp(dep: Provider<MinimalExternalModuleDependency>) {
    val value : MinimalExternalModuleDependency = dep.get()
    cordapp("${value.module.group}:${value.module.name}:${value.versionConstraint.requiredVersion}")
}

// Adds Cordform 'cordapp' method override accept TOML reference
fun Node.cordapp(dep: Provider<MinimalExternalModuleDependency>, action: Action<in net.corda.plugins.Cordapp>) {
    val value : MinimalExternalModuleDependency = dep.get()
    cordapp("${value.module.group}:${value.module.name}:${value.versionConstraint.requiredVersion}", action)
}
