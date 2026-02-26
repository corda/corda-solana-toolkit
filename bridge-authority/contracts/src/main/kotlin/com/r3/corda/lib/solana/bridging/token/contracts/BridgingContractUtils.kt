package com.r3.corda.lib.solana.bridging.token.contracts

inline fun <T> List<T>.requireSingle(errorMessage: () -> Any): T {
    return requireNotNull(singleOrNull(), errorMessage)
}

val isSolanaSupported: Boolean = try {
    Class.forName("net.corda.core.solana.SolanaInstruction")
    true
} catch (_: ClassNotFoundException) {
    false
}
