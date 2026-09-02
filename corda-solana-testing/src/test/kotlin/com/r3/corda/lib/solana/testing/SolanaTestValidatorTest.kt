package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.core.AccountManagement
import com.r3.corda.lib.solana.testing.SolanaTestValidator.Builder.PORT_BLOCK_MAX
import com.r3.corda.lib.solana.testing.SolanaTestValidator.Builder.PORT_BLOCK_MIN
import com.r3.corda.lib.solana.testing.SolanaTestValidator.Builder.PORT_BLOCK_WIDTH
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import software.sava.core.accounts.PublicKey
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class SolanaTestValidatorTest {
    // Runs concurrently (see junit-platform.properties) so several validators start at once.
    @Execution(ExecutionMode.CONCURRENT)
    @RepeatedTest(10)
    fun `dynamic ports assign distinct port blocks to concurrent validators`(@TempDir tempDir: Path) {
        SolanaTestValidator
            .builder()
            .ledger(Files.createDirectory(tempDir.resolve("ledger")))
            .dynamicPorts()
            .start()
            .use { validator ->
                val rpcPort = validator.rpcPort()
                println("rpcPort = $rpcPort")
                assertThat(rpcPort).isBetween(PORT_BLOCK_MIN, PORT_BLOCK_MAX - 1)
                assertThat((rpcPort - PORT_BLOCK_MIN) % PORT_BLOCK_WIDTH).isZero()
                assertThat(activePorts.add(rpcPort))
                    .withFailMessage("concurrent validators collided on port block $rpcPort")
                    .isTrue()
                try {
                    // Stay alive while sibling runs start, so an overlapping block would be caught.
                    Thread.sleep(500)
                } finally {
                    activePorts.remove(rpcPort)
                }
            }
    }

    @Test
    fun `deactivateFeature leaves the gate off in genesis`(@TempDir tempDir: Path) {
        // A feature left off gets no feature account at all, whereas one the validator activates
        // does, so the two lookups tell a deactivated gate apart from a request that never landed.
        SolanaTestValidator
            .builder()
            .ledger(Files.createDirectory(tempDir.resolve("ledger")))
            .dynamicPorts()
            .deactivateFeature(DISABLE_DEPRECATED_BPF_LOADER)
            .start()
            .use { validator ->
                val accounts = AccountManagement(validator.waitForReadiness().client())
                assertThat(accounts.getAccountInfo(DISABLE_DEPRECATED_BPF_LOADER))
                    .withFailMessage("the gate was activated, so deactivateFeature had no effect")
                    .isNull()
                assertThat(accounts.getAccountInfo(BPF_FUNCTION_HASH_COLLISIONS))
                    .withFailMessage("the control gate is off too, so the assertion above proves nothing")
                    .isNotNull()
            }
    }

    private companion object {
        private val activePorts = ConcurrentHashMap.newKeySet<Int>()

        // Two long-standing gates every supported validator build knows and activates by default.
        private val DISABLE_DEPRECATED_BPF_LOADER =
            PublicKey.fromBase58Encoded("GTUMCZ8LTNxVfxdrw7ZsDFTxXb7TutYkzJnFwinpE6dg")
        private val BPF_FUNCTION_HASH_COLLISIONS =
            PublicKey.fromBase58Encoded("8199Q2gMD2kwgfopK5qqVWuDbegLgpuFUFHCcUJQDN8b")
    }
}
