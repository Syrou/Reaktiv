package eu.syrou.example.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

enum class RemoteImageStyle { Cover, Avatar }

@Composable
fun RemoteImage(url: String?, style: RemoteImageStyle, modifier: Modifier = Modifier) {
    if (url.isNullOrEmpty()) {
        Box(modifier)
        return
    }
    AsyncImage(
        modifier = if (style == RemoteImageStyle.Avatar) modifier.clip(CircleShape) else modifier,
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        contentScale = ContentScale.Crop,
        clipToBounds = true,
        contentDescription = null
    )
}
