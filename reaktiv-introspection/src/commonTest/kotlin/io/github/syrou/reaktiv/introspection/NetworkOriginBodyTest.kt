package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkBodyProvider
import io.github.syrou.reaktiv.introspection.network.NetworkBodySlice
import io.github.syrou.reaktiv.introspection.network.NetworkBodySource
import io.github.syrou.reaktiv.introspection.network.NetworkTap
import io.github.syrou.reaktiv.introspection.network.sliceOnCharBoundary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkOriginBodyTest {

    private val origin = NetworkBodyProvider { requestId, _, offset, maxBytes ->
        if (requestId == "live") ("x".repeat(600_000)).encodeToByteArray().sliceOnCharBoundary(offset, maxBytes) else null
    }

    private val archive = object : NetworkBodyProvider {
        override val source: NetworkBodySource = NetworkBodySource.Archive

        override fun slice(requestId: String, part: NetworkBodyPart, offset: Int, maxBytes: Int): NetworkBodySlice =
            "archived".encodeToByteArray().sliceOnCharBoundary(offset, maxBytes)
    }

    @Test
    fun `an origin body is read whole and never from the archive`() {
        NetworkTap.addBodyProvider(archive)
        NetworkTap.addBodyProvider(origin)
        try {
            assertEquals(600_000, NetworkTap.originBody("live", NetworkBodyPart.RESPONSE)?.length)
            assertNull(NetworkTap.originBody("evicted", NetworkBodyPart.RESPONSE))
        } finally {
            NetworkTap.removeBodyProvider(archive)
            NetworkTap.removeBodyProvider(origin)
        }
    }
}
