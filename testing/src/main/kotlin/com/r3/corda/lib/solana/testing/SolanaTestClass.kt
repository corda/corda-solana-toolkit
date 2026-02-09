package com.r3.corda.lib.solana.testing

import org.junit.jupiter.api.extension.ExtendWith

/**
 *
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(SolanaTestValidatorExtension::class)
annotation class SolanaTestClass(
    val waitForReadiness: Boolean = true,
)
