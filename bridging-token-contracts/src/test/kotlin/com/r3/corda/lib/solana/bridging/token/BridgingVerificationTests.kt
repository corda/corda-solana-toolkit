package com.r3.corda.lib.solana.bridging.token

import com.r3.corda.lib.solana.bridging.token.contracts.MintContract
import com.r3.corda.lib.solana.bridging.token.contracts.MintContract.Companion.contractId
import com.r3.corda.lib.solana.bridging.token.states.MintState
import com.r3.corda.lib.tokens.contracts.FungibleTokenContract
import com.r3.corda.lib.tokens.contracts.commands.IssueTokenCommand
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
import net.corda.core.utilities.OpaqueBytes
import net.corda.solana.aggregator.common.Signer
import net.corda.solana.sdk.instruction.AccountMeta
import net.corda.solana.sdk.instruction.Pubkey
import net.corda.solana.sdk.instruction.SolanaInstruction
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BridgingVerificationTests {
    companion object {
        val tokenIssuer = Party(
            CordaX500Name("Alice", "Lodz", "PL"),
            Crypto.generateKeyPair().public
        )
        val bridgeAuthorityParty = Party(
            CordaX500Name("Bridge Authority", "Frankfurt", "DE"),
            Crypto.generateKeyPair().public
        )
        val confidentialIdentity = AnonymousParty(Crypto.generateKeyPair().public)
        val services = MockServices(
            cordappPackages = listOf("com.r3.corda.lib.solana.bridging.token"),
            initialIdentity = TestIdentity(bridgeAuthorityParty.name),
            identityService = mock<IdentityService>(),
            networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
        )
        val TOKEN_PROGRAM_ID: String = FungibleTokenContract.contractId
    }

    val cordaIssuedTokenType = (10000 of TokenType("TEST", 0)).issuedBy(tokenIssuer)

    val mint = Pubkey(Signer.random().account.bytes())
    val mintAuthority = Pubkey(Signer.random().account.bytes())
    val tokenAccount = Pubkey(Signer.random().account.bytes())

    val mintState = MintState(
        tokenIssuer,
        10000,
        false,
        tokenAccount,
        mint,
        mintAuthority,
        bridgeAuthorityParty
    )

    @Test
    fun successfulLockVerification() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(contractId)
                input(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaIssuedTokenType, bridgeAuthorityParty)
                )
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                )
                output(
                    contractId,
                    mintState
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    MintContract.MintCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
                )

                verifies()
            }
        }
    }

    @Test
    fun successfulMintVerification() {
        services.ledger {
            transaction {
                attachment(contractId)
                input(
                    contractId,
                    mintState
                )
                output(
                    contractId,
                    mintState.copy(minted = true, bridgeAuthority = bridgeAuthorityParty)
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    MintContract.MintCommand.MintToSolana
                )
                notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))

                verifies()
            }
        }
    }

    @Suppress("LongMethod")
    @Test
    fun lockAmountErrors() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(contractId)
                input(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaIssuedTokenType, bridgeAuthorityParty)
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    MintContract.MintCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
                )

                tweak {
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                    )
                    output(
                        contractId,
                        mintState.copy(amount = 9999)
                    )
                    `fails with`("BridgedFungibleTokenProxy must have the same amount as the locked token")
                }

                tweak {
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                    )
                    output(
                        contractId,
                        mintState.copy(amount = 10001)
                    )
                    `fails with`("BridgedFungibleTokenProxy must have the same amount as the locked token")
                }

                tweak {
                    val overspendCordaIssuedTokenType = (10001 of TokenType("TEST", 0)).issuedBy(tokenIssuer)
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(overspendCordaIssuedTokenType, confidentialIdentity)
                    )
                    output(
                        contractId,
                        mintState
                    )
                    `fails with`("In move groups the amount of input tokens MUST EQUAL the amount of output tokens")
                }

                tweak {
                    val underspendCordaIssuedTokenType = (9999 of TokenType("TEST", 0)).issuedBy(tokenIssuer)
                    output(
                        TOKEN_PROGRAM_ID,
                        FungibleToken(underspendCordaIssuedTokenType, confidentialIdentity)
                    )
                    output(
                        contractId,
                        mintState
                    )
                    `fails with`("In move groups the amount of input tokens MUST EQUAL the amount of output tokens")
                }
            }
        }
    }

    @Suppress("LongMethod")
    @Test
    fun lockCommandErrors() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(contractId)
                input(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaIssuedTokenType, bridgeAuthorityParty)
                )
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                )
                output(
                    contractId,
                    mintState
                )
                tweak {
                    `fails with`("A transaction must contain at least one command")
                }
                tweak {
                    command(
                        listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                        MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                    )
                    `fails with`("Bridging transactions must have a single bridging command")
                }
                tweak {
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        MintContract.MintCommand.LockToken(
                            bridgeAuthorityParty,
                            confidentialIdentity
                        )
                    )
                    `fails with`("There must be at least one token command in this transaction.")
                }
                tweak {
                    command(
                        listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                        MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                    )
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        MintContract.MintCommand.LockToken(
                            bridgeAuthorityParty,
                            confidentialIdentity
                        )
                    )
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        MintContract.MintCommand.MintToSolana
                    )
                    `fails with`("Bridging transactions must have a single bridging command")
                }
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    MintContract.MintCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
                )
                verifies()
            }
        }
    }

    // TODO test with a surplus dummy unrelated state and contracts

    @Test
    fun lockInstructionError() {
        services.ledger {
            transaction {
                attachment(TOKEN_PROGRAM_ID)
                attachment(contractId)
                input(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaIssuedTokenType, bridgeAuthorityParty)
                )
                output(
                    TOKEN_PROGRAM_ID,
                    FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                )
                output(
                    contractId,
                    mintState
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    MintContract.MintCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
                )
                tweak {
                    notaryInstruction(Token2022.mintTo(mint, mintAuthority, mintAuthority, 10000))
                    `fails with`("No Solana instructions allowed")
                }
            }
        }
    }

    @Test
    fun mintAmountErrors() {
        services.ledger {
            transaction {
                attachment(contractId)
                input(
                    contractId,
                    mintState
                )
                output(
                    contractId,
                    mintState.copy(minted = true)
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    MintContract.MintCommand.MintToSolana
                )

                tweak {
                    notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10001))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }
                tweak {
                    notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 9999))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }

                notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))

                verifies()
            }
        }
    }

    @Test
    fun mintCommandErrors() {
        services.ledger {
            transaction {
                attachment(contractId)
                input(
                    contractId,
                    mintState
                )
                output(
                    contractId,
                    mintState.copy(minted = true)
                )
                notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))

                // no commands
                tweak {
                    `fails with`("A transaction must contain at least one command")
                }

                // two bridging commands
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    MintContract.MintCommand.MintToSolana,
                )
                tweak {
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        MintContract.MintCommand.MintToSolana,
                    )
                    `fails with`("Bridging transactions must have a single bridging command")
                }

                // one bridging command, one random command
                tweak {
                    attachment(TOKEN_PROGRAM_ID)
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        IssueTokenCommand(cordaIssuedTokenType.token, emptyList())
                    )
                    `fails with`("Bridging transaction must only contain a single command")
                }

                verifies()
            }
        }
    }

    @Test
    fun mintInstructionErrors() {
        services.ledger {
            transaction {
                attachment(contractId)
                input(
                    contractId,
                    mintState
                )
                output(
                    contractId,
                    mintState.copy(minted = true)
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    MintContract.MintCommand.MintToSolana,
                )

                tweak {
                    notaryInstruction(Token2022.mintTo(mint, mintAuthority, mintAuthority, 10000))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }

                tweak {
                    `fails with`("Exactly one Solana instruction required")
                }

                tweak {
                    notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))
                    notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))
                    `fails with`("Exactly one Solana instruction required")
                }

                tweak {
                    notaryInstruction(instructionWithWrongOperation(tokenAccount))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }
                // wrong destination
                tweak {
                    Token2022.mintTo(mint, tokenAccount, tokenAccount, 10000)
                    `fails with`("Exactly one Solana instruction required")
                }
                // wrong amount
                tweak {
                    notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 1000))
                    `fails with`("Solana instruction in the transaction not the expected mint instruction:")
                }

                notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))

                verifies()
            }
        }
    }

    private fun instructionWithWrongOperation(programId: Pubkey): SolanaInstruction {
        val operation = 6
        val amount: Long = 10000
        val destination = Token2022.PROGRAM_ID
        val data = ByteBuffer
            .allocate(9)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(operation.toByte())
            .putLong(amount)
            .array()
        return SolanaInstruction(
            programId,
            listOf(
                AccountMeta(mint, isSigner = false, isWritable = true),
                AccountMeta(destination, isSigner = false, isWritable = true),
                AccountMeta(mintAuthority, isSigner = true, isWritable = false),
            ),
            OpaqueBytes(data)
        )
    }
}
