package com.r3.corda.lib.solana.bridging.token.flows

import java.util.*

/**
 * Captures the Corda soft lock id of a fungible token lock during the RedeemFungibleTokenFlow execution.
 * Token SDK (specifically AbstractMoveTokensFlow) does not expose the TransactionBuilder
 * that holds the lock id used to soft lock the tokens.
 */
class FungibleTokenLockCapture {
    private var _lockId: UUID? = null
    var lockId: UUID?
        get() {
            return checkNotNull(_lockId) { "Lock is not acquired" }
        }
        set(value) {
            check(_lockId == null) { "Lock is already acquired with id $_lockId" }
            _lockId = value
        }
}
