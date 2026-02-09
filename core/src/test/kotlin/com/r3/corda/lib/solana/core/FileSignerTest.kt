package com.r3.corda.lib.solana.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.ProcessBuilder.Redirect.INHERIT
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.io.path.div

class FileSignerTest {
    @Test
    fun read(@TempDir tempDir: Path) {
        val file = tempDir / "signer.json"
        val keyGenOutput = exec("solana-keygen new -o $file --no-bip39-passphrase")
        val expectedAddress = Pattern.compile("""pubkey: (\S+)""").matcher(keyGenOutput).let { matcher ->
            matcher.find()
            matcher.group(1)
        }
        assertThat(FileSigner.read(file).publicKey().toBase58()).isEqualTo(expectedAddress)
    }

    @Test
    fun random(@TempDir tempDir: Path) {
        val randomSigner = FileSigner.random(tempDir)
        val expectedAddress = exec("solana address -k ${randomSigner.file}")
        assertThat(randomSigner.publicKey().toBase58()).isEqualTo(expectedAddress)
        assertThat(FileSigner.read(randomSigner.file).publicKey()).isEqualTo(randomSigner.publicKey())
    }

    private fun exec(args: String, workingDirectory: Path? = null): String {
        val builder = ProcessBuilder(args.split(" ")).redirectError(INHERIT)
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile())
        }
        val process = builder.start()
        val exitCode = process.waitFor()
        val output = process.inputReader().use { it.readText() }
        check(exitCode == 0) { "Process failed ($exitCode): $output" }
        return output.trimEnd()
    }
}
