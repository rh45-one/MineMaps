package app.minemaps.engine

import app.minemaps.engine.art.Anchor
import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TexturePack
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TexturePackTest {
    @Test
    fun originalPackLoadsWithAnchors() {
        assertTrue(EngineResources.isAvailable(TexturePack.ORIGINAL))
        val atlas = TestSupport.loadAtlas(TexturePack.ORIGINAL)
        assertEquals(Anchor(8, 13), atlas.anchor("pin"))
        assertEquals(Anchor(8, 8), atlas.anchor("diamond"))
        assertTrue(atlas.textureNames().all { atlas.variantCount(it) >= 1 })
    }

    @Test
    fun vanillaPackHasEveryNameOfTheOriginalWhenBundled() {
        assumeTrue("vanilla pack not bundled", EngineResources.isAvailable(TexturePack.VANILLA))
        val original = TestSupport.loadAtlas(TexturePack.ORIGINAL)
        val vanilla = TestSupport.loadAtlas(TexturePack.VANILLA)
        for (name in original.textureNames()) assertTrue(vanilla.hasTexture(name) && vanilla.variantCount(name) >= 1, "texture $name")
        for (name in original.iconNames()) assertTrue(vanilla.icon(name) != null, "icon $name")
        assertEquals(original.bookmarkIcons, vanilla.bookmarkIcons)
        assertEquals(original.tintableIcons, vanilla.tintableIcons)
        assertEquals(Anchor(8, 16), vanilla.anchor("pin"))
    }
}
