package com.r3.corda.lib.solana.bridging.token.contracts

import com.r3.corda.lib.solana.bridging.token.states.TokenAmount
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import net.corda.core.contracts.Amount

inline fun <T> List<T>.requireSingle(errorMessage: () -> Any): T {
    return requireNotNull(singleOrNull(), errorMessage)
}

fun Amount<IssuedTokenType>.toTokenAmount() = TokenAmount(quantity, token.fractionDigits)
