package com.r3.corda.lib.solana.bridging.token

import com.r3.corda.lib.tokens.contracts.FungibleTokenContract
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import net.corda.core.crypto.Crypto
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.OpaqueBytes
import net.corda.solana.aggregator.common.Signer
import net.corda.solana.sdk.instruction.AccountMeta
import net.corda.solana.sdk.instruction.Pubkey
import net.corda.solana.sdk.instruction.SolanaInstruction
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import org.mockito.Mockito.mock
import java.nio.ByteBuffer
import java.nio.ByteOrder

val alice = Party(
    CordaX500Name("Alice", "Lodz", "PL"),
    Crypto.generateKeyPair().public
)
val bridgeAuthority = Party(
    CordaX500Name("Bridge Authority", "Frankfurt", "DE"),
    Crypto.generateKeyPair().public
)
val confidentialIdentity = AnonymousParty(Crypto.generateKeyPair().public)
val services = MockServices(
    cordappPackages = listOf("com.r3.corda.lib.solana.bridging.token"),
    initialIdentity = TestIdentity(bridgeAuthority.name),
    identityService = mock(),
    networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
)
val TOKEN_PROGRAM_ID: String = FungibleTokenContract.contractId
val cordaTokenAmount = (10000 of TokenType("TEST", 0)).issuedBy(alice)

val mint = Pubkey(Signer.random().account.bytes())
val mintAuthority = Pubkey(Signer.random().account.bytes())
val aliceMintTokenAccount = Pubkey(Signer.random().account.bytes())

val bridgeAuthorityWallet = Pubkey(Signer.random().account.bytes())
val aliceRedemptionTokenAccount = Pubkey(Signer.random().account.bytes())

fun instructionWithWrongOperation(programId: Pubkey): SolanaInstruction {
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
