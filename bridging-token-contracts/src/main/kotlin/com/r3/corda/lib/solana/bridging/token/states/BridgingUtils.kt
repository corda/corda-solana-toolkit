package com.r3.corda.lib.solana.bridging.token.states

import net.corda.core.contracts.Amount

fun Amount<*>.toDecimalAmount() = toDecimal().toLong()
