package eu.syrou.androidexample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.syrou.androidexample.reaktiv.lifecycledemo.LifecycleDemoModule
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberDispatcher
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.RemovalReason
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch

object LifecycleDemoScreen : Screen {
    override val route: String = "lifecycle-demo"
    override val titleResource: TitleResource = {
        "Lifecycle Demo"
    }
    override val enterTransition: NavTransition = NavTransition.Scale(800)
    override val exitTransition: NavTransition = NavTransition.None

    override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
        lifecycle.invokeOnRemoval { reason ->
            if (reason == RemovalReason.NAVIGATION) {
                launch {
                    dispatch(LifecycleDemoModule.LifecycleDemoAction.ClearFields)
                }
            }
        }
    }

    @Composable
    override fun Content(params: Params) {
        LifecycleDemoContent()
    }
}

@Composable
private fun LifecycleDemoContent() {
    val state by composeState<LifecycleDemoModule.LifecycleDemoState>()
    val dispatch = rememberDispatcher()
    val store = rememberStore()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Lifecycle Demo",
            style = MaterialTheme.typography.headlineMedium
        )
        Card {
            Text(
                text = "The fields below live in module state and are cleared by " +
                    "invokeOnRemoval when this screen is popped. Fill them in, then " +
                    "swipe or press back: the values stay visible for the whole exit " +
                    "animation and clear only after the screen is gone. Navigating " +
                    "deeper keeps them, because a covered screen is not removed.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
        Text(
            "Cleared on exit ${state.timesCleared} times",
            style = MaterialTheme.typography.titleMedium
        )
        StoreBackedTextField(
            storeValue = state.name,
            label = "Name",
            onTextChange = { dispatch(LifecycleDemoModule.LifecycleDemoAction.SetName(it)) }
        )
        StoreBackedTextField(
            storeValue = state.email,
            label = "Email",
            onTextChange = { dispatch(LifecycleDemoModule.LifecycleDemoAction.SetEmail(it)) }
        )
        StoreBackedTextField(
            storeValue = state.notes,
            label = "Notes",
            onTextChange = { dispatch(LifecycleDemoModule.LifecycleDemoAction.SetNotes(it)) }
        )
        Button(
            onClick = {
                store.launch {
                    store.navigation {
                        navigateTo(PullToRefreshDemoScreen.route)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Navigate deeper (fields survive)")
        }
    }
}

@Composable
private fun StoreBackedTextField(
    storeValue: String,
    label: String,
    onTextChange: (String) -> Unit
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(storeValue, TextRange(storeValue.length))) }
    var isFocused by remember { mutableStateOf(false) }

    if (!isFocused && fieldValue.text != storeValue) {
        fieldValue = TextFieldValue(storeValue, TextRange(storeValue.length))
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { newValue ->
            val textChanged = newValue.text != fieldValue.text
            fieldValue = newValue
            if (textChanged) {
                onTextChange(newValue.text)
            }
        },
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
    )
}
