package com.r3.corda.lib.solana.core

import io.github.bucket4j.Bucket
import org.slf4j.LoggerFactory
import software.sava.core.accounts.Signer
import software.sava.core.tx.Transaction
import software.sava.rpc.json.http.client.SolanaRpcClient
import software.sava.rpc.json.http.request.Commitment
import software.sava.rpc.json.http.request.Commitment.FINALIZED
import software.sava.rpc.json.http.response.BlockHeight
import software.sava.rpc.json.http.response.JsonRpcException
import software.sava.rpc.json.http.response.TransactionError
import software.sava.rpc.json.http.response.TxSimulation
import software.sava.rpc.json.http.response.TxStatus
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket
import software.sava.solana.programs.clients.NativeProgramAccountClient
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.TreeSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong
import kotlin.reflect.KFunction
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4
import kotlin.reflect.KFunction5

/**
 * Thread-safe Solana client which does retries and honours RPC rate limits. It leverages the Sava library and utilises
 * its data types and exceptions.
 *
 * Both a global default and RPC specific rate limits (in requests per second) can be specified, with the later taking
 * precedence. RPC providers can restrict certain RPCs which have higher computational load, so it's important these
 * be configured correctly. If no rate limit is specified then the client will make requests as they come. Under
 * heavy load this is likely to cause HTTP 429 `Too Many Requests` errors to occur. If a `Retry-After` is also
 * returned then the client will retry after this time, otherwise it will perform its own exponential backoff.
 *
 * [SolanaClient] also keeps a regularly updated cached value of the latest blockhash. This is used by default when
 * serialising and sending transactions, which avoids an extra RPC round-trip when low latency is important or a
 * completely fresh blockhash is not required. If a fresh blockhash is needed this can be overriden with the
 * `freshBlockhash` parameter or calling [getBlockhashInfo] with `true`.
 *
 * @param rpcUrl The RPC URL of the RPC provider.
 * @param websocketUrl The websocket URL of the RPC provider.
 * @param commitment The commitment level to use for all operations.
 * @param defaultRateLimit The default rate limit (in requests per second) to use for each RPC, unless a specific rate
 * limit for an RPC is provided in [specificRateLimits].
 * @param specificRateLimits A map of RPC name to rate limit (in requests per second) which overrides the rate limit
 * for particular RPCs.
 * @param userMainExecutor The executor to use for the asynchronous tasks. If one isn't provided then a reasonable
 * default is created.
 */
class SolanaClient
@JvmOverloads
constructor(
    val rpcUrl: URI,
    val websocketUrl: URI,
    val commitment: Commitment = Commitment.CONFIRMED,
    private val defaultRateLimit: Int? = null,
    private val specificRateLimits: Map<String, Int> = emptyMap(),
    private val userMainExecutor: Executor? = null,
) : AutoCloseable {
    companion object {
        // Constants from Solana
        private const val DEFAULT_MS_PER_SLOT = 400
        private const val MAX_PROCESSING_AGE = 150

        private const val MAX_ERROR_POLLING_RETRIES = 5
        private const val SIMULATION_ERROR_CODE = -32002L

        private val THROTTLING_ERROR_CODES = setOf(429, -32429)
        private val BLOCKHASH_REFRESH_DELAY: Duration = Duration.ofSeconds(15)
        private val DEFAULT_DELAY: Duration = Duration.ofSeconds(1)
        private val MAX_BACKOFF_DELAY: Duration = Duration.ofSeconds(30)

        private val log = LoggerFactory.getLogger(SolanaClient::class.java)

        private val rpcMethods by lazy {
            SolanaRpcClient::class.java
                .declaredMethods
                .filter { it.returnType == CompletableFuture::class.java }
                .groupBy { it.name }
        }

        @JvmStatic
        @Throws(SolanaTransactionException::class, SolanaTransactionExpiredException::class)
        fun <T> CompletableFuture<T>.waitForConfirmation(): T {
            try {
                return get()
            } catch (e: ExecutionException) {
                // Re-throw so that we get sane stacktraces
                when (val cause = e.cause) {
                    is SolanaTransactionException -> throw SolanaTransactionException(
                        e.message ?: "waitForConfirmation",
                        cause.error,
                        cause.logMessages
                    )
                    is SolanaTransactionExpiredException ->
                        throw SolanaTransactionExpiredException(cause.message ?: "sendAndConfirm")
                    is RuntimeException -> throw cause
                    else -> throw e
                }
            }
        }
    }

    private val started = AtomicBoolean(false)

    // Internal use only, which is just the HttpClient and for updating the blockhash cache. We don't want user-driven
    // traffic on this executor as it's using the unbounded cached thread pool, which is ideally only for short-lived
    // tasks. Alternative would be to only have the mainExecutor but that can be fixed size. But since all operations
    // in HttpClient are asynchronous we might risk deadlock.
    private val internalExecutor = Executors.newCachedThreadPool(NamedThreadFactory("SolanaClient-Internal"))

    // Use directly only for short-lived tasks, otherwise use scheduleLongTask
    private val scheduler = Executors.newSingleThreadScheduledExecutor(NamedThreadFactory("SolanaClient-Scheduler"))

    private val mainExecutor = userMainExecutor ?: run {
        // Use the largest RPC rate limit as a reasonable backup value for the pool size
        val rateLimits = TreeSet(specificRateLimits.values)
        if (defaultRateLimit != null) {
            rateLimits += defaultRateLimit
        }
        val threadFactory = NamedThreadFactory("SolanaClient-Main")
        if (rateLimits.isNotEmpty()) {
            val poolSize = rateLimits.last()
            ThreadPoolExecutor(poolSize, poolSize, 1, MINUTES, LinkedBlockingQueue(), threadFactory).apply {
                allowCoreThreadTimeOut(true)
            }
        } else {
            Executors.newCachedThreadPool(threadFactory)
        }
    }

    private val rpcRateLimiters = ConcurrentHashMap<String, RpcRateLimiter>()

    private val rpc: SolanaRpcClient
    private val websocket: SolanaRpcWebsocket

    @Volatile
    private var cachedBlockhashInfo: BlockhashInfo? = null

    init {
        val httpClient = HttpClient.newBuilder().executor(internalExecutor).build()
        rpc = SolanaRpcClient.build()
            .endpoint(rpcUrl)
            .httpClient(httpClient)
            .defaultCommitment(commitment)
            .createClient()
        websocket = SolanaRpcWebsocket.build()
            .uri(websocketUrl)
            .webSocketBuilder(httpClient)
            .commitment(commitment)
            .create()
    }

    fun start() {
        check(started.compareAndSet(false, true))
        websocket.connect().getOrThrow()
        // Kickoff the background update of the latest blockhash
        asyncBlockhashInfoCacheUpdate(Duration.ZERO)
    }

    /**
     * Serialises and signs the given transaction, which is then sent to the network and waited for until it is
     * confirmed at the configured [commitment] level.
     *
     * @return The [SerialisedTransaction] that was created and sent to the network.
     * @throws SolanaTransactionException If the transaction failed, including if it failed during simulation.
     * @throws SolanaTransactionExpiredException If the transaction was not processed and instead expired. If this is
     * thrown then it safe to resubmit the same transaction but with an updated blockhash.
     * @throws [JsonRpcException]
     *
     * @see serialiseTransaction
     * @see asyncSendAndConfirm
     * @see [https://solana.com/docs/rpc/http/sendtransaction]
     */
    @JvmOverloads
    @Throws(SolanaTransactionException::class, SolanaTransactionExpiredException::class)
    fun sendAndConfirm(
        createTransaction: (NativeProgramAccountClient) -> Transaction,
        feePayer: Signer,
        otherSigners: Collection<Signer> = emptyList(),
        freshBlockhash: Boolean = false,
        skipPreflight: Boolean = false,
    ): SerialisedTransaction {
        val future = asyncSendAndConfirm(createTransaction, feePayer, otherSigners, freshBlockhash, skipPreflight)
        return future.waitForConfirmation()
    }

    /**
     * Asynchronously serialises and signs the given transaction which is then sent to the network. The
     * [CompletableFuture] that is returned completes when the transaction is confirmed at the configured
     * [commitment] level.
     *
     * See overload which takes in a [SerialisedTransaction] for more details.
     *
     * @return [CompletableFuture] which completes with the [SerialisedTransaction] that was created and sent to the
     * network.
     *
     * @see serialiseTransaction
     * @see asyncConfirm
     * @see [https://solana.com/docs/rpc/http/sendtransaction]
     */
    @JvmOverloads
    fun asyncSendAndConfirm(
        createTransaction: (NativeProgramAccountClient) -> Transaction,
        feePayer: Signer,
        otherSigners: Collection<Signer> = emptyList(),
        freshBlockhash: Boolean = false,
        skipPreflight: Boolean = false,
    ): CompletableFuture<SerialisedTransaction> {
        check(started.get())
        return CompletableFuture
            .supplyAsync(
                { serialiseTransaction(createTransaction, feePayer, otherSigners, freshBlockhash) },
                mainExecutor
            )
            .thenCompose { transaction ->
                sendTransaction(transaction, skipPreflight)
                asyncConfirm(transaction.signature, transaction.lastValidBlockHeight).thenApply { transaction }
            }
    }

    /**
     * Asynchronously sends the given [SerialisedTransaction] to the network and waits for confirmation using
     * [asyncConfirm].
     *
     * The future can complete exceptionally with the following exceptions:
     *
     * * [SolanaTransactionException] If the transaction failed, including if it failed during simulation.
     * * [SolanaTransactionExpiredException] If the transaction was not processed and instead expired. If this is
     * thrown then it safe to resubmit the same transaction but with an updated blockhash.
     * * [JsonRpcException]
     *
     * @see asyncConfirm
     */
    @JvmOverloads
    fun asyncSendAndConfirm(
        transaction: SerialisedTransaction,
        skipPreflight: Boolean = false,
    ): CompletableFuture<*> {
        check(started.get())
        return CompletableFuture
            .supplyAsync({ sendTransaction(transaction, skipPreflight) }, mainExecutor)
            .thenCompose { asyncConfirm(transaction.signature, transaction.lastValidBlockHeight) }
    }

    private fun sendTransaction(transaction: SerialisedTransaction, skipPreflight: Boolean) {
        try {
            val signature = call(
                SolanaRpcClient::sendTransaction,
                commitment,
                transaction.asBase64(),
                skipPreflight,
                -1
            )
            check(signature == transaction.signature) // Sanity check
        } catch (e: JsonRpcException) {
            // If the error was in the simulation then simulate the transaction to get the SolanaTransactionException
            // with the error details
            if (e.code() == SIMULATION_ERROR_CODE) {
                simulateTransaction(transaction)
                // If for some reason simulateTransaction didn't throw then fall-through and throw the original
                // sendTransaction error
            }
            throw e
        }
    }

    /**
     * Returns a [CompletableFuture] which completes when the transaction is confirmed at the configured [commitment]
     * level. If the last valid block height for the transaction is provided then it will also check for expiration.
     *
     * The websocket `signatureSubscribe` request is used but falls back to polling with the `getSignatureStatuses`
     * RPC if that fails.
     *
     * The future can complete exceptionally with the following exceptions:
     *
     * * [SolanaTransactionException] If the transaction failed, including if it failed during simulation.
     * * [SolanaTransactionExpiredException] If the transaction was not processed and instead expired. If this is
     * thrown then it safe to resubmit the same transaction but with an updated blockhash. This will only occur if
     * [lastValidBlockHeight] is provided.
     * * [JsonRpcException]
     *
     * @see [https://solana.com/docs/rpc/websocket/signaturesubscribe]
     * @see [https://solana.com/docs/rpc/http/getsignaturestatuses]
     */
    @JvmOverloads
    fun asyncConfirm(signature: String, lastValidBlockHeight: Long = 0): CompletableFuture<*> {
        check(started.get())
        val txConfirmedFuture = CompletableFuture<Unit>()
        // signatureSubscribe is an efficient way of getting notification the transaction is confirmed. However it
        // may not be reliable and we might miss the event. So as a backup we start polling for the signature status
        // after we have reasonably expected a response.
        val pollingFuture = scheduleSignatureStatusPolling(signature, txConfirmedFuture)
        val isUniqueSub = websocket.signatureSubscribe(signature) { txResult ->
            // Stop polling immediately once we have the websocket response. It's important we have this here even
            // though it will eventually be done via txConfirmedFuture.whenComplete because that executes on the
            // mainExecutor and thus risks the polling to continue on unnecessarily under heavy load.
            pollingFuture.cancel(true)
            // Websocket callbacks are executed on the internalExecutor and so we must get off it and complete the
            // txConfirmedFuture on the mainExecutor. As txConfirmedFuture is returned to the user it's possible they
            // specify further work on completion which would otherwise execute on the internalExecutor.
            mainExecutor.execute {
                val txConfirmedFutureUpdated = onSignatureStatus(txConfirmedFuture, txResult.error, signature)
                if (txConfirmedFutureUpdated) {
                    log.debug("{} confirmed via websocket: {}", signature, txResult)
                }
            }
        }
        if (!isUniqueSub) {
            log.info(
                "Duplicate confirmation for the same signature ($signature), defaulting to just polling for this " +
                    "request"
            )
        }
        val expireTxFuture = if (lastValidBlockHeight != 0L) {
            scheduleTxExpiration(signature, lastValidBlockHeight, txConfirmedFuture)
        } else {
            null
        }
        txConfirmedFuture.whenComplete { _, _ ->
            expireTxFuture?.cancel(true)
            // This second cancellation of pollingFuture is needed if txConfirmedFuture completes via tx expiration.
            pollingFuture.cancel(true)
            if (isUniqueSub) { // Only the original subscriber can unsubscribe
                websocket.signatureUnsubscribe(signature)
            }
        }
        return txConfirmedFuture
    }

    /**
     * Retrying [SolanaRpcClient.simulateTransaction] wrapper but if the simulation had an error throws
     * [SolanaTransactionException] instead of returning the [TxSimulation].
     *
     * @throws SolanaTransactionException If the simulation failed.
     *
     * @see [https://solana.com/docs/rpc/http/simulatetransaction]
     */
    @JvmOverloads
    fun simulateTransaction(transaction: SerialisedTransaction, replaceRecentBlockhash: Boolean = false): TxSimulation {
        // started checked by call
        val result = call(SolanaRpcClient::simulateTransaction, transaction.asBase64(), replaceRecentBlockhash)
        if (result.error != null) {
            throw SolanaTransactionException("Simulated transaction failed: $result", result.error, result.logs)
        }
        return result
    }

    /**
     * Serialise and sign the given [Transaction], returning a [SerialisedTransaction] which can then be used with
     * [asyncSendAndConfirm] or [simulateTransaction].
     *
     * By default the current cached blockhash is used which avoids an RPC call. If the latest value is required then
     * make sure [freshBlockhash] is `true` (see also [getBlockhashInfo]).
     */
    @JvmOverloads
    fun serialiseTransaction(
        createTransaction: (NativeProgramAccountClient) -> Transaction,
        feePayer: Signer,
        otherSigners: Collection<Signer> = emptyList(),
        freshBlockhash: Boolean = false,
    ): SerialisedTransaction {
        check(started.get())
        val transaction = createTransaction(NativeProgramAccountClient.createClient(feePayer))
        val blockhashInfo = getBlockhashInfo(forceFetch = freshBlockhash)
        transaction.setRecentBlockHash(blockhashInfo.blockhash)
        transaction.sign(feePayer)
        for (otherSigner in otherSigners) {
            if (otherSigner.publicKey() != feePayer.publicKey()) {
                transaction.sign(otherSigner)
            }
        }
        return SerialisedTransaction.create(transaction.serialized(), blockhashInfo.lastValidBlockHeight)
    }

    /**
     * Returns the most recently cached blockhash. If there is no cached value or if [forceFetch] is `true`, then the
     * cache is updated with the latest value fetched synchronously from the network.
     *
     * @param forceFetch If `true` ignores any cached value and makes a fresh request.
     * @return The latest blockhash, from cache or network.
     * @throws JsonRpcException If latest blockhash was requested synchronously from the network and there was an error.
     *
     * @see [https://solana.com/docs/rpc/http/getlatestblockhash]
     */
    fun getBlockhashInfo(forceFetch: Boolean): BlockhashInfo {
        check(started.get())
        val cachedBlockhashInfo = this.cachedBlockhashInfo
        return if (forceFetch || cachedBlockhashInfo == null) {
            val blockhashInfo = getLatestBlockhashInfo()
            this.cachedBlockhashInfo = blockhashInfo
            blockhashInfo
        } else {
            cachedBlockhashInfo
        }
    }

    /**
     * Returns an estimate for the current block height using the cached blockhash, thus avoiding an RPC call. The
     * further away from the last refresh, the less accurate this estimate will be. The estimate also assumes every
     * slot is filled with a block, which is not always the case.
     *
     * For an accurate value of the block height use [SolanaRpcClient.getBlockHeight] instead.
     */
    fun getEstimatedBlockHeight(): Long {
        // started checked by getBlockhashInfo
        val blockhashInfo = getBlockhashInfo(forceFetch = false)
        val elapsedMillis = blockhashInfo.retrievalTime.until(Instant.now(), ChronoUnit.MILLIS).coerceAtLeast(0)
        // TODO Use getRecentPerformanceSamples to get a recent average for ms per slot.
        val estimatedElapsedSlots = elapsedMillis / DEFAULT_MS_PER_SLOT
        return blockhashInfo.blockHeight + estimatedElapsedSlots
    }

    /**
     * Retrying call on the given RPC method from [SolanaRpcClient].
     */
    fun <R> call(methodName: String, resultType: Class<R>, vararg rpcArgs: Any): R {
        check(started.get())
        val methodOverloads = requireNotNull(rpcMethods[methodName]) { "$methodName not an RPC method" }
        val method = methodOverloads.find { it.parameters.isMatchingArgs(rpcArgs) }
        requireNotNull(method) {
            val message = StringBuilder("No matching overload found for [")
            rpcArgs.joinTo(message, postfix = "']:\n") { it.javaClass.name }
            methodOverloads.joinTo(message, separator = "\n")
        }
        val actualResultType = (method.genericReturnType as ParameterizedType).actualTypeArguments[0] as Class<*>
        require(actualResultType == resultType) {
            "$methodName returns a ${actualResultType.name}, not a ${resultType.name}"
        }
        return getRateLimiter(methodName).retryingCall(rpcArgs) {
            @Suppress("UNCHECKED_CAST")
            method.invoke(rpc, *rpcArgs) as CompletableFuture<R>
        }
    }

    private fun Array<Parameter>.isMatchingArgs(rpcArgs: Array<out Any>): Boolean {
        if (rpcArgs.size != size) {
            return false
        }
        for (index in rpcArgs.indices) {
            if (!this[index].type.toBoxedClass().isAssignableFrom(rpcArgs[index].javaClass)) {
                return false
            }
        }
        return true
    }

    private fun Class<*>.toBoxedClass(): Class<*> {
        return when {
            this == Long::class.javaPrimitiveType -> Long::class.javaObjectType
            this == Double::class.javaPrimitiveType -> Double::class.javaObjectType
            this == Int::class.javaPrimitiveType -> Int::class.javaObjectType
            this == Float::class.javaPrimitiveType -> Float::class.javaObjectType
            this == Short::class.javaPrimitiveType -> Short::class.javaObjectType
            this == Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            this == Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
            this == Char::class.javaPrimitiveType -> Char::class.javaObjectType
            else -> this
        }
    }

    // The following `call` functions are designed only for Kotlin consumers and so are marked as synthetic to hide
    // them from javac.

    /**
     * Retrying wrapper around the given [SolanaRpcClient] no parameter RPC function.
     *
     * Example:
     * ```
     * val latestBlockhash: LatestBlockHash = solanaClient.call(SolanaRpcClient::getLatestBlockHash)
     * ```
     */
    @JvmSynthetic
    fun <R> call(method: KFunction1<SolanaRpcClient, CompletableFuture<R>>): R {
        check(started.get())
        return getRateLimiter(method.name).retryingCall(method)
    }

    /**
     * Retrying wrapper around the given [SolanaRpcClient] single parameter RPC function.
     *
     * Example:
     * ```
     * val accountInfo: AccountInfo = solanaClient.call(SolanaRpcClient::getAccountInfo, address)
     * ```
     */
    @JvmSynthetic
    fun <P : Any, R> call(method: KFunction2<SolanaRpcClient, P, CompletableFuture<R>>, parameter: P): R {
        check(started.get())
        return getRateLimiter(method.name).retryingCall(method, parameter)
    }

    /**
     * Retrying wrapper around the given [SolanaRpcClient] two-parameter RPC function.
     *
     * Example:
     * ```
     * val result: TxSimulation = solanaClient.call(
     *      SolanaRpcClient::simulateTransaction,
     *      encodededTx,
     *      replaceRecentBlockhash
     * )
     * ```
     */
    @JvmSynthetic
    fun <P1 : Any, P2 : Any, R> call(
        method: KFunction3<SolanaRpcClient, P1, P2, CompletableFuture<R>>,
        parameter1: P1,
        parameter2: P2,
    ): R {
        check(started.get())
        return getRateLimiter(method.name).retryingCall(method, parameter1, parameter2)
    }

    /**
     * Retrying wrapper around the given [SolanaRpcClient] three-parameter RPC function.
     */
    @JvmSynthetic
    fun <P1 : Any, P2 : Any, P3 : Any, R> call(
        method: KFunction4<SolanaRpcClient, P1, P2, P3, CompletableFuture<R>>,
        parameter1: P1,
        parameter2: P2,
        parameter3: P3,
    ): R {
        check(started.get())
        return getRateLimiter(method.name).retryingCall(method, parameter1, parameter2, parameter3)
    }

    /**
     * Retrying wrapper around the given [SolanaRpcClient] four-parameter RPC function.
     */
    @JvmSynthetic
    fun <P1 : Any, P2 : Any, P3 : Any, P4 : Any, R> call(
        method: KFunction5<SolanaRpcClient, P1, P2, P3, P4, CompletableFuture<R>>,
        parameter1: P1,
        parameter2: P2,
        parameter3: P3,
        parameter4: P4,
    ): R {
        check(started.get())
        return getRateLimiter(method.name).retryingCall(method, parameter1, parameter2, parameter3, parameter4)
    }

    private fun getRateLimiter(methodName: String): RpcRateLimiter {
        return rpcRateLimiters.computeIfAbsent(methodName, ::RpcRateLimiter)
    }

    private fun scheduleSignatureStatusPolling(
        signature: String,
        txConfirmedFuture: CompletableFuture<Unit>,
    ): Future<*> {
        // We intentionally start polling a bit after the default/ideal response time for the commitment level
        val initialDelay = if (commitment == FINALIZED) Duration.ofSeconds(13) else Duration.ofMillis(600)
        return scheduleLongTask(mainExecutor, initialDelay) {
            val getSignatureStatusesMethod:
                KFunction2<SolanaRpcClient, Collection<String>, CompletableFuture<Map<String, TxStatus>>> =
                SolanaRpcClient::getSignatureStatuses
            try {
                val txStatus = getRateLimiter(getSignatureStatusesMethod.name).pollingCall(
                    getSignatureStatusesMethod,
                    DEFAULT_DELAY,
                    { txStatuses ->
                        log.debug("getSignatureStatuses: {}", txStatuses)
                        val txStatus = txStatuses.values.first()
                        val txCommitment = txStatus.confirmationStatus
                        // The transaction is only confirmed once it's reached our commitment level
                        txStatus.takeIf { txCommitment != null && txCommitment <= commitment }
                    },
                    listOf(signature)
                )
                val txConfirmedFutureUpdated = onSignatureStatus(txConfirmedFuture, txStatus.error, signature)
                if (txConfirmedFutureUpdated) {
                    log.warn("$signature was confirmed via polling and not websocket ($txStatus)")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                log.debug("Signature status polling for {} cancelled", signature)
            } catch (e: Exception) {
                log.warn("Signature status polling for $signature failed", e)
            }
        }
    }

    /**
     * Schedule a task to error the transaction confirmation if it has expired. We first use an estimated expiry time
     * but then poll with getBlockHeight to make sure the transaction's lastValidBlockHeight has been surpassed. It's
     * only then can the user safely submit a new transaction. If they try before then it's possible for both
     * transactions to be accepted by the network.
     *
     * See https://solana.com/developers/guides/advanced/retry#when-to-re-sign-transactions.
     */
    private fun scheduleTxExpiration(
        signature: String,
        lastValidBlockHeight: Long,
        txConfirmedFuture: CompletableFuture<Unit>,
    ): Future<*> {
        val estimatedTxExpiry = Duration.ofMillis(
            (lastValidBlockHeight - getEstimatedBlockHeight()).coerceAtLeast(0) * DEFAULT_MS_PER_SLOT
        )
        return scheduleLongTask(mainExecutor, estimatedTxExpiry) {
            val getBlockHeightMethod:
                KFunction1<SolanaRpcClient, CompletableFuture<BlockHeight>> = SolanaRpcClient::getBlockHeight
            try {
                val blockHeight = getRateLimiter(getBlockHeightMethod.name).pollingCall(
                    getBlockHeightMethod,
                    DEFAULT_DELAY,
                    { blockHeight -> blockHeight.height.takeIf { it > lastValidBlockHeight } }
                )
                txConfirmedFuture.completeExceptionally(
                    SolanaTransactionExpiredException(
                        "Transaction $signature lastValidBlockHeight " +
                            "($lastValidBlockHeight) is no longer valid against current block height ($blockHeight)"
                    )
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // If we were unable to determine if the transaction has expired, then take the worst case scenario.
                Thread.sleep(MAX_PROCESSING_AGE * DEFAULT_MS_PER_SLOT.toLong())
                txConfirmedFuture.completeExceptionally(
                    SolanaTransactionExpiredException(
                        "Transaction $signature lastValidBlockHeight " +
                            "($lastValidBlockHeight) is no longer valid"
                    ).apply { addSuppressed(e) }
                )
            }
        }
    }

    private fun onSignatureStatus(
        txConfirmedFuture: CompletableFuture<Unit>,
        errorFromSigStatus: TransactionError?,
        signature: String,
    ): Boolean {
        return if (errorFromSigStatus == null) {
            txConfirmedFuture.complete(Unit)
        } else {
            // If there was an error then get the full logs with getTransaction.
            val confirmationException = try {
                val tx = call(SolanaRpcClient::getTransaction, signature)
                if (tx.meta?.error == errorFromSigStatus) {
                    SolanaTransactionException("Transaction $signature failed: $tx", tx.meta.error, tx.meta.logMessages)
                } else {
                    IllegalStateException(
                        "$signature: signature status indicated error ($errorFromSigStatus) but " +
                            "getTransaction gave different result ($tx)"
                    )
                }
            } catch (e: Exception) {
                IllegalStateException("Unable to get logs for $signature which had errored with $errorFromSigStatus", e)
            }
            txConfirmedFuture.completeExceptionally(confirmationException)
        }
    }

    private fun asyncBlockhashInfoCacheUpdate(delay: Duration) {
        scheduleLongTask(internalExecutor, delay) {
            try {
                cachedBlockhashInfo = getLatestBlockhashInfo()
                log.debug("Cached latest blockhash updated: {}", cachedBlockhashInfo)
            } catch (e: Exception) {
                log.warn("Failed to get latest blockhash", e)
                // The cached value may still be valid but we eagerly clear it now to avoid the possibility of it being
                // invalid later
                cachedBlockhashInfo = null
            }
            // Only schedule the next update once we've gotten through any RPC throttling
            asyncBlockhashInfoCacheUpdate(BLOCKHASH_REFRESH_DELAY)
        }
    }

    private fun getLatestBlockhashInfo(): BlockhashInfo {
        val latestBlockhash = call(SolanaRpcClient::getLatestBlockHash)
        return BlockhashInfo(latestBlockhash.blockHash, latestBlockhash.lastValidBlockHeight, Instant.now())
    }

    // We want to keep the singleton scheduler free from long running tasks and so instead the given task is scheduled
    // to be _submitted_ to the general executor after the given delay.
    private fun <T> scheduleLongTask(taskExecutor: Executor, initialDelay: Duration, longTask: () -> T): Future<T> {
        lateinit var scheduledFuture: ScheduledFuture<*>

        val returnFuture = object : CompletableFuture<T>() {
            // Make sure the underlying scheduled future is also cancelled
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                var result = super.cancel(mayInterruptIfRunning)
                if (scheduledFuture.cancel(mayInterruptIfRunning)) {
                    result = true
                }
                return result
            }
        }

        scheduledFuture = scheduler.schedule({
            taskExecutor.execute {
                if (!returnFuture.isCancelled) {
                    try {
                        returnFuture.complete(longTask())
                    } catch (t: Throwable) {
                        returnFuture.completeExceptionally(t)
                    }
                }
            }
        }, initialDelay.toMillis(), MILLISECONDS)

        return returnFuture
    }

    override fun close() {
        if (!started.compareAndSet(true, false)) {
            return
        }
        websocket.close()
        if (userMainExecutor == null) {
            (mainExecutor as ExecutorService).shutdownNow()
        }
        scheduler.shutdownNow()
        internalExecutor.shutdownNow()
    }

    /**
     * A serialised transaction with the last valid block height it was created with.
     *
     * @see [SolanaClient.serialiseTransaction]
     */
    class SerialisedTransaction private constructor(
        private val bytesInternal: ByteArray,
        val lastValidBlockHeight: Long,
    ) {
        val bytes: ByteArray
            get() = bytesInternal.clone()

        val signature: String
            get() = Transaction.getBase58Id(bytesInternal)

        fun asBase64(): String = Base64.getEncoder().encodeToString(bytesInternal)

        override fun toString(): String = signature

        companion object {
            // Constructors can't be annotated with JvmSynthetic (which hides it from javac) and so need to have a
            // separate factory method
            @JvmSynthetic
            internal fun create(bytes: ByteArray, lastValidBlockHeight: Long): SerialisedTransaction {
                return SerialisedTransaction(bytes, lastValidBlockHeight)
            }
        }
    }

    /**
     * Information on a blockhash.
     *
     * @property blockhash The base-58 encoding of the block hash.
     * @property lastValidBlockHeight Last block height at which the blockhash will be valid.
     * @property retrievalTime Time of when the blockhash was received.
     */
    class BlockhashInfo(val blockhash: String, val lastValidBlockHeight: Long, val retrievalTime: Instant) {
        /**
         * Back out the MAX_PROCESSING_AGE from the lastValidBlockHeight to get the block height.
         */
        val blockHeight: Long
            get() = lastValidBlockHeight - MAX_PROCESSING_AGE

        override fun toString(): String {
            return "BlockhashInfo(blockhash=$blockhash, lastValidBlockHeight=$lastValidBlockHeight, " +
                "retrievalTime=$retrievalTime)"
        }
    }

    private inner class RpcRateLimiter(val methodName: String) {
        private val rateLimitingBucket: Bucket?

        @Volatile
        private var backoffUntil = Instant.now()

        init {
            val rateLimit = (specificRateLimits[methodName] ?: defaultRateLimit)?.toLong()
            rateLimitingBucket = rateLimit?.let {
                Bucket.builder()
                    .addLimit { bandwidth ->
                        // Since we don't know the throttling strategy of the RPC provider we take the more
                        // conservative approach of only refilling at the end of each second as opposed to gradually.
                        bandwidth.capacity(rateLimit).refillIntervally(rateLimit, Duration.ofSeconds(1))
                    }
                    .build()
            }
        }

        fun <T> retryingCall(method: KFunction<CompletableFuture<T>>, vararg rpcArgs: Any): T {
            return retryingCall(rpcArgs) { method.call(rpc, *rpcArgs) }
        }

        fun <T> retryingCall(rpcArgs: Array<out Any>, methodCall: () -> CompletableFuture<T>): T {
            var nextRetryAfter = DEFAULT_DELAY
            while (true) {
                if (!waitForAvailability()) {
                    continue
                }
                try {
                    return methodCall().getOrThrow()
                } catch (e: JsonRpcException) {
                    if (e.retryAfterSeconds().isEmpty && e.code().toInt() !in THROTTLING_ERROR_CODES) {
                        throw e
                    }
                    val retryAfter = if (e.retryAfterSeconds().isPresent) {
                        Duration.ofSeconds(e.retryAfterSeconds().asLong)
                    } else {
                        val currentRetryAfter = nextRetryAfter
                        nextRetryAfter = minOf(
                            Duration.ofMillis((nextRetryAfter.toMillis() * 1.5).roundToLong()),
                            MAX_BACKOFF_DELAY
                        )
                        currentRetryAfter
                    }
                    backoffUntil = Instant.now() + retryAfter
                    log.warn(
                        "${toString(rpcArgs)} was throttled, all $methodName calls will be made after " +
                            "$retryAfter"
                    )
                }
            }
        }

        private fun waitForAvailability(): Boolean {
            // If the RPC was previously throttled then first wait it out
            val backoffUntil = this.backoffUntil
            val backoffMillis = Instant.now().until(backoffUntil, ChronoUnit.MILLIS)
            if (backoffMillis > 0) {
                Thread.sleep(backoffMillis)
            }
            // If a rate limit has been specified then wait for a token to become available. This will also prevent a
            // thundering herd of threads all retrying the same RPC after backoff.
            val rateLimitingBucket = this.rateLimitingBucket
            if (rateLimitingBucket != null) {
                rateLimitingBucket.asBlocking().consume(1)
                // If the thread had to wait long then it's possible for another HTTP 429 to occur on another RPC call.
                // So we must check if the backoff delay has been extended.
                if (backoffUntil != this.backoffUntil) {
                    rateLimitingBucket.addTokens(1)
                    return false
                }
            }
            return true
        }

        fun <T, P> pollingCall(
            method: KFunction<CompletableFuture<T>>,
            pollingInterval: Duration,
            pollingCondition: (T) -> P?,
            vararg rpcArgs: Any,
        ): P {
            var consecutiveErrors = 0
            while (true) {
                val rpcResult = try {
                    retryingCall(method, *rpcArgs)
                } catch (e: Exception) {
                    if (consecutiveErrors++ == MAX_ERROR_POLLING_RETRIES || e is InterruptedException) {
                        throw e
                    }
                    if (log.isDebugEnabled) {
                        log.debug(
                            "${toString(rpcArgs)} poll failed (attempt $consecutiveErrors), retrying after " +
                                "$DEFAULT_DELAY",
                            e
                        )
                    }
                    Thread.sleep(DEFAULT_DELAY.toMillis())
                    continue
                }
                consecutiveErrors = 0
                val pollValue = pollingCondition(rpcResult)
                if (pollValue != null) {
                    return pollValue
                }
                Thread.sleep(pollingInterval.toMillis())
            }
        }

        private fun toString(args: Array<out Any>) = args.joinToString(",", "$methodName(", ")")
    }

    private fun <V> Future<V>.getOrThrow(): V {
        return try {
            get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private class NamedThreadFactory(
        private val name: String,
        private val delegate: ThreadFactory = Executors.defaultThreadFactory(),
    ) : ThreadFactory {
        private val threadNumber = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread {
            val thread = delegate.newThread(runnable)
            thread.name = "$name-${threadNumber.getAndIncrement()}"
            return thread
        }
    }
}
