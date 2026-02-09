package com.r3.corda.lib.solana.bridging.token

import com.r3.corda.lib.tokens.contracts.FungibleTokenContract
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.secureRandomBytes
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.solana.AccountMeta
import net.corda.core.solana.Pubkey
import net.corda.core.solana.SolanaInstruction
import net.corda.core.utilities.OpaqueBytes
import net.corda.solana.sdk.Token2022
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import org.mockito.Mockito.mock
import java.nio.ByteBuffer
import java.nio.ByteOrder

val tokenIssuer = Party(
    DUMMY_BANK_A_NAME,
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
val cordaTokenAmount = (10000 of TokenType("TEST", 0)).issuedBy(tokenIssuer)

val mintAccount = Pubkey(secureRandomBytes(32))
val mintAuthority = Pubkey(secureRandomBytes(32))
val tokenAccount = Pubkey(secureRandomBytes(32))
val bridgeAuthorityWallet = Pubkey(secureRandomBytes(32))

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
            AccountMeta(mintAccount, isSigner = false, isWritable = true),
            AccountMeta(destination, isSigner = false, isWritable = true),
            AccountMeta(mintAuthority, isSigner = true, isWritable = false),
        ),
        OpaqueBytes(data)
    )
}
