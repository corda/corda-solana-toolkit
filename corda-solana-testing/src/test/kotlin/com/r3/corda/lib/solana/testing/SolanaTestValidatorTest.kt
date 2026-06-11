package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.testing.SolanaTestValidator.Builder.PORT_BLOCK_MAX
import com.r3.corda.lib.solana.testing.SolanaTestValidator.Builder.PORT_BLOCK_MIN
import com.r3.corda.lib.solana.testing.SolanaTestValidator.Builder.PORT_BLOCK_WIDTH
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
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

    private companion object {
        private val activePorts = ConcurrentHashMap.newKeySet<Int>()
    }
}
