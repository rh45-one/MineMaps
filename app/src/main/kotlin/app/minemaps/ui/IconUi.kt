package app.minemaps.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.minemaps.data.SavedPlaceNames
import app.minemaps.engine.art.TextureAtlas
import app.minemaps.ui.theme.McButton
import app.minemaps.ui.theme.McDialog
import app.minemaps.ui.theme.McSlot
import app.minemaps.ui.theme.McTextField
import app.minemaps.ui.theme.McVariant
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

fun atlasImage(atlas: TextureAtlas, name: String, tint: Int? = null): ImageBitmap? {
    val px = atlas.icon(name) ?: return null
    val pixels = if (tint != null) TextureAtlas.tintIcon(px, tint) else px
    return Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888).asImageBitmap()
}

fun terrainImage(atlas: TextureAtlas, name: String): ImageBitmap? = runCatching {
    Bitmap.createBitmap(atlas.mip(name, 0, 16), 16, 16, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

@Composable
fun rememberBookmarkImages(atlas: TextureAtlas): Map<String, ImageBitmap> =
    remember(atlas) { (atlas.bookmarkIcons + "pin").distinct().mapNotNull { n -> atlasImage(atlas, n)?.let { n to it } }.toMap() }

@Composable
fun PixelIcon(image: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    if (image != null) Image(bitmap = image, contentDescription = null, modifier = modifier.size(size), filterQuality = FilterQuality.None)
    else Spacer(modifier.size(size))
}

@Composable
fun IconNameDialog(
    title: String,
    initialName: String,
    initialIcon: String,
    atlas: TextureAtlas,
    confirmLabel: String,
    onConfirm: (name: String, icon: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var icon by rememberSaveable { mutableStateOf(if (initialIcon in atlas.bookmarkIcons) initialIcon else "pin") }
    val images = rememberBookmarkImages(atlas)
    val gridHeight = minOf(240, (LocalConfiguration.current.screenHeightDp * 0.4f).toInt()).dp
    McDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            McButton(onDismiss) { Text("Cancel", style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
            McButton({ onConfirm(SavedPlaceNames.clean(name), icon) }, variant = McVariant.PRIMARY) { Text(confirmLabel, style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
        },
    ) {
        McTextField(
            value = name,
            onValueChange = { name = it.take(SavedPlaceNames.MAX_LENGTH) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Name",
            keyboardActions = KeyboardActions.Default,
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(56.dp), modifier = Modifier.fillMaxWidth().height(gridHeight)) {
            items(atlas.bookmarkIcons) { n ->
                McSlot(Modifier.padding(2.dp).semantics { contentDescription = "Icon $n" }, size = 52.dp, selected = n == icon, onClick = { icon = n }) { PixelIcon(images[n], 36.dp) }
            }
        }
    }
}
