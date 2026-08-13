package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.serialization.Redacted
import io.github.syrou.reaktiv.core.serialization.RedactedAs
import io.github.syrou.reaktiv.core.util.reaktivJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
enum class ClearanceLevel { LOW, HIGH }

@Serializable
enum class TokenKind { UNKNOWN, BEARER, COOKIE }

@Serializable(with = StrictInstantSerializer::class)
data class StrictInstant(val iso: String)

object StrictInstantSerializer : KSerializer<StrictInstant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.datetime.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: StrictInstant) {
        encoder.encodeString(value.iso)
    }

    override fun deserialize(decoder: Decoder): StrictInstant {
        val raw = decoder.decodeString()
        require(raw.length >= 10 && raw[4] == '-') { "not an instant: $raw" }
        return StrictInstant(raw)
    }
}

@Serializable(with = OpaqueStampSerializer::class)
data class OpaqueStamp(val raw: String)

object OpaqueStampSerializer : KSerializer<OpaqueStamp> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.example.OpaqueStamp", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OpaqueStamp) {
        encoder.encodeString(value.raw)
    }

    override fun deserialize(decoder: Decoder): OpaqueStamp {
        val raw = decoder.decodeString()
        require(raw.startsWith("stamp:")) { "not a stamp: $raw" }
        return OpaqueStamp(raw)
    }
}

@Serializable
sealed interface TestCredential

@Serializable
@SerialName("basic")
data class BasicTestCredential(val secret: String = "s3cr3t", val retries: Int = 2) : TestCredential

@Serializable
data class VaultTestState(
    val password: String = "hunter2",
    val secretLevel: ClearanceLevel = ClearanceLevel.HIGH,
    val tokenKind: TokenKind = TokenKind.BEARER,
    @Redacted val internalCode: ClearanceLevel = ClearanceLevel.HIGH,
    @RedactedAs("HIGH") val tier: ClearanceLevel = ClearanceLevel.HIGH,
    val secretMode: ClearanceLevel? = ClearanceLevel.HIGH,
    val tokenIssuedAt: StrictInstant = StrictInstant("2026-07-23T10:00:00Z"),
    val ssn: Long = 123456789,
    val passwordSet: Boolean = true,
    val plainName: String = "ok"
) : ModuleState

@Serializable
data class AuthTestState(
    val credentials: TestCredential = BasicTestCredential(),
    val mode: TokenKind = TokenKind.COOKIE
) : ModuleState

@Serializable
data class UnsafeTestState(
    val secretStamp: OpaqueStamp = OpaqueStamp("stamp:abc")
) : ModuleState

class TypeSafeRedactionTest {

    private val testSerializers = SerializersModule {
        polymorphic(ModuleState::class) {
            subclass(VaultTestState::class)
            subclass(AuthTestState::class)
            subclass(UnsafeTestState::class)
        }
    }

    private val json = reaktivJson(serializersModule = testSerializers, encodeDefaults = true)

    private val normalizedKeys = DEFAULT_SENSITIVE_KEYS.map { it.normalizeRedactionKey() }

    private fun encodeRedacted(state: ModuleState): TypeSafeRedactionOutcome {
        val element = json.encodeToJsonElement(PolymorphicSerializer(ModuleState::class), state).jsonObject
        val strategy = json.serializersModule.getPolymorphic(ModuleState::class, state)!!
        return redactModuleElement(json.serializersModule, strategy.descriptor, element, normalizedKeys)
    }

    private fun decodeRestored(obj: JsonObject): ModuleState =
        json.decodeFromString(
            PolymorphicSerializer(ModuleState::class),
            restoreRedactedModuleElement(json, obj).toString()
        )

    @Test
    fun `an enum under a sensitive key masks to the placeholder and decodes to the first constant`() {
        val masked = encodeRedacted(VaultTestState()).element
        assertEquals(REDACTED_PLACEHOLDER, masked["secretLevel"]!!.jsonPrimitive.content)
        val decoded = decodeRestored(masked) as VaultTestState
        assertEquals(ClearanceLevel.LOW, decoded.secretLevel)
    }

    @Test
    fun `an enum fallback prefers a constant named unknown`() {
        val masked = encodeRedacted(VaultTestState()).element
        assertEquals(REDACTED_PLACEHOLDER, masked["tokenKind"]!!.jsonPrimitive.content)
        val decoded = decodeRestored(masked) as VaultTestState
        assertEquals(TokenKind.UNKNOWN, decoded.tokenKind)
    }

    @Test
    fun `redactedAs pins the enum restore value`() {
        val masked = encodeRedacted(VaultTestState()).element
        assertEquals(REDACTED_PLACEHOLDER, masked["tier"]!!.jsonPrimitive.content)
        val decoded = decodeRestored(masked) as VaultTestState
        assertEquals(ClearanceLevel.HIGH, decoded.tier)
    }

    @Test
    fun `a redacted annotation masks a field with a harmless name`() {
        val masked = encodeRedacted(VaultTestState()).element
        assertEquals(REDACTED_PLACEHOLDER, masked["internalCode"]!!.jsonPrimitive.content)
        val decoded = decodeRestored(masked) as VaultTestState
        assertEquals(ClearanceLevel.LOW, decoded.internalCode)
    }

    @Test
    fun `a nullable enum under a sensitive key restores to null`() {
        val masked = encodeRedacted(VaultTestState()).element
        assertEquals(REDACTED_PLACEHOLDER, masked["secretMode"]!!.jsonPrimitive.content)
        val decoded = decodeRestored(masked) as VaultTestState
        assertNull(decoded.secretMode)
    }

    @Test
    fun `an instant under a sensitive key restores to a decodable epoch`() {
        val masked = encodeRedacted(VaultTestState()).element
        assertEquals(REDACTED_PLACEHOLDER, masked["tokenIssuedAt"]!!.jsonPrimitive.content)
        val decoded = decodeRestored(masked) as VaultTestState
        assertEquals("1970-01-01T00:00:00Z", decoded.tokenIssuedAt.iso)
    }

    @Test
    fun `a sealed subtree under a sensitive key keeps its discriminator and decodes`() {
        val masked = encodeRedacted(AuthTestState()).element
        val credentials = masked["credentials"]!!.jsonObject
        assertEquals("basic", credentials["type"]!!.jsonPrimitive.content)
        assertEquals(REDACTED_PLACEHOLDER, credentials["secret"]!!.jsonPrimitive.content)
        val decoded = decodeRestored(masked) as AuthTestState
        val basic = decoded.credentials as BasicTestCredential
        assertEquals(REDACTED_PLACEHOLDER, basic.secret)
        assertEquals(2, basic.retries)
        assertEquals(TokenKind.COOKIE, decoded.mode)
    }

    @Test
    fun `plain strings numbers and booleans keep the shape preserving behavior`() {
        val masked = encodeRedacted(VaultTestState()).element
        assertEquals(REDACTED_PLACEHOLDER, masked["password"]!!.jsonPrimitive.content)
        assertEquals("0", masked["ssn"]!!.jsonPrimitive.content)
        assertEquals("true", masked["passwordSet"]!!.jsonPrimitive.content)
        assertEquals("ok", masked["plainName"]!!.jsonPrimitive.content)
        val decoded = decodeRestored(masked) as VaultTestState
        assertEquals(REDACTED_PLACEHOLDER, decoded.password)
        assertEquals(0L, decoded.ssn)
        assertTrue(decoded.passwordSet)
        assertEquals("ok", decoded.plainName)
    }

    @Test
    fun `an unrestorable custom format is reported at capture time`() {
        val outcome = encodeRedacted(UnsafeTestState())
        assertEquals(REDACTED_PLACEHOLDER, outcome.element["secretStamp"]!!.jsonPrimitive.content)
        assertTrue(outcome.unrestorablePaths.isNotEmpty())
        assertTrue(outcome.unrestorablePaths.first().contains("secretStamp"))
        assertTrue(outcome.unrestorablePaths.first().contains("com.example.OpaqueStamp"))
    }

    @Test
    fun `legacy element level masking is repaired on restore`() {
        val plain = json.encodeToJsonElement(
            PolymorphicSerializer(ModuleState::class), VaultTestState()
        ).jsonObject
        val legacyMasked = sensitiveKeyRedactor().redact("module", plain).jsonObject
        assertEquals(REDACTED_PLACEHOLDER, legacyMasked["secretLevel"]!!.jsonPrimitive.content)
        val decoded = decodeRestored(legacyMasked) as VaultTestState
        assertEquals(ClearanceLevel.LOW, decoded.secretLevel)
        assertEquals(ClearanceLevel.HIGH, decoded.tier)
        assertNull(decoded.secretMode)
        assertEquals("1970-01-01T00:00:00Z", decoded.tokenIssuedAt.iso)
    }
}
