package app.minemaps.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.minemaps.engine.art.TextureAtlas

fun atlasImage(atlas: TextureAtlas, name: String, tint: Int? = null): ImageBitmap? {
    val px = atlas.icon(name) ?: return null
    val pixels = if (tint != null) TextureAtlas.tintIcon(px, tint) else px
    return Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888).asImageBitmap()
}

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
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon) }
    val images = rememberBookmarkImages(atlas)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), modifier = Modifier.fillMaxWidth().height(232.dp)) {
                    items(atlas.bookmarkIcons) { n ->
                        val selected = n == icon
                        Box(
                            Modifier.padding(2.dp).size(44.dp)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                                .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                .semantics { contentDescription = "Icon $n" }
                                .clickable { icon = n },
                            contentAlignment = Alignment.Center,
                        ) { PixelIcon(images[n], 40.dp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim(), icon) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
