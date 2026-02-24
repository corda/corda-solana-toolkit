package com.r3.corda.lib.solana.core.internal

import org.slf4j.Logger
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket

fun reconnect(websocket: SolanaRpcWebsocket, logger: Logger) {
    while (true) {
        // connect() returns null if the websocket has been explicitly closed
        val connectFuture = websocket.connect() ?: return
        try {
            connectFuture.get()
            logger.debug("Reconnected to websocket")
            break
        } catch (e: Exception) {
            // Default reconnect delay in the websocket implementation is 3s
            logger.debug("Failed to reconnect to websocket, trying again...", e)
        }
    }
}
