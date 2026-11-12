package com.example.parkingfinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.example.parkingfinder.data.ParkingSpot

enum class Tier(val argb: Int, val textArgb: Int) {
    CHEAP(0xFF2E7D32.toInt(), 0xFFFFFFFF.toInt()),   // зелений
    MID(0xFFF9A825.toInt(), 0xFF000000.toInt()),     // жовтий
    PRICEY(0xFFC62828.toInt(), 0xFFFFFFFF.toInt()),  // червоний
}

/** Найдешевша третина (за ціною години) — зелена, найдорожча — червона. */
fun computeTiers(spots: List<ParkingSpot>): Map<String, Tier> {
    val sorted = spots.filter { it.pricePerHour != null }.sortedBy { it.pricePerHour }
    if (sorted.isEmpty()) return emptyMap()
    val n = sorted.size
    val cheapCut = maxOf(1, n / 3)
    val midCut = maxOf(cheapCut, (2 * n) / 3)
    return sorted.mapIndexed { i, s ->
        s.id to when {
            i < cheapCut -> Tier.CHEAP
            i < midCut -> Tier.MID
            else -> Tier.PRICEY
        }
    }.toMap()
}

/** Малює «бейдж» з двома рядками: ціна за годину та за добу. Вибраний має чорну рамку. */
fun priceBitmap(ctx: Context, hourText: String, dayText: String, tier: Tier, selected: Boolean): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val padH = 8 * d
    val padV = 5 * d
    val pointer = 7 * d
    val m = 2 * d

    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tier.textArgb; textSize = 14 * d; typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tier.textArgb; textSize = 11 * d
        textAlign = Paint.Align.CENTER
    }
    val w = maxOf(bold.measureText(hourText), small.measureText(dayText)) + 2 * padH
    val line1 = -bold.fontMetrics.ascent
    val line2 = -small.fontMetrics.ascent
    val boxH = padV + line1 + 2 * d + line2 + padV + small.fontMetrics.descent

    val bmp = Bitmap.createBitmap(
        (w + 2 * m).toInt() + 1,
        (boxH + pointer + 2 * m).toInt() + 1,
        Bitmap.Config.ARGB_8888,
    )
    val c = Canvas(bmp)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tier.argb }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = (if (selected) 3f else 1.5f) * d
    }
    val rect = RectF(m, m, w + m, boxH + m)
    c.drawRoundRect(rect, 8 * d, 8 * d, fill)
    c.drawRoundRect(rect, 8 * d, 8 * d, stroke)

    val cx = rect.centerX()
    val tri = Path().apply {
        moveTo(cx - pointer, rect.bottom - 1f)
        lineTo(cx + pointer, rect.bottom - 1f)
        lineTo(cx, rect.bottom + pointer)
        close()
    }
    c.drawPath(tri, fill)

    c.drawText(hourText, cx, m + padV + line1, bold)
    c.drawText(dayText, cx, m + padV + line1 + 2 * d + line2, small)
    return bmp
}

/** Кольорова крапка з білою обводкою: для «Ви тут» і центру пошуку. */
fun dotBitmap(ctx: Context, color: Int): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val size = (24 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(size / 2f, size / 2f, 11 * d, p)
    p.color = color
    c.drawCircle(size / 2f, size / 2f, 8 * d, p)
    return bmp
}
