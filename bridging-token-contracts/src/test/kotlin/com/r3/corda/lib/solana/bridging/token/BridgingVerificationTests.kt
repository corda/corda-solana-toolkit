package com.r3.corda.lib.solana.bridging.token

import com.lmax.solana4j.programs.TokenProgramBase
import com.r3.corda.lib.solana.bridging.token.contracts.BridgingContract
import com.r3.corda.lib.solana.bridging.token.contracts.BridgingContract.Companion.BRIDGE_PROGRAM_ID
import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.FungibleTokenContract.Companion.contractId
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
            cordappPackages = listOf("net.corda.finance.contracts.asset"),
            initialIdentity = TestIdentity(bridgeAuthorityParty.name),
            identityService = mock<IdentityService>(),
            networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
        )
    }

    val cordaIssuedTokenType = (10000 of TokenType("TEST", 0)).issuedBy(tokenIssuer)

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
                    FungibleToken(cordaIssuedTokenType, bridgeAuthorityParty)
                )
                output(
                    contractId,
                    FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                )
                output(
                    BRIDGE_PROGRAM_ID,
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(
                        10000,
                        false,
                        tokenAccount,
                        mint,
                        mintAuthority,
                        listOf(bridgeAuthorityParty)
                    )
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    BridgingContract.BridgingCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
                )

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
                    BridgedFungibleTokenProxy(10000, false, tokenAccount, mint, mintAuthority, emptyList())
                )
                output(
                    BRIDGE_PROGRAM_ID,
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(10000, true, tokenAccount, mint, mintAuthority, emptyList())
                )
                command(listOf(bridgeAuthorityParty.owningKey), BridgingContract.BridgingCommand.MintToSolana)
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
                attachment(contractId)
                attachment(BRIDGE_PROGRAM_ID)
                input(
                    contractId,
                    FungibleToken(cordaIssuedTokenType, bridgeAuthorityParty)
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    BridgingContract.BridgingCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
                )

                tweak {
                    output(
                        contractId,
                        FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                    )
                    output(
                        BRIDGE_PROGRAM_ID,
                        BRIDGE_PROGRAM_ID,
                        BridgedFungibleTokenProxy(
                            9999,
                            false,
                            tokenAccount,
                            mint,
                            mintAuthority,
                            listOf(bridgeAuthorityParty)
                        )
                    )
                    `fails with`("Locked amount of 10000 must match amount to recorded in the proxy 9999")
                }

                tweak {
                    output(
                        contractId,
                        FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                    )
                    output(
                        BRIDGE_PROGRAM_ID,
                        BRIDGE_PROGRAM_ID,
                        BridgedFungibleTokenProxy(
                            10001,
                            false,
                            tokenAccount,
                            mint,
                            mintAuthority,
                            listOf(bridgeAuthorityParty)
                        )
                    )
                    `fails with`("Locked amount of 10000 must match amount to recorded in the proxy 10001")
                }

                tweak {
                    val overspendCordaIssuedTokenType = (10001 of TokenType("TEST", 0)).issuedBy(tokenIssuer)
                    output(
                        contractId,
                        FungibleToken(overspendCordaIssuedTokenType, confidentialIdentity)
                    )
                    output(
                        BRIDGE_PROGRAM_ID,
                        BRIDGE_PROGRAM_ID,
                        BridgedFungibleTokenProxy(
                            10000,
                            false,
                            tokenAccount,
                            mint,
                            mintAuthority,
                            listOf(bridgeAuthorityParty)
                        )
                    )
                    `fails with`("In move groups the amount of input tokens MUST EQUAL the amount of output tokens")
                }

                tweak {
                    val underspendCordaIssuedTokenType = (9999 of TokenType("TEST", 0)).issuedBy(tokenIssuer)
                    output(
                        contractId,
                        FungibleToken(underspendCordaIssuedTokenType, confidentialIdentity)
                    )
                    output(
                        BRIDGE_PROGRAM_ID,
                        BRIDGE_PROGRAM_ID,
                        BridgedFungibleTokenProxy(
                            10000,
                            false,
                            tokenAccount,
                            mint,
                            mintAuthority,
                            listOf(bridgeAuthorityParty)
                        )
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
                attachment(contractId)
                attachment(BRIDGE_PROGRAM_ID)
                input(
                    contractId,
                    FungibleToken(cordaIssuedTokenType, bridgeAuthorityParty)
                )
                output(
                    contractId,
                    FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                )
                output(
                    BRIDGE_PROGRAM_ID,
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(
                        10000,
                        false,
                        tokenAccount,
                        mint,
                        mintAuthority,
                        listOf(bridgeAuthorityParty)
                    )
                )
                tweak {
                    `fails with`("A transaction must contain at least one command")
                }
                tweak {
                    command(
                        listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                        MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                    )
                    `fails with`("Bridging transactions must have single bridging command")
                }
                tweak {
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        BridgingContract.BridgingCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
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
                        BridgingContract.BridgingCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
                    )
                    command(
                        listOf(bridgeAuthorityParty.owningKey),
                        BridgingContract.BridgingCommand.MintToSolana
                    )
                    `fails with`(" Bridging transactions must have single bridging command")
                }
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    BridgingContract.BridgingCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
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
                attachment(contractId)
                attachment(BRIDGE_PROGRAM_ID)
                input(
                    contractId,
                    FungibleToken(cordaIssuedTokenType, bridgeAuthorityParty)
                )
                output(
                    contractId,
                    FungibleToken(cordaIssuedTokenType, confidentialIdentity)
                )
                output(
                    BRIDGE_PROGRAM_ID,
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(
                        10000,
                        false,
                        tokenAccount,
                        mint,
                        mintAuthority,
                        listOf(bridgeAuthorityParty)
                    )
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey, confidentialIdentity.owningKey),
                    MoveTokenCommand(cordaIssuedTokenType.token, listOf(0), listOf(0))
                )
                command(
                    listOf(bridgeAuthorityParty.owningKey),
                    BridgingContract.BridgingCommand.LockToken(bridgeAuthorityParty, confidentialIdentity)
                )
                tweak {
                    notaryInstruction(Token2022.mintTo(mint, mintAuthority, mintAuthority, 10000))
                    `fails with`("No Solana mint instruction allowed")
                }
            }
        }
    }

    @Test
    fun mintAmountErrors() {
        services.ledger {
            transaction {
                attachment(BRIDGE_PROGRAM_ID)
                input(
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(10000, false, tokenAccount, mint, mintAuthority, emptyList())
                )
                output(
                    BRIDGE_PROGRAM_ID,
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(10000, true, tokenAccount, mint, mintAuthority, emptyList())
                )
                command(listOf(bridgeAuthorityParty.owningKey), BridgingContract.BridgingCommand.MintToSolana)

                tweak {
                    notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10001))
                    `fails with`("The instruction in the transaction does not match the sum or the bridging config:")
                }
                tweak {
                    notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 9999))
                    `fails with`("The instruction in the transaction does not match the sum or the bridging config:")
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
                attachment(BRIDGE_PROGRAM_ID)
                input(
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(10000, false, tokenAccount, mint, mintAuthority, emptyList())
                )
                output(
                    BRIDGE_PROGRAM_ID,
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(10000, true, tokenAccount, mint, mintAuthority, emptyList())
                )
                notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))

                // no commands
                tweak {
                    `fails with`("A transaction must contain at least one command")
                }

                // two bridging commands
                command(listOf(bridgeAuthorityParty.owningKey), BridgingContract.BridgingCommand.MintToSolana)
                tweak {
                    command(listOf(bridgeAuthorityParty.owningKey), BridgingContract.BridgingCommand.MintToSolana)
                    `fails with`("Bridging transactions must have single bridging command")
                }

                // one bridging command, one random command
                tweak {
                    attachment(contractId)
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
                attachment(BRIDGE_PROGRAM_ID)
                input(
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(10000, false, tokenAccount, mint, mintAuthority, emptyList())
                )
                output(
                    BRIDGE_PROGRAM_ID,
                    BRIDGE_PROGRAM_ID,
                    BridgedFungibleTokenProxy(10000, true, tokenAccount, mint, mintAuthority, emptyList())
                )
                command(listOf(bridgeAuthorityParty.owningKey), BridgingContract.BridgingCommand.MintToSolana)

                tweak {
                    notaryInstruction(Token2022.mintTo(mint, mintAuthority, mintAuthority, 10000))
                    `fails with`("The instruction in the transaction does not match the sum or the bridging config")
                }

                tweak {
                    `fails with`("Exactly one Solana mint instruction required")
                }

                tweak {
                    notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))
                    notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))
                    `fails with`("Exactly one Solana mint instruction required")
                }

                tweak {
                    notaryInstruction(makeInstruction(Token2022.PROGRAM_ID, 6, 10000, tokenAccount))
                    `fails with`("The instruction in the transaction does not match the sum or the bridging config:")
                }

                tweak {
                    notaryInstruction(
                        makeInstruction(
                            mintAuthority,
                            TokenProgramBase.MINT_TO_INSTRUCTION,
                            10000,
                            tokenAccount
                        )
                    )
                    `fails with`("The instruction in the transaction does not match the sum or the bridging config:")
                }

                tweak {
                    notaryInstruction(
                        makeInstruction(
                            Token2022.PROGRAM_ID,
                            TokenProgramBase.MINT_TO_INSTRUCTION,
                            10000,
                            mint
                        )
                    )
                    `fails with`("The instruction in the transaction does not match the sum or the bridging config:")
                }

                notaryInstruction(Token2022.mintTo(mint, tokenAccount, mintAuthority, 10000))

                verifies()
            }
        }
    }

    // Exact copy of a method from Corda Enterprise
    fun makeInstruction(programId: Pubkey, operation: Int, amount: Long, destination: Pubkey): SolanaInstruction {
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
