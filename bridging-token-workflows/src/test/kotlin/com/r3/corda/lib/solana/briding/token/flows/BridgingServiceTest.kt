package com.r3.corda.lib.solana.briding.token.flows

import com.r3.corda.lib.solana.bridging.token.flows.BridgingService
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.createMockCordaService
import net.corda.testing.node.makeTestIdentityService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class BridgingServiceTest {
    private lateinit var services: MockServices

    @BeforeEach
    fun setUp() {
        services = MockServices(
            listOf(
                "com.r3.corda.lib.solana.bridging.token.contracts",
                "com.r3.corda.lib.solana.bridging.token.flows"
            ),
            TestIdentity(
                CordaX500Name("Bridge Authority", "London", "GB")
            ),
            makeTestIdentityService(),
            generateKeyPair()
        )
    }

    @Test
    fun `attempts to create a websockets at service startup`() {
        val bridgingService = createMockCordaService(services, ::BridgingService)
        bridgingService.savaFactory = TestFactory
        testF

        Thread.sleep(5000)
    }
}
