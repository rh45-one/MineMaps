package app.minemaps.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/** 12x12 pixel-art glyphs ('#' = filled), drawn with integer-snapped cells so edges stay crisp. */
enum class Glyph(vararg val rows: String) {
    SEARCH(
        "...####.....",
        "..##..##....",
        ".##....##...",
        ".#......#...",
        ".#......#...",
        ".##....##...",
        "..##..###...",
        "...#####....",
        ".......###..",
        "........###.",
        ".........###",
        "..........##",
    ),
    CLOSE(
        "##........##",
        "###......###",
        ".###....###.",
        "..###..###..",
        "...######...",
        "....####....",
        "....####....",
        "...######...",
        "..###..###..",
        ".###....###.",
        "###......###",
        "##........##",
    ),
    SETTINGS(
        ".....##.....",
        "..#..##..#..",
        ".###.##.###.",
        "..########..",
        "..###..###..",
        "####....####",
        "####....####",
        "..###..###..",
        "..########..",
        ".###.##.###.",
        "..#..##..#..",
        ".....##.....",
    ),
    CHEVRON_RIGHT(
        "............",
        "...##.......",
        "...###......",
        "....###.....",
        ".....###....",
        "......###...",
        "......###...",
        ".....###....",
        "....###.....",
        "...###......",
        "...##.......",
        "............",
    ),
    PLUS(
        "............",
        "....####....",
        "....####....",
        "....####....",
        ".##########.",
        ".##########.",
        ".##########.",
        ".##########.",
        "....####....",
        "....####....",
        "....####....",
        "............",
    ),
    MINUS(
        "............",
        "............",
        "............",
        "............",
        ".##########.",
        ".##########.",
        ".##########.",
        ".##########.",
        "............",
        "............",
        "............",
        "............",
    ),
    LOCATE(
        ".....##.....",
        ".....##.....",
        "...######...",
        "..##....##..",
        "..#......#..",
        "###..##..###",
        "###..##..###",
        "..#......#..",
        "..##....##..",
        "...######...",
        ".....##.....",
        ".....##.....",
    ),
    LOCATE_FILLED(
        ".....##.....",
        ".....##.....",
        "...######...",
        "..########..",
        "..########..",
        "############",
        "############",
        "..########..",
        "..########..",
        "...######...",
        ".....##.....",
        ".....##.....",
    ),
    BACK(
        "............",
        "....#.......",
        "...##.......",
        "..###.......",
        ".###########",
        "############",
        "############",
        ".###########",
        "..###.......",
        "...##.......",
        "....#.......",
        "............",
    ),
    EDIT(
        "........##..",
        ".......####.",
        "......####..",
        ".....####...",
        "....####....",
        "...####.....",
        "..####......",
        ".####.......",
        ".###........",
        "###.........",
        "............",
        "............",
    ),
    DELETE(
        "....####....",
        "############",
        "............",
        ".##########.",
        ".#.#.##.#.#.",
        ".#.#.##.#.#.",
        ".#.#.##.#.#.",
        ".#.#.##.#.#.",
        ".#.#.##.#.#.",
        ".##########.",
        "............",
        "............",
    ),
    INFO(
        "....####....",
        "....####....",
        "............",
        "...#####....",
        "....####....",
        "....####....",
        "....####....",
        "....####....",
        "....####....",
        "..########..",
        "..########..",
        "............",
    ),
    CHECK(
        "............",
        "..........##",
        ".........###",
        "........###.",
        "##.....###..",
        "###...###...",
        ".###.###....",
        "..#####.....",
        "...###......",
        "....#.......",
        "............",
        "............",
    ),
    STAR(
        ".....##.....",
        ".....##.....",
        "....####....",
        "############",
        ".##########.",
        "..########..",
        "..########..",
        ".####..####.",
        ".###....###.",
        ".##......##.",
        "............",
        "............",
    ),
    COPY(
        ".#######....",
        ".#.....#....",
        ".#..#######.",
        ".#..#.....#.",
        ".#..#.....#.",
        ".#..#.....#.",
        ".####.....#.",
        "....#.....#.",
        "....#.....#.",
        "....#######.",
        "............",
        "............",
    ),
    NAV_UP(
        ".....##.....",
        "....####....",
        "...######...",
        "..########..",
        ".###.##.###.",
        ".....##.....",
        ".....##.....",
        ".....##.....",
        ".....##.....",
        ".....##.....",
        ".....##.....",
        "............",
    ),
    NAV_LEFT(
        "....#.......",
        "...##.......",
        "..###.......",
        ".########...",
        "##########..",
        ".########...",
        "..###..##...",
        "...#...##...",
        ".......##...",
        ".......##...",
        ".......##...",
        "............",
    ),
    NAV_RIGHT(
        ".......#....",
        ".......##...",
        ".......###..",
        "...########.",
        "..##########",
        "...########.",
        "...##..###..",
        "...##...#...",
        "...##.......",
        "...##.......",
        "...##.......",
        "............",
    ),
    LAYERS(
        ".....##.....",
        "...######...",
        ".##########.",
        "...######...",
        ".....##.....",
        ".##......##.",
        "...######...",
        ".....##.....",
        ".##......##.",
        "...######...",
        ".....##.....",
        "............",
    ),
    ;

    companion object {
        const val SIZE = 12
    }
}

@Composable
fun PixelGlyph(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val cell = floor(this.size.minDimension / Glyph.SIZE).coerceAtLeast(1f)
        val used = cell * Glyph.SIZE
        val ox = floor((this.size.width - used) / 2f)
        val oy = floor((this.size.height - used) / 2f)
        glyph.rows.forEachIndexed { y, row ->
            var x = 0
            while (x < row.length) {
                if (row[x] == '#') {
                    var e = x
                    while (e < row.length && row[e] == '#') e++
                    drawRect(tint, Offset(ox + x * cell, oy + y * cell), Size((e - x) * cell, cell))
                    x = e
                } else {
                    x++
                }
            }
        }
    }
}

/** 12x12 pixel-art traffic light icon (red / amber / green lamps inside a dark housing). */
@Composable
fun TrafficLightGlyph(active: Boolean, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val cell = floor(this.size.minDimension / 12f).coerceAtLeast(1f)
        val used = cell * 12f
        val ox = floor((this.size.width - used) / 2f)
        val oy = floor((this.size.height - used) / 2f)
        val housing = Color(0xFF181A1D)
        val rim = if (active) Color(0xFFEAEAEA) else Color(0xFF8A8E94)
        val red = if (active) Color(0xFFFF3B30) else Color(0xFF7A3532)
        val amber = if (active) Color(0xFFFFC107) else Color(0xFF7A6328)
        val green = if (active) Color(0xFF38C764) else Color(0xFF2D6E42)
        // Outer rim & inner body (cols 3..8, rows 0..11)
        drawRect(rim, Offset(ox + 3 * cell, oy + 0 * cell), Size(6 * cell, 12 * cell))
        drawRect(housing, Offset(ox + 4 * cell, oy + 1 * cell), Size(4 * cell, 10 * cell))
        // Side visors
        for (ry in intArrayOf(2, 5, 8)) {
            drawRect(rim, Offset(ox + 2 * cell, oy + ry * cell), Size(cell, 2 * cell))
            drawRect(rim, Offset(ox + 9 * cell, oy + ry * cell), Size(cell, 2 * cell))
        }
        // 2x2 lamps: Red (y=1..2), Amber (y=5..6), Green (y=8..9)
        drawRect(red, Offset(ox + 5 * cell, oy + 2 * cell), Size(2 * cell, 2 * cell))
        drawRect(amber, Offset(ox + 5 * cell, oy + 5 * cell), Size(2 * cell, 2 * cell))
        drawRect(green, Offset(ox + 5 * cell, oy + 8 * cell), Size(2 * cell, 2 * cell))
    }
}
