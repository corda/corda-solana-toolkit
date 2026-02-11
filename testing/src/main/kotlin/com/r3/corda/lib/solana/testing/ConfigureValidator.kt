package com.r3.corda.lib.solana.testing

/**
 * Annotated on a method for configuring the [SolanaTestValidator] instance provided by [SolanaTestClass].
 * The method must be public static void and have a single [SolanaTestValidator.Builder] parameter.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigureValidator
