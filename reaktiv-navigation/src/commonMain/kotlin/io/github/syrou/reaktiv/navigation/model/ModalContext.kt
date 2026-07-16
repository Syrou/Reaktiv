package io.github.syrou.reaktiv.navigation.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Tracks the relationship between a modal and the screen it was opened on top of.
 *
 * Created automatically by the navigation system when a [Modal] is pushed onto the back stack.
 * It ensures that the correct underlying screen is rendered behind the modal, and that
 * navigation from within a modal (e.g. navigating to a new screen) is handled correctly.
 *
 * @property modalEntry The [NavigationEntry] for the modal itself.
 * @property originalUnderlyingScreenEntry The screen that was active when the modal was opened;
 *   rendered behind the modal while it is visible.
 * @property navigatedAwayToRoute If the user navigated to a new screen from inside the modal,
 *   this holds that screen's route so it can be restored on back-press.
 */
@Serializable
public data class ModalContext(
    @Contextual val modalEntry: NavigationEntry,
    @Contextual val originalUnderlyingScreenEntry: NavigationEntry,
    val navigatedAwayToRoute: String? = null
)
