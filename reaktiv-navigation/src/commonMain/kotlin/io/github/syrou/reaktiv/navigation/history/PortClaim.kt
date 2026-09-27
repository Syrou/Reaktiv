package io.github.syrou.reaktiv.navigation.history

internal class PortClaim {
    private var owner: Any? = null

    var listener: BrowserListener? = null
        private set

    fun claim(owner: Any, listener: BrowserListener): ClaimResult {
        val current = this.listener
        return when {
            this.owner == null -> {
                this.owner = owner
                this.listener = listener
                ClaimResult.Bound
            }
            this.owner === owner && current != null -> {
                this.listener = listener
                ClaimResult.Rebound(current)
            }
            else -> ClaimResult.Denied
        }
    }

    fun release(owner: Any, listener: BrowserListener) {
        if (this.owner === owner && this.listener === listener) {
            this.owner = null
            this.listener = null
        }
    }
}
