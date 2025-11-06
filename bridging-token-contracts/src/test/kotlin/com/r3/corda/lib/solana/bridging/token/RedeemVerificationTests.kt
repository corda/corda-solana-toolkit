package com.r3.corda.lib.solana.bridging.token

import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract.Companion.CONTRACT_ID
import com.r3.corda.lib.solana.bridging.token.states.RedeemedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.commands.IssueTokenCommand
import com.r3.corda.lib.tokens.contracts.commands.MoveTokenCommand
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.node.ledger
import org.junit.jupiter.api.Test
import java.util.*

class RedeemVerificationTests {
    val redeemState = RedeemedFungibleTokenProxy(
        aliceRedemptionTokenAccount,
        bridgeAuthorityWallet,
        mint,
        10000,
        cordaTokenAmount.token.tokenIdentifier,
        aliceParty,
        bridgeAuthorityParty,
        UUID.randomUUID()
    )

    @Test
    fun successVerifyIssueRedeemState() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(CONTRACT_ID)
                input(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, confidentialIdentityParty)
                )
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, bridgeAuthorityParty)
                )
                output(
                    CONTRACT_ID,
                    redeemState
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentityParty.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentityParty)
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
                attachment(CONTRACT_ID)
                input(
                    CONTRACT_ID,
                    redeemState
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana()
                )
                notaryInstruction(
                    Token2022.burn(mint, aliceRedemptionTokenAccount, bridgeAuthorityWallet, 10000)
                )
                verifies()
            }
        }
    }

    @Suppress("LongMethod")
    @Test
    fun issueRedeemStateAmountErrors() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(CONTRACT_ID)
                input(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, confidentialIdentityParty)
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentityParty.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentityParty)
                )
                tweak {
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(cordaTokenAmount, bridgeAuthorityParty)
                    )
                    output(
                        CONTRACT_ID,
                        redeemState.copy(amount = 9999)
                    )
                    `fails with`("The amount in the RedeemState must match the amount in the FungibleToken state")
                }
                tweak {
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(cordaTokenAmount, bridgeAuthorityParty)
                    )
                    output(
                        CONTRACT_ID,
                        redeemState.copy(amount = 10001)
                    )
                    `fails with`("The amount in the RedeemState must match the amount in the FungibleToken state")
                }
                tweak {
                    val overspendCordaIssuedTokenType = (10001 of TokenType("TEST", 0)).issuedBy(aliceParty)
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(overspendCordaIssuedTokenType, bridgeAuthorityParty)
                    )
                    output(
                        CONTRACT_ID,
                        redeemState
                    )
                    `fails with`("In move groups the amount of input tokens MUST EQUAL the amount of output tokens")
                }
                tweak {
                    val underspendCordaIssuedTokenType = (9999 of TokenType("TEST", 0)).issuedBy(aliceParty)
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(underspendCordaIssuedTokenType, bridgeAuthorityParty)
                    )
                    output(
                        CONTRACT_ID,
                        redeemState
                    )
                    `fails with`("In move groups the amount of input tokens MUST EQUAL the amount of output tokens")
                }
            }
        }
    }

    @Suppress("LongMethod")
    @Test
    fun issueRedeemStateCommandErrors() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(CONTRACT_ID)
                input(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, confidentialIdentityParty)
                )
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, bridgeAuthorityParty)
                )
                output(
                    CONTRACT_ID,
                    redeemState
                )
                tweak {
                    `fails with`("A transaction must contain at least one command")
                }
                tweak {
                    command(
                        listOf(bridgeAuthorityParty.owningKey, confidentialIdentityParty.owningKey),
                        MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                    )
                    `fails with`("Redeem transactions must have single redeem command")
                }
                tweak {
                    command(
                        listOf(confidentialIdentityParty.owningKey),
                        FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentityParty)
                    )
                    `fails with`("There must be at least one token command in this transaction.")
                }
                tweak {
                    command(
                        listOf(bridgeAuthorityParty.owningKey, confidentialIdentityParty.owningKey),
                        MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                    )
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentityParty)
                    )
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana()
                    )
                    `fails with`("Redeem transactions must have single redeem command")
                }
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentityParty.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentityParty)
                )
                verifies()
            }
        }
    }

    @Test
    fun issueRedeemStateInstructionError() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(CONTRACT_ID)
                input(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, confidentialIdentityParty)
                )
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaTokenAmount, bridgeAuthorityParty)
                )
                output(
                    CONTRACT_ID,
                    redeemState
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentityParty.owningKey),
                    MoveTokenCommand(cordaTokenAmount.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(confidentialIdentityParty)
                )
                tweak {
                    notaryInstruction(
                        Token2022.burn(mint, aliceRedemptionTokenAccount, bridgeAuthorityWallet, 10000)
                    )
                    `fails with`("No Solana instructions allowed")
                }
            }
        }
    }

    @Test
    fun burnAmountErrors() {
        services.ledger {
            transaction {
                attachment(CONTRACT_ID)
                input(
                    CONTRACT_ID,
                    redeemState
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana()
                )

                tweak {
                    notaryInstruction(Token2022.burn(mint, aliceRedemptionTokenAccount, bridgeAuthorityWallet, 10001))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }
                tweak {
                    notaryInstruction(Token2022.burn(mint, aliceRedemptionTokenAccount, bridgeAuthorityWallet, 9999))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }

                notaryInstruction(Token2022.burn(mint, aliceRedemptionTokenAccount, bridgeAuthorityWallet, 10000))

                verifies()
            }
        }
    }

    @Test
    fun burnCommandErrors() {
        services.ledger {
            transaction {
                attachment(CONTRACT_ID)
                input(
                    CONTRACT_ID,
                    redeemState
                )
                notaryInstruction(Token2022.burn(mint, aliceRedemptionTokenAccount, bridgeAuthorityWallet, 10000))

                // no commands
                tweak {
                    `fails with`("A transaction must contain at least one command")
                }

                // two bridging commands
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana(),
                )
                tweak {
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana(),
                    )
                    `fails with`("Redeem transactions must have single redeem command")
                }

                // one bridging command, one random command
                tweak {
                    attachment(TOKEN_PROGRAM_ID)
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        IssueTokenCommand(cordaTokenAmount.token, emptyList())
                    )
                    `fails with`("BurnOnSolana transaction must only contain a single command")
                }

                verifies()
            }
        }
    }

    @Test
    fun burnInstructionErrors() {
        services.ledger {
            transaction {
                attachment(CONTRACT_ID)
                input(
                    CONTRACT_ID,
                    redeemState
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana(),
                )

                tweak {
                    notaryInstruction(Token2022.burn(mint, mintAuthority, mintAuthority, 10000))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }

                tweak {
                    `fails with`("Exactly one Solana instruction required")
                }

                tweak {
                    notaryInstruction(Token2022.burn(mint, aliceRedemptionTokenAccount, bridgeAuthorityWallet, 10000))
                    notaryInstruction(Token2022.burn(mint, aliceRedemptionTokenAccount, bridgeAuthorityWallet, 10000))
                    `fails with`("Exactly one Solana instruction required")
                }

                tweak {
                    notaryInstruction(instructionWithWrongOperation(aliceMintTokenAccount))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }
                // wrong destination
                tweak {
                    Token2022.burn(mint, aliceMintTokenAccount, bridgeAuthorityWallet, 10000)
                    `fails with`("Exactly one Solana instruction required")
                }
                // wrong amount
                tweak {
                    notaryInstruction(Token2022.burn(mint, aliceMintTokenAccount, bridgeAuthorityWallet, 9999))
                    `fails with`("The Solana instruction in the transaction not the expected burn instruction:")
                }

                notaryInstruction(Token2022.burn(mint, aliceRedemptionTokenAccount, bridgeAuthorityWallet, 10000))

                verifies()
            }
        }
    }
}
