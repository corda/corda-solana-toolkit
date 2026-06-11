package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.core.SolanaClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SolanaTestValidatorExtensionTest {
    @SolanaTestClass(waitForReadiness = false)
    class NotWaitForReadiness {
        companion object {
            @TempDir
            lateinit var tempDir: Path

            @Suppress("unused")
            @ConfigureValidator
            @JvmStatic
            fun configureValidator(builder: SolanaTestValidator.Builder) {
                builder.ledger(tempDir)
            }
        }

        @Test
        fun `SolanaClient available and started`(client: SolanaClient, testValidator: SolanaTestValidator) {
            assertThat(client).isSameAs(testValidator.client())
            assertThat(client.isStarted).isTrue
            assertThat(client.getBlockhashInfo(forceFetch = true)).isNotNull
        }

        @Test
        fun `@ConfigureValidator annotation`(testValidator: SolanaTestValidator) {
            assertThat(testValidator.ledger()).isEqualTo(tempDir)
        }

        @Test
        fun `waitForReadiness later`(testValidator: SolanaTestValidator) {
            testValidator.waitForReadiness()
        }
    }

    companion object {
        private var assertSameValidatorInstancePerTestValidator: SolanaTestValidator? = null
        private var assertDifferentValidatorInstancePerClassValidator: SolanaTestValidator? = null

        @AfterAll
        @JvmStatic
        fun `false-positive guard`() {
            assertThat(assertSameValidatorInstancePerTestValidator).isNotNull
            assertThat(assertDifferentValidatorInstancePerClassValidator).isNotNull
        }

        @AfterAll
        @JvmStatic
        fun `assert different validator instances per class`() {
            assertThat(assertSameValidatorInstancePerTestValidator)
                .isNotSameAs(assertDifferentValidatorInstancePerClassValidator)
        }
    }

    @Nested
    @SolanaTestClass(waitForReadiness = false)
    @DisplayName("assert same validator instance per test")
    inner class AssertSameValidatorInstancePerTest {
        @RepeatedTest(2)
        fun test(testValidator: SolanaTestValidator) {
            if (assertSameValidatorInstancePerTestValidator == null) {
                assertSameValidatorInstancePerTestValidator = testValidator
            } else {
                assertThat(testValidator).isSameAs(assertSameValidatorInstancePerTestValidator)
            }
        }
    }

    @Nested
    @SolanaTestClass(waitForReadiness = false)
    @DisplayName("assert different validator instance per class")
    inner class AssertDifferentValidatorInstancePerClass {
        @Test
        fun captureInstance(testValidator: SolanaTestValidator) {
            assertDifferentValidatorInstancePerClassValidator = testValidator
        }
    }

    /**
     * Programmatic registration via [SolanaTestValidatorExtension.builder] and a static `@RegisterExtension` field.
     */
    class ProgrammaticRegistration {
        companion object {
            @JvmStatic
            @TempDir
            lateinit var tempDir: Path

            @JvmStatic
            @RegisterExtension
            val solana: SolanaTestValidatorExtension = SolanaTestValidatorExtension.builder()
                .waitForReadiness(false)
                .configureValidator { it.ledger(tempDir) }
                .build()

            @JvmStatic
            @RegisterExtension
            val cooperating = ValidatorCapturingExtension(solana)
        }

        @Test
        fun `builder configuration is applied and the client is started`(
            client: SolanaClient,
            testValidator: SolanaTestValidator,
        ) {
            assertThat(client).isSameAs(testValidator.client())
            assertThat(client.isStarted).isTrue
            assertThat(testValidator.ledger()).isEqualTo(tempDir)
        }

        @Test
        fun `cooperating extension sees the same validator instance`(testValidator: SolanaTestValidator) {
            assertThat(cooperating.captured).isSameAs(testValidator)
        }
    }

    /** Stand-in for a real cooperating extension (e.g. one that bootstraps fixtures on the shared validator). */
    class ValidatorCapturingExtension(private val solana: SolanaTestValidatorExtension) : BeforeEachCallback {
        var captured: SolanaTestValidator? = null
            private set

        override fun beforeEach(context: ExtensionContext) {
            captured = solana.getValidator(context)
        }
    }
}
