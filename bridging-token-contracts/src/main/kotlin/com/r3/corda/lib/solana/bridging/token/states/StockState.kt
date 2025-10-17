package com.r3.corda.lib.solana.bridging.token.states

import com.r3.corda.lib.solana.bridging.token.contracts.StockContract
import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import net.corda.core.schemas.StatePersistable
import java.math.BigDecimal

// *********
// * State *
// *********
@BelongsToContract(StockContract::class)
data class StockState(
    val issuer: Party,
    val symbol: String,
    val name: String,
    val currency: String,
    val price: BigDecimal,
    override val linearId: UniqueIdentifier,
    override val fractionDigits: Int = 0,
    override val maintainers: List<Party> = listOf(issuer),
) : EvolvableTokenType(),
    StatePersistable
