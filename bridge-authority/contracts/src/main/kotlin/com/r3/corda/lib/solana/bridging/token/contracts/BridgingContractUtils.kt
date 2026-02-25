package com.r3.corda.lib.solana.bridging.token.contracts

inline fun <T> List<T>.requireSingle(errorMessage: () -> Any): T {
    return requireNotNull(singleOrNull(), errorMessage)
}

private val solanaInstructionClass: Class<*>? = try {
    Class.forName("net.corda.core.solana.SolanaInstruction")
} catch (_: ClassNotFoundException) {
    null
}

val isSolanaInstructionOnClasspath: Boolean = solanaInstructionClass != null
