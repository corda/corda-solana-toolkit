package com.r3.corda.lib.solana.bridging.token.flows

import java.util.*

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
