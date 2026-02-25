package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.bridging.token.testing.QuerySimpleTokensFlow
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrThrow
import com.r3.corda.lib.tokens.workflows.utilities.tokenAmountsByToken
import net.corda.core.identity.Party
import net.corda.testing.node.StartedMockNode
import java.math.BigDecimal

fun StartedMockNode.getAllFungibleTokens(issuer: Party, stock: TokenType): List<FungibleToken> {
    val fungibleTokenType = this
        .startFlow(QuerySimpleTokensFlow(issuer, stock))
        .get()
        .firstOrNull() ?: return emptyList()
    return this.services.vaultService
        .tokenAmountsByToken(fungibleTokenType.state.data.tokenType)
        .states
        .map { it.state.data }
}

fun StartedMockNode.myTokenBalance(issuer: Party, tokenType: TokenType): BigDecimal {
    val myIdentity = this.services.myInfo.legalIdentities.first()
    val fungibleTokens = getAllFungibleTokens(issuer, tokenType).filter { it.holder == myIdentity }
    return if (fungibleTokens.isEmpty()) {
        BigDecimal.ZERO
    } else {
        fungibleTokens
            .sumTokenStatesOrThrow()
            .toDecimal()
    }
}
