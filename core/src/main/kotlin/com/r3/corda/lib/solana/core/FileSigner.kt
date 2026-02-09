package com.r3.corda.lib.solana.core

import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A [Signer] based on the Solana filesystem wallet format (https://docs.solanalabs.com/cli/wallets/file-system).
 *
 * NOTE: Changes to the underlying file will not be reflected in the [FileSigner] instance.
 */
class FileSigner private constructor(val file: Path, private val signer: Signer) : Signer by signer {
    companion object {
        /**
         * Read the given wallet keypair file.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun read(file: Path): FileSigner {
            val content = file.readText().trim()
            require(content.startsWith("[") && content.endsWith("]"))
            val keyPairBytes = content
                .substring(1, content.length - 1)
                .splitToSequence(",")
                .map {
                    val byte = it.trim().toInt()
                    require(byte in 0..255)
                    byte.toByte()
                }
                .toList()
            require(keyPairBytes.size == Signer.KEY_LENGTH + PublicKey.PUBLIC_KEY_LENGTH)
            val privateKeyBytes = keyPairBytes.subList(0, Signer.KEY_LENGTH).toByteArray()
            val signer = Signer.createFromPrivateKey(privateKeyBytes)
            return FileSigner(file, signer)
        }

        /**
         * Generate a random [FileSigner] in the given directory. The created file will have the filename format
         * `<base58Address>.json`.
         *
         * This is similar to the CLI command:
         *
         * ```
         * solana-keygen new --no-bip39-passphrase
         * ```
         */
        @JvmStatic
        @Throws(IOException::class)
        fun random(dir: Path): FileSigner {
            val privateKey = Signer.generatePrivateKeyBytes()
            val signer = Signer.createFromPrivateKey(privateKey)
            val file = dir / "${signer.publicKey()}.json"
            file.writeText(
                buildString(256) {
                    append('[')
                    appendUBytes(privateKey)
                    append(',')
                    appendUBytes(signer.publicKey().toByteArray())
                    append(']')
                }
            )
            return FileSigner(file, signer)
        }

        private fun StringBuilder.appendUBytes(bytes: ByteArray) {
            for (i in bytes.indices) {
                append(bytes[i].toUByte().toInt())
                if (i < bytes.size - 1) {
                    append(',')
                }
            }
        }
    }

    override fun createDedicatedSigner(): FileSigner = FileSigner(file, signer.createDedicatedSigner())
}
