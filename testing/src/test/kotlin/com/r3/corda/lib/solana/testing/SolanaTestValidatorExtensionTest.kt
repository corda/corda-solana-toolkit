package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.core.SolanaClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

class SolanaTestValidatorExtensionTest {
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
    inner class General {
        @Test
        fun `SolanaClient available`(client: SolanaClient, testValidator: SolanaTestValidator) {
            assertThat(client).isSameAs(testValidator.client())
        }
    }

    @Nested
    @SolanaTestClass(waitForReadiness = false)
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
    inner class AssertDifferentValidatorInstancePerClass {
        @Test
        fun test(testValidator: SolanaTestValidator) {
            assertDifferentValidatorInstancePerClassValidator = testValidator
        }
    }
}
