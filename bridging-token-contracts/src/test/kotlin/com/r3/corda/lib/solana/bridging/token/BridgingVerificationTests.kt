package com.r3.corda.lib.solana.bridging.token

import com.r3.corda.lib.solana.bridging.token.contracts.BridgingContract
import com.r3.corda.lib.solana.bridging.token.contracts.BridgingContract.Companion.BRIDGE_PROGRAM_ID
import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.FungibleTokenContract.Companion.contractId
import com.r3.corda.lib.tokens.contracts.commands.MoveTokenCommand
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import net.corda.core.crypto.Crypto
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.IdentityService
import net.corda.solana.aggregator.common.Signer
import net.corda.solana.sdk.instruction.Pubkey
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class BridgingVerificationTests {
    companion object {
        val tokenIssuer = Party(CordaX500Name("Alice", "Lodz", "PL"), Crypto.generateKeyPair().public)
        val bridgeParty = Party(CordaX500Name("Bridge Authority", "Frankfurt", "DE"), Crypto.generateKeyPair().public)
        val confidentialIdentity = AnonymousParty(Crypto.generateKeyPair().public)
        val services = MockServices(
            cordappPackages = listOf("net.corda.finance.contracts.asset"),
            initialIdentity = TestIdentity(bridgeParty.name),
            identityService = mock<IdentityService>(),
            networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
        )
    }

    val cordaIssuedTokenType = (100 of TokenType("TEST", 0)).issuedBy(tokenIssuer)

    val mint = Pubkey(Signer.random().account.bytes())
    val mintAuthority = Pubkey(Signer.random().account.bytes())
    val tokenAccount = Pubkey(Signer.random().account.bytes())

    @Test
    fun successfulLockVerification() {
        services.ledger {
            transaction {
                attachment(contractId)
                attachment(BRIDGE_PROGRAM_ID)
                input(
                    contractId,
                    FungibleToken(cordaIssuedTokenType, bridgeParty)
                )
                output(
                    contractId,
                    FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                )
                output(
                    BRIDGE_PROGRAM_ID,
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(100, false, tokenAccount, mint, mintAuthority, listOf(bridgeParty))
                )
                command(
                    listOf(bridgeParty.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeParty.owningKey),
                    BridgingContract.BridgingCommand.LockToken(bridgeParty, confidentialIdentity)
                )
                notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 100))

                verifies()
            }
        }
    }

    @Test
    fun successfulMintVerification() {
        services.ledger {
            transaction {
                attachment(BRIDGE_PROGRAM_ID)
                input(
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(100, false, tokenAccount, mint, mintAuthority, emptyList())
                )
                output(
                    BRIDGE_PROGRAM_ID,
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(100, true, tokenAccount, mint, mintAuthority, emptyList())
                )
                command(listOf(bridgeParty.owningKey), BridgingContract.BridgingCommand.MintToSolana)
                notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 100))

                verifies()
            }
        }
    }
}
