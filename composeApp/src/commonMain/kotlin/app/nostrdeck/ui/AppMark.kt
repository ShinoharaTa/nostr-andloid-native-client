package app.nostrdeck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * アプリアイコンと同一デザインのブランドマーク（左レール等で使用）。
 * androidMain のベクターリソース(ic_launcher_*)と同じサイン曲線の幾何を Compose Canvas で描く
 * （KMP 共通・tools/gen_icon.py と同じパラメータ）。108 の座標系をサイズに合わせてスケール。
 */
@Composable
fun AppMark(modifier: Modifier, cornerRadius: Dp = DeckRadius.Md) {
    Canvas(modifier) {
        val s = size.minDimension / 108f
        fun pt(x: Float, y: Float) = Offset(x * s, y * s)

        // 背景（黒の角丸ボックス）。
        drawRoundRect(color = DeckColors.Bg, cornerRadius = CornerRadius(cornerRadius.toPx()))

        // --- リレー波（3波長）＋端点ノード ---
        data class Wave(val y0: Float, val amp: Float, val wl: Float, val phase: Float)
        val waves = listOf(
            Wave(46f, 13f, 52f, 0f),
            Wave(62f, 10f, 66f, (PI * 0.55).toFloat()),
            Wave(54f, 8f, 34f, (PI * 1.1).toFloat()),
        )
        val x0 = 8f; val x1 = 100f; val n = 72
        fun yAt(w: Wave, x: Float) = w.y0 + w.amp * sin((2 * PI * (x - x0) / w.wl + w.phase)).toFloat()
        waves.forEach { w ->
            val path = Path()
            for (i in 0..n) {
                val x = x0 + (x1 - x0) * i / n
                val y = yAt(w, x)
                if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s)
            }
            drawPath(path, DeckColors.Like, style = Stroke(width = 2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        waves.forEach { w ->
            drawCircle(DeckColors.Text2, radius = 2.4f * s, center = pt(x0, yAt(w, x0)))
            drawCircle(DeckColors.Text2, radius = 2.4f * s, center = pt(x1, yAt(w, x1)))
        }

        // --- N（縦2本 + サインカーブの対角） ---
        val nw = 5.5f * s
        val nStroke = Stroke(width = nw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(DeckColors.Text, pt(42f, 40f), pt(42f, 69f), strokeWidth = nw, cap = StrokeCap.Round)
        drawLine(DeckColors.Text, pt(66f, 40f), pt(66f, 69f), strokeWidth = nw, cap = StrokeCap.Round)
        // 対角線: (42,40)→(66,69) を単一の弓（perpendicular に sin オフセット）。
        val diag = Path()
        val (dx, dy) = (66f - 42f) to (69f - 40f)
        val len = hypot(dx, dy)
        val px = -dy / len; val py = dx / len
        val amp = -4f
        val m = 48
        for (i in 0..m) {
            val t = i.toFloat() / m
            val bx = 42f + dx * t; val by = 40f + dy * t
            val off = amp * sin((PI * t)).toFloat()
            val x = bx + px * off; val y = by + py * off
            if (i == 0) diag.moveTo(x * s, y * s) else diag.lineTo(x * s, y * s)
        }
        drawPath(diag, DeckColors.Text, style = nStroke)
    }
}
