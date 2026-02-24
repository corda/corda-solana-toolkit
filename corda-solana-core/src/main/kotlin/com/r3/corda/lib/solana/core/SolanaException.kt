package com.r3.corda.lib.solana.core

import software.sava.rpc.json.http.response.TransactionError

open class SolanaException(
    message: String?,
    cause: Throwable?,
) : Exception(message, cause) {
    constructor(message: String?) : this(message, null)
}

class SolanaTransactionException(
    message: String,
    val error: TransactionError,
    val logMessages: List<String>,
) : SolanaException(message)

/**
 * The transaction has expired, where its recent blockhash is no longer in the recent block window and thus no longer
 * valid to be processed. Users will need to sign a new transaction with a fresh recent blockhas if they wish to try
 * again.
 */
class SolanaTransactionExpiredException(message: String) : SolanaException(message)
