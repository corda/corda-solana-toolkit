package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.core.SolanaClient
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import java.util.function.Consumer

/**
 * Automatically starts a [SolanaTestValidator] before all the tests and closes it after they have run.
 *
 * Tests and lifecycle methods can specify a [SolanaClient] parameter whiich will be connected to the validator. They
 * can also specify a [SolanaTestValidator] parameter to get access to the instance itself.
 *
 * By default, the test validator uses a temporary directory for its ledger and listens on dynamically assigned ports.
 * This can be changed, or further configured, by having a [BeforeAll] method with a [SolanaTestValidator.Builder]
 * parameter.
 *
 * If such a method is defined, then it's not possible to have a second [BeforeAll] for the final [SolanaTestValidator].
 * This is becuase it is not possible to guarantee the builder method will be called before the validator method.
 * Instead, the [BeforeAll] method must manually start the validator and pass in the instance to a second [Consumer]
 * parameter. If there is no need to access the [SolanaTestValidator] in the [BeforeAll] method then the [Consumer]
 * parameter is not required. The extension will automatically start the validator when needed.
 *
 *
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(SolanaTestValidatorExtension::class)
annotation class SolanaTestClass(
    val waitForReadiness: Boolean = true,
)
