package eu.syrou.androidexample.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.syrou.androidexample.reaktiv.TestNavigationModule.TestNavigationAction
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.extension.dismissModal
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.launch


object NotificationModal : Modal {
    override val route = "notification"
    override val enterTransition = NavTransition.SlideUpBottom
    override val exitTransition = NavTransition.SlideOutBottom
    override val requiresAuth = true
    override val titleResource: TitleResource? = { "Modal?" }

    @Composable
    override fun Content(params: Params) {
        val test = params.getTyped<List<String>>("TEST")
        println("TEST contains: $test")
        val scope = rememberCoroutineScope()
        val store = rememberStore()
        CustomDialogBox(onConfirmClick = {
            scope.launch {
                store.dispatch(TestNavigationAction.TriggerMultipleNavigation)
            }
        }, onDismissClick = {
            scope.launch {
                store.dismissModal()
            }
        })
    }
}

@Composable
fun CustomDialogBox(
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    title: String = "Dialog Title",
    content: String = "This is the dialog content.",
    confirmButtonText: String = "Confirm",
    dismissButtonText: String = "Cancel",
    onConfirmClick: () -> Unit = {},
    onDismissClick: () -> Unit = {},
    dialogAlignment: Alignment = Alignment.Center
) {
    if (isVisible) {
        Box(
            modifier = modifier
                .fillMaxSize(),
            contentAlignment = dialogAlignment
        ) {
            Card(
                modifier = Modifier
                    .padding(16.dp)
                    .widthIn(min = 280.dp, max = 400.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 8.dp
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = content,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = onDismissClick,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(text = dismissButtonText)
                        }
                        Button(
                            onClick = onConfirmClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(text = confirmButtonText)
                        }
                    }
                }
            }
        }
    }
}