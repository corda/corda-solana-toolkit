package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.core.SolanaClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SolanaTestValidatorExtensionTest {
    @SolanaTestClass(waitForReadiness = false)
    class General {
        companion object {
            @TempDir
            lateinit var tempDir: Path

            @ConfigureValidator
            @JvmStatic
            fun configureValidator(builder: SolanaTestValidator.Builder) {
                builder.ledger(tempDir)
            }
        }

        @Test
        fun `SolanaClient available`(client: SolanaClient, testValidator: SolanaTestValidator) {
            assertThat(client).isSameAs(testValidator.client())
        }

        @Test
        fun `@ConfigureValidator annotation`(testValidator: SolanaTestValidator) {
            assertThat(testValidator.ledger()).isEqualTo(tempDir)
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
}
