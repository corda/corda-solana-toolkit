package com.r3.corda.lib.solana.bridging.token

import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import com.r3.corda.lib.solana.bridging.token.states.FungibleTokenBurnReceipt
import com.r3.corda.lib.tokens.contracts.commands.IssueTokenCommand
import com.r3.corda.lib.tokens.contracts.commands.MoveTokenCommand
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.node.ledger
import org.junit.jupiter.api.Test

class RedeemVerificationTests {
    val redeemState = FungibleTokenBurnReceipt(
        tokenAccount,
        bridgeAuthorityWallet,
        mintAccount,
        10000,
        bridgeAuthority
    )

    @Test
    fun successVerifyUnlockToken() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenRedemptionContract.CONTRACT_ID)
                input(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, confidentialIdentity))
                output(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, bridgeAuthority))
                input(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState)
                command(
                    listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentity)
                )
                verifies()
            }
        }
    }

    @Test
    fun successVerifyBurnOnSolana() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenRedemptionContract.CONTRACT_ID)
                output(
                    FungibleTokenRedemptionContract.CONTRACT_ID,
                    redeemState
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana
                )
                notaryInstruction(
                    Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 10000)
                )
                verifies()
            }
        }
    }

    @Test
    fun unlockTokenAmountErrors() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenRedemptionContract.CONTRACT_ID)
                input(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, confidentialIdentity))
                command(
                    listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentity)
                )
                tweak {
                    output(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, bridgeAuthority))
                    input(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState.copy(amount = 9999))
                    `fails with`(
                        "The amount in the FungibleTokenBurnReceipt must match the amount in the FungibleToken state"
                    )
                }
                tweak {
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(cordaTokenAmount, bridgeAuthority)
                    )
                    input(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState.copy(amount = 10001))
                    `fails with`(
                        "The amount in the FungibleTokenBurnReceipt must match the amount in the FungibleToken state"
                    )
                }
                tweak {
                    val overspendCordaIssuedTokenType = (10001 of TokenType("TEST", 0)).issuedBy(tokenIssuer)
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(overspendCordaIssuedTokenType, bridgeAuthority)
                    )
                    input(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState)
                    `fails with`("In move groups the amount of input tokens MUST EQUAL the amount of output tokens")
                }
                tweak {
                    val underspendCordaIssuedTokenType = (9999 of TokenType("TEST", 0)).issuedBy(tokenIssuer)
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(underspendCordaIssuedTokenType, bridgeAuthority)
                    )
                    input(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState)
                    `fails with`("In move groups the amount of input tokens MUST EQUAL the amount of output tokens")
                }
            }
        }
    }

    @Test
    fun unlockTokenCommandErrors() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenRedemptionContract.CONTRACT_ID)
                input(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, confidentialIdentity))
                output(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, bridgeAuthority))
                input(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState)
                tweak { `fails with`("A transaction must contain at least one command") }
                tweak {
                    command(
                        listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                        MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                    )
                    `fails with`("Redeem transactions must have single redeem command")
                }
                tweak {
                    command(
                        listOf(confidentialIdentity.owningKey),
                        FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentity)
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
                        FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentity)
                    )
                    command(
                        listOf(bridgeAuthority.owningKey),
                        FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana
                    )
                    `fails with`("Redeem transactions must have single redeem command")
                }
                command(
                    listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentity)
                )
                verifies()
            }
        }
    }

    @Test
    fun unlockTokenInstructionError() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(FungibleTokenRedemptionContract.CONTRACT_ID)
                input(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, confidentialIdentity))
                output(TOKEN_PROGRAM_ID, FungibleToken(cordaTokenAmount, bridgeAuthority))
                input(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState)
                command(
                    listOf(bridgeAuthority.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentity)
                )
                tweak {
                    notaryInstruction(
                        Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 10000)
                    )
                    `fails with`("No Solana instructions allowed")
                }
            }
        }
    }

    @Test
    fun burnOnSolanaAmountErrors() {
        services.ledger {
            transaction {
                attachment(FungibleTokenRedemptionContract.CONTRACT_ID)
                output(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState)
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana
                )

                tweak {
                    notaryInstruction(Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 10001))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }
                tweak {
                    notaryInstruction(Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 9999))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }

                notaryInstruction(Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 10000))

                verifies()
            }
        }
    }

    @Test
    fun burnOnSolanaCommandErrors() {
        services.ledger {
            transaction {
                attachment(FungibleTokenRedemptionContract.CONTRACT_ID)
                output(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState)
                notaryInstruction(Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 10000))

                // no commands
                tweak { `fails with`("A transaction must contain at least one command") }

                // two bridging commands
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana,
                )
                tweak {
                    command(
                        listOf(bridgeAuthority.owningKey),
                        FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana,
                    )
                    `fails with`("Redeem transactions must have single redeem command")
                }

                // one bridging command, one random command
                tweak {
                    attachment(TOKEN_PROGRAM_ID)
                    command(
                        listOf(bridgeAuthority.owningKey),
                        IssueTokenCommand(cordaTokenAmount.token, emptyList())
                    )
                    `fails with`("BurnOnSolana transaction must only contain a single command")
                }

                verifies()
            }
        }
    }

    @Test
    fun burnOnSolanaInstructionErrors() {
        services.ledger {
            transaction {
                attachment(FungibleTokenRedemptionContract.CONTRACT_ID)
                output(FungibleTokenRedemptionContract.CONTRACT_ID, redeemState)
                command(
                    listOf(bridgeAuthority.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana,
                )

                tweak {
                    notaryInstruction(Token2022.burn(mintAccount, mintAuthority, mintAuthority, 10000))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }

                tweak { `fails with`("Exactly one Solana instruction required") }

                tweak {
                    notaryInstruction(Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 10000))
                    notaryInstruction(Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 10000))
                    `fails with`("Exactly one Solana instruction required")
                }

                tweak {
                    notaryInstruction(instructionWithWrongOperation(tokenAccount))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }
                // wrong owner
                tweak {
                    notaryInstruction(Token2022.burn(mintAccount, tokenAccount, tokenAccount, 10000))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }
                // wrong amount
                tweak {
                    notaryInstruction(Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 9999))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }

                notaryInstruction(Token2022.burn(mintAccount, tokenAccount, bridgeAuthorityWallet, 10000))

                verifies()
            }
        }
    }
}
