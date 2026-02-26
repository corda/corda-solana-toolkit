package com.r3.corda.lib.solana.bridging.token

import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenBridgeContract
import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.solana.bridging.token.states.TokenAmount
import com.r3.corda.lib.solana.core.cordautils.Token2022
import com.r3.corda.lib.tokens.contracts.commands.IssueTokenCommand
import com.r3.corda.lib.tokens.contracts.commands.MoveTokenCommand
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.LedgerTransaction
import net.corda.testing.node.ledger
import org.junit.jupiter.api.Test

class MintVerificationTests {
    val bridgedFungibleTokenProxy = BridgedFungibleTokenProxy(
        TokenAmount(cordaTokenAmount.quantity, cordaTokenAmount.token.fractionDigits),
        TokenAmount(solanaTokenAmount, SOLANA_DECIMALS),
        tokenAccount.toString(),
        mintAccount.toString(),
        mintAuthority.toString(),
        bridgeAuthority
    )

    @Test
    fun `locking tokens is successful`() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenBridgeContract.CONTRACT_ID)
                input(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, bridgeAuthority)
                )
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, confidentialIdentity)
                )
                output(
                    FungibleTokenBridgeContract.CONTRACT_ID,
                    bridgedFungibleTokenProxy
                )
                command(
                    listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenBridgeContract.BridgeCommand.LockToken
                )
                verifies()
            }
        }
    }

    @Test
    fun `minting tokens is successful`() {
        services.ledger {
            transaction {
                attachment(FungibleTokenBridgeContract.CONTRACT_ID)
                input(
                    FungibleTokenBridgeContract.CONTRACT_ID,
                    bridgedFungibleTokenProxy
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenBridgeContract.BridgeCommand.MintToSolana
                )
                notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, mintAuthority, 10000))

                verifies()
            }
        }
    }

    @Test
    fun `locking tokens fails with amount related errors`() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenBridgeContract.CONTRACT_ID)
                input(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, bridgeAuthority))
                command(
                    listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenBridgeContract.BridgeCommand.LockToken
                )

                tweak {
                    output(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, confidentialIdentity))
                    output(
                        FungibleTokenBridgeContract.CONTRACT_ID,
                        bridgedFungibleTokenProxy.copyWithAmount(9999)
                    )
                    `fails with`("BridgedFungibleTokenProxy must have the same amount as the locked token")
                }

                tweak {
                    output(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, confidentialIdentity))
                    output(FungibleTokenBridgeContract.CONTRACT_ID, bridgedFungibleTokenProxy.copyWithAmount(10001))
                    `fails with`("BridgedFungibleTokenProxy must have the same amount as the locked token")
                }

                tweak {
                    val overspendCordaIssuedTokenType =
                        (10001 of TokenType("TEST", CORDA_DECIMALS)).issuedBy(tokenIssuer)
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(overspendCordaIssuedTokenType, confidentialIdentity)
                    )
                    output(FungibleTokenBridgeContract.CONTRACT_ID, bridgedFungibleTokenProxy)
                    `fails with`("In move groups the amount of input tokens MUST EQUAL the amount of output tokens")
                }

                tweak {
                    val underspendCordaIssuedTokenType =
                        (9999 of TokenType("TEST", CORDA_DECIMALS)).issuedBy(tokenIssuer)
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(underspendCordaIssuedTokenType, confidentialIdentity)
                    )
                    output(FungibleTokenBridgeContract.CONTRACT_ID, bridgedFungibleTokenProxy)
                    `fails with`("In move groups the amount of input tokens MUST EQUAL the amount of output tokens")
                }
            }
        }
    }

    @Test
    fun `locking tokens fails with command related errors`() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenBridgeContract.CONTRACT_ID)
                input(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, bridgeAuthority))
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, confidentialIdentity)
                )
                output(FungibleTokenBridgeContract.CONTRACT_ID, bridgedFungibleTokenProxy)
                tweak { `fails with`("A transaction must contain at least one command") }
                tweak {
                    command(
                        listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                        MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                    )
                    `fails with`("Bridging transactions must have a single bridge command")
                }
                tweak {
                    command(
                        listOf(bridgeAuthority.owningKey),
                        FungibleTokenBridgeContract.BridgeCommand.LockToken
                    )
                    `fails with`("There must be at least one token command in this transaction.")
                }
                tweak {
                    command(
                        listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                        MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                    )
                    command(
                        listOf(bridgeAuthority.owningKey),
                        FungibleTokenBridgeContract.BridgeCommand.LockToken
                    )
                    command(
                        listOf(bridgeAuthority.owningKey),
                        FungibleTokenBridgeContract.BridgeCommand.MintToSolana
                    )
                    `fails with`("Bridging transactions must have a single bridge command")
                }
                command(
                    listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenBridgeContract.BridgeCommand.LockToken
                )
                verifies()
            }
        }
    }

    @Test
    fun `locking tokens fails when transaction contains unrelated states or contracts`() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenBridgeContract.CONTRACT_ID)
                attachment(DummyContract.CONTRACT_ID)
                input(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, bridgeAuthority))
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, confidentialIdentity)
                )
                output(FungibleTokenBridgeContract.CONTRACT_ID, bridgedFungibleTokenProxy)
                command(
                    listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenBridgeContract.BridgeCommand.LockToken
                )

                // Add an unrelated state - this should cause the transaction to fail
                tweak {
                    output(DummyContract.CONTRACT_ID, DummyState(bridgeAuthority))
                    command(listOf(bridgeAuthority.owningKey), DummyContract.Commands.Create)
                    `fails with`("Lock transaction must only contain commands LockToken and token command (Move Token)")
                }

                verifies()
            }
        }
    }

    @Test
    fun `locking tokens fails with instruction related errors`() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenBridgeContract.CONTRACT_ID)
                input(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, bridgeAuthority))
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, confidentialIdentity)
                )
                output(FungibleTokenBridgeContract.CONTRACT_ID, bridgedFungibleTokenProxy)
                command(
                    listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenBridgeContract.BridgeCommand.LockToken
                )
                tweak {
                    notaryInstruction(Token2022.mintTo(mintAccount, mintAuthority, mintAuthority, 10000))
                    `fails with`("No Solana instructions allowed")
                }
            }
        }
    }

    @Test
    fun `minting tokens fails with amount related errors`() {
        services.ledger {
            transaction {
                attachment(FungibleTokenBridgeContract.CONTRACT_ID)
                input(FungibleTokenBridgeContract.CONTRACT_ID, bridgedFungibleTokenProxy)
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenBridgeContract.BridgeCommand.MintToSolana
                )

                tweak {
                    notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, mintAuthority, 10001))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }
                tweak {
                    notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, mintAuthority, 9999))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }

                notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, mintAuthority, 10000))

                verifies()
            }
        }
    }

    @Test
    fun `minting tokens fails with command related errors`() {
        services.ledger {
            transaction {
                attachment(FungibleTokenBridgeContract.CONTRACT_ID)
                input(FungibleTokenBridgeContract.CONTRACT_ID, bridgedFungibleTokenProxy)
                notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, mintAuthority, 10000))

                // no commands
                tweak {
                    `fails with`("A transaction must contain at least one command")
                }

                // two bridging commands
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenBridgeContract.BridgeCommand.MintToSolana,
                )
                tweak {
                    command(
                        listOf(bridgeAuthority.owningKey),
                        FungibleTokenBridgeContract.BridgeCommand.MintToSolana,
                    )
                    `fails with`("Bridging transactions must have a single bridge command")
                }

                // one bridging command, one random command
                tweak {
                    attachment(TOKEN_PROGRAM_ID)
                    command(
                        listOf(bridgeAuthority.owningKey),
                        IssueTokenCommand(cordaTokenAmount.token, emptyList())
                    )
                    `fails with`("Bridging transaction must only contain a single command")
                }

                verifies()
            }
        }
    }

    @Test
    fun `minting tokens fails with instruction related errors`() {
        services.ledger {
            transaction {
                attachment(FungibleTokenBridgeContract.CONTRACT_ID)
                input(FungibleTokenBridgeContract.CONTRACT_ID, bridgedFungibleTokenProxy)
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenBridgeContract.BridgeCommand.MintToSolana,
                )

                tweak {
                    notaryInstruction(Token2022.mintTo(mintAccount, mintAuthority, mintAuthority, 10000))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }

                tweak { `fails with`("Exactly one Solana instruction required") }

                tweak {
                    notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, mintAuthority, 10000))
                    notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, mintAuthority, 10000))
                    `fails with`("Exactly one Solana instruction required")
                }

                tweak {
                    notaryInstruction(instructionWithWrongOperation(tokenAccount))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }
                // wrong destination
                tweak {
                    notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, tokenAccount, 10000))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction")
                }
                // wrong amount
                tweak {
                    notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, mintAuthority, 1000))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }

                notaryInstruction(Token2022.mintTo(mintAccount, tokenAccount, mintAuthority, 10000))

                verifies()
            }
        }
    }

    private fun BridgedFungibleTokenProxy.copyWithAmount(cordaQuantity: Long) =
        copy(
            cordaAmount = TokenAmount(cordaQuantity, CORDA_DECIMALS),
            solanaAmount = TokenAmount(cordaQuantity * 10, SOLANA_DECIMALS)
        )
}

class DummyContract : Contract {
    companion object {
        const val CONTRACT_ID = "com.r3.corda.lib.solana.bridging.token.DummyContract"
    }

    override fun verify(tx: LedgerTransaction) {
        // No verification for testing
    }

    interface Commands : CommandData {
        object Create : Commands
    }
}

@BelongsToContract(DummyContract::class)
data class DummyState(val party: AbstractParty) : ContractState {
    override val participants: List<AbstractParty> = listOf(party)
}
