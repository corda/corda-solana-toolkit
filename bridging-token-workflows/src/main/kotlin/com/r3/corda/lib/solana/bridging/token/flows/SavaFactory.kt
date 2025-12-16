package com.r3.corda.lib.solana.bridging.token.flows

import net.corda.core.utilities.debug
import net.corda.core.utilities.loggerFor
import net.corda.solana.sdk.instruction.Pubkey
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.SolanaAccounts
import software.sava.core.accounts.token.TokenAccount
import software.sava.core.rpc.Filter
import software.sava.rpc.json.http.client.SolanaRpcClient
import software.sava.rpc.json.http.response.AccountInfo
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.TimeUnit

object SavaFactory {
    private const val CONNECTION_TIMEOUT_SECONDS = 3L
    val logger = loggerFor<SavaFactory>()

    class WebSocketWrapper(val rpcUrl: String, val wsUrl: String, onWebSocketClose: (Int, String) -> Unit) {
        private val socket = createWebSocket(wsUrl) { _, errorCode, reason ->
            onWebSocketClose(errorCode, reason)
        }

        fun onToken2022ByOwner(
            owners: Set<Pubkey>,
            onAccountChanged: (owner: Pubkey, account: Pubkey, tokenMint: Pubkey, amount: Long) -> Unit,
        ): Boolean {
            logger.info("Attaching websocket for account owned by: $owners")
            return socket.programSubscribe(
                SolanaAccounts.MAIN_NET.token2022Program(),
                owners.map { Filter.createMemCompFilter(TokenAccount.OWNER_OFFSET, it.toPublicKey()) },
                { _ ->
                    owners.forEach { owner ->
                        getNonZeroTokenAccounts(owner).forEach {
                            logger.debug {
                                "WebSocketWrapper::onSub found non zero account ${it.pubKey} owned by ${it.data.owner}"
                            }
                            try {
                                onAccountChanged(
                                    owner,
                                    it.pubKey.toPubkey(),
                                    it.data.mint.toPubkey(),
                                    it.data.amount(),
                                )
                            } catch (e: Exception) {
                                logger.error(
                                    "Tried to process account ${it.pubKey} with amount ${it.data.amount} " +
                                        "but processing threw:",
                                    e
                                )
                            }
                        }
                    }
                }
            ) { accountInfo ->
                val token = TokenAccount.read(accountInfo.pubKey, accountInfo.data)
                val ownerKey = accountInfo.pubKey.toPubkey()
                val accountKey = accountInfo.pubKey.toPubkey()
                val mintKey = token.mint.toPubkey()
                onAccountChanged(ownerKey, accountKey, mintKey, token.amount)
            }
        }

        fun getNonZeroTokenAccounts(owner: Pubkey): List<AccountInfo<TokenAccount>> {
            val httpClient = HttpClient.newHttpClient()
            val solanaClient = SolanaRpcClient.createClient(URI.create(rpcUrl), httpClient, globalCommitmentLevelSava)
            logger.debug { "Checking for non-zero token accounts owned by $owner" }
            return solanaClient
                .getTokenAccountsForProgramByOwner(owner.toPublicKey(), SolanaAccounts.MAIN_NET.token2022Program())
                .join()
                .filter {
                    it.data.amount > 0
                }
        }

        fun reconnect(): Boolean {
            logger.info("Reconnecting Solana websocket...")
            return try {
                socket.connect().get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS) != null
            } catch (_: Exception) {
                logger.warn("Solana websocket failed to connect")
                false
            }
        }
    }

    fun createWebSocket(rpcUrl: String, onClose: (SolanaRpcWebsocket, Int, String) -> Unit): SolanaRpcWebsocket {
        val httpClient = HttpClient.newHttpClient()
        val socket = SolanaRpcWebsocket
            .build()
            .webSocketBuilder(httpClient.newWebSocketBuilder())
            .uri(rpcUrl)
            .solanaAccounts(SolanaAccounts.MAIN_NET)
            .commitment(globalCommitmentLevelSava)
            .onClose(onClose)
            .create()

        socket.connect().get()
        return socket
    }

    fun Pubkey.toPublicKey(): PublicKey = PublicKey.createPubKey(bytes)

    fun PublicKey.toPubkey() = Pubkey(copyByteArray())
}
