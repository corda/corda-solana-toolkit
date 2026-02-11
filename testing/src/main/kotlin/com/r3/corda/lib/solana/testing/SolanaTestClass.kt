package com.r3.corda.lib.solana.testing

import com.r3.corda.lib.solana.core.SolanaClient
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Automatically starts a [SolanaTestValidator] before all the tests of a class and closes it after they have all run.
 * This is a convenience annotation for [SolanaTestValidatorExtension].
 *
 * Tests and lifecycle methods can specify a [SolanaClient] parameter which will be connected to the validator. They
 * can also specify a [SolanaTestValidator] parameter to get access to the instance itself.
 *
 * By default, the test validator uses a temporary directory for its ledger and listens on dynamically assigned ports.
 * This can be changed, or the validator further configured, by having a static method annotated with
 * [ConfigureValidator] which takes in a [SolanaTestValidator.Builder] as a single parameter.
 *
 * @property waitForReadiness By default the extension will wait for the validator to be in a state where it can
 * receive transactions. This adds some time to the test startup. This can be turned off by specifying `false`. See
 * [SolanaTestValidator.waitForReadiness].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(SolanaTestValidatorExtension::class)
annotation class SolanaTestClass(
    val waitForReadiness: Boolean = true,
)
