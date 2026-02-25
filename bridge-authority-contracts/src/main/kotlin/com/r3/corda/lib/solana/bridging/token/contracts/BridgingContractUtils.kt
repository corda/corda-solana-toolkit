package com.r3.corda.lib.solana.bridging.token.contracts

inline fun <T> List<T>.requireSingle(errorMessage: () -> Any): T {
    return requireNotNull(singleOrNull(), errorMessage)
}
