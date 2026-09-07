import io.github.syrou.reaktiv.navigation.dsl.DeepLinkAlias
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeepLinkAliasMatchTest {

    private val invitation = DeepLinkAlias("invitations/{type}/confirm/{token}", "home/invitation/{type}")
    private val release = DeepLinkAlias("releases/{release-id}", "home/releases/release-info")
    private val streams = DeepLinkAlias("studio/streams", "home/insight")
    private val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpbnZpdGVyX2ZpcnN0X25hbWUiOiJhZnR5In0.tuoz6CMGaY9bsWSrz_eBYFli-ljV4KFAhVhBK1mztN0"

    @Test
    fun placeholdersMatchTokensWithDotsUnderscoresAndDashes() {
        val params = assertNotNull(invitation.matchAndExtract("invitations/team/confirm/$jwt"))
        assertEquals("team", params.getString("type"))
        assertEquals(jwt, params.getString("token"))
    }

    @Test
    fun placeholderMatchesNumericId() {
        val params = assertNotNull(release.matchAndExtract("releases/1234"))
        assertEquals("1234", params.getString("release-id"))
    }

    @Test
    fun leadingSlashOnTheLinkDoesNotPreventAMatch() {
        assertNotNull(invitation.matchAndExtract("/invitations/team/confirm/$jwt"))
        assertNotNull(release.matchAndExtract("/releases/1234"))
        assertNotNull(streams.matchAndExtract("/studio/streams"))
    }

    @Test
    fun leadingSlashOnThePatternDoesNotPreventAMatch() {
        val slashed = DeepLinkAlias("/studio/streams", "home/insight")
        assertNotNull(slashed.matchAndExtract("studio/streams"))
        assertNotNull(slashed.matchAndExtract("/studio/streams"))
    }

    @Test
    fun aPatternStillDoesNotMatchAnUnrelatedPath() {
        assertNull(streams.matchAndExtract("studio/streams/extra"))
        assertNull(release.matchAndExtract("releases"))
    }
}
