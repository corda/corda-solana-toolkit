package com.r3.corda.lib.solana.core

import org.slf4j.LoggerFactory
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.SolanaAccounts
import software.sava.core.accounts.token.TokenAccount
import software.sava.rpc.json.http.client.SolanaRpcClient
import software.sava.rpc.json.http.response.AccountInfo
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket
import java.net.http.HttpClient
import java.util.concurrent.CompletableFuture.delayedExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean

class TokenAccountListener(
    private val solanaClient: SolanaClient,
    private val programId: PublicKey = SolanaAccounts.MAIN_NET.tokenProgram(),
) : AutoCloseable {
    companion object {
        private const val CONNECTION_TIMEOUT_SECONDS = 3L
        private const val MAX_BACKOFF_DELAY_SECS = 10
        private val logger = LoggerFactory.getLogger(TokenAccountListener::class.java)
    }

    // HttpClient in Java 17 doesn't have a close() method and so we create the same executor that it does by default
    // so that we can shut it down in our close()
    private val httpExecutor = Executors.newCachedThreadPool()

    // For some very bizzare reason SolanaJsonRpcWebsocket maintains a Map of the current subscriptions on the program
    // ID. This means a second programSubscribe with a different filter ends up being ignored. So we're forced to have a
    // SolanaJsonRpcWebsocket instance for each owner we want to subscribe to!
    // TODO Fix this in our fork
    private val websocketBuilder = SolanaRpcWebsocket
        .build()
        .webSocketBuilder(HttpClient.newBuilder().executor(httpExecutor).build())
        .uri(solanaClient.websocketUrl)
        .commitment(solanaClient.commitment)
    private val subscriptions = ConcurrentHashMap<PublicKey, Subscription>()

    fun listenToOwner(owner: PublicKey, onTokenAccount: (TokenAccount) -> Unit) {
        logger.info("Attaching websocket for account owned by: $owner")
        val subscription = Subscription(owner)
        require(subscriptions.putIfAbsent(owner, subscription) == null) {
            "Already listening to ${owner.toBase58()}"
        }
        subscription.websocket.connect().get()
        subscription.subscribe(onTokenAccount)
    }

    fun unsubscribe(owner: PublicKey): Boolean {
        val subscription = subscriptions.remove(owner) ?: return false
        subscription.websocket.programUnsubscribe(programId)
        subscription.websocket.close()
        return true
    }

    override fun close() {
        subscriptions.values.forEach { it.websocket.close() }
        subscriptions.clear()
        httpExecutor.shutdown()
    }

    private inner class Subscription(val owner: PublicKey) {
        private val checkTokenAccounts = AtomicBoolean(false)
        private val tokenAccounts = ConcurrentHashMap<PublicKey, TokenAccount>()
        val websocket: SolanaRpcWebsocket = websocketBuilder
            .onClose { _, errorCode, reason ->
                logger.info("Websocket for $owner closed: $errorCode, $reason. Reconnecting...")
                ForkJoinPool.commonPool().submit { reconnect() }
            }.create()

        fun subscribe(onTokenAccount: (TokenAccount) -> Unit) {
            websocket.programSubscribe(
                programId,
                listOf(TokenAccount.createOwnerFilter(owner)),
                { _ ->
                    // This flag is to prevent checking existing token accounts on first subscription. We only want
                    // to check for existing token accounts if we've reconnected and thus might have missed some
                    // updates.
                    if (checkTokenAccounts.getAndSet(true)) {
                        for (account in getAllTokenAccounts()) {
                            val previous = tokenAccounts.put(account.pubKey, account.data)
                            if (previous != account.data) {
                                onTokenAccount(account.data)
                            }
                        }
                    }
                },
                { accountInfo ->
                    val tokenAccount = TokenAccount.read(accountInfo.pubKey, accountInfo.data)
                    tokenAccounts[tokenAccount.address] = tokenAccount
                    onTokenAccount(tokenAccount)
                }
            )
        }

        private fun getAllTokenAccounts(): List<AccountInfo<TokenAccount>> {
            return solanaClient.call(SolanaRpcClient::getTokenAccountsForProgramByOwner, owner, programId)
        }

        private fun reconnect(remainingAttempts: Int = MAX_BACKOFF_DELAY_SECS) {
            val connectFuture = checkNotNull(websocket.connect()) {
                "Cannot reconnect to SolanaRpcWebsocket as it has been closed"
            }
            val connected = try {
                connectFuture.get(CONNECTION_TIMEOUT_SECONDS, SECONDS)
                true
            } catch (e: Exception) {
                logger.warn("Solana websocket failed to connect: ${e.message}")
                false
            }
            when {
                connected -> logger.info("Reconnected to websocket for $owner")
                remainingAttempts > 0 -> {
                    logger.warn("Failed to reconnect to websocket, trying again...")
                    val attemptNumber = MAX_BACKOFF_DELAY_SECS - remainingAttempts + 1
                    logger.info("Retrying to reconnect in $attemptNumber seconds (attempt $attemptNumber)")
                    delayedExecutor(attemptNumber.toLong(), SECONDS).execute {
                        reconnect(remainingAttempts - 1)
                    }
                }
                else -> {
                    logger.info("Failed to reconnect to websocket. Trying again in $MAX_BACKOFF_DELAY_SECS seconds")
                    delayedExecutor(MAX_BACKOFF_DELAY_SECS.toLong(), SECONDS).execute {
                        reconnect(0)
                    }
                }
            }
        }
    }
}
