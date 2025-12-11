package io.github.syrou.reaktiv.core.util

import kotlinx.serialization.modules.SerializersModuleBuilder

/**
 * Interface for modules that need to register custom serializers for types used within their state.
 *
 * When your module's state contains polymorphic types, sealed classes, or types requiring
 * custom serializers, implement this interface to automatically register them with the Store.
 * The Store will automatically collect and use these serializers for both persistence and
 * DevTools integration.
 *
 * Usage example:
 * ```kotlin
 * @Serializable
 * sealed interface AppSettings
 *
 * @Serializable
 * data class BasicSettings(val theme: String) : AppSettings
 *
 * @Serializable
 * data class AdvancedSettings(val theme: String, val locale: String) : AppSettings
 *
 * @Serializable
 * data class UserState(
 *     val settings: AppSettings,
 *     val timestamp: Instant
 * ) : ModuleState
 *
 * object UserModule : Module<UserState, UserAction>, CustomTypeRegistrar {
 *     override val initialState = UserState(
 *         settings = BasicSettings("dark"),
 *         timestamp = Clock.System.now()
 *     )
 *
 *     override val reducer = { state, action ->
 *         when (action) {
 *             is UserAction.UpdateSettings -> state.copy(settings = action.settings)
 *         }
 *     }
 *
 *     override val createLogic = { dispatch -> UserLogic(dispatch) }
 *
 *     override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
 *         builder.polymorphic(AppSettings::class) {
 *             subclass(BasicSettings::class)
 *             subclass(AdvancedSettings::class)
 *         }
 *         builder.contextual(Instant::class, InstantSerializer)
 *     }
 * }
 *
 * val store = createStore {
 *     module(UserModule)
 * }
 * ```
 *
 * The Store automatically:
 * 1. Detects modules implementing CustomTypeRegistrar
 * 2. Calls registerAdditionalSerializers during Store construction
 * 3. Includes these serializers in the Store's SerializersModule
 * 4. Makes them available to persistence and DevTools middleware
 *
 * @see io.github.syrou.reaktiv.navigation.NavigationModule for a built-in example
 */
interface CustomTypeRegistrar {
    /**
     * Register additional serializers for types used within your module's state.
     *
     * @param builder SerializersModuleBuilder for registering polymorphic types, contextual serializers, etc.
     */
    fun registerAdditionalSerializers(builder: SerializersModuleBuilder)
}