package br.com.rotina

/** WCAG contrast helpers shared by task cards and unit tests. Colors are packed ARGB ints. */
internal fun compositeArgb(foreground: Int, background: Int, alpha: Float = ((foreground ushr 24) and 0xff) / 255f): Int {
    val a = alpha.coerceIn(0f, 1f)
    fun channel(shift: Int) = ((((foreground ushr shift) and 0xff) * a) + (((background ushr shift) and 0xff) * (1f - a))).toInt().coerceIn(0, 255)
    return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun taskSurfaceArgb(taskColor: Int?, surface: Int, done: Boolean): Int {
    val selected = taskColor ?: surface
    return if (done) compositeArgb(surface, selected, .28f) else selected
}

private fun linearChannel(value: Int): Double {
    val c = value / 255.0
    return if (c <= .04045) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4)
}

internal fun relativeLuminance(argb: Int): Double =
    .2126 * linearChannel((argb ushr 16) and 0xff) +
        .7152 * linearChannel((argb ushr 8) and 0xff) +
        .0722 * linearChannel(argb and 0xff)

internal fun contrastRatio(first: Int, second: Int): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
}

internal fun contentArgb(background: Int, minimumRatio: Double = 4.5): Int {
    val black = 0xff000000.toInt()
    val white = 0xffffffff.toInt()
    val blackRatio = contrastRatio(black, background)
    val whiteRatio = contrastRatio(white, background)
    val best = if (blackRatio >= whiteRatio) black else white
    return if (contrastRatio(best, background) >= minimumRatio) best else best
}

internal fun accentArgbOn(background: Int, preferred: Int, minimumRatio: Double = 3.0): Int {
    if (contrastRatio(preferred, background) >= minimumRatio) return preferred
    val black = 0xff000000.toInt()
    val white = 0xffffffff.toInt()
    val target = if (contrastRatio(black, background) >= contrastRatio(white, background)) black else white
    for (step in 1..100) {
        val t = step / 100f
        fun channel(shift: Int) = ((((preferred ushr shift) and 0xff) * (1f - t)) + (((target ushr shift) and 0xff) * t)).toInt()
        val candidate = (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        if (contrastRatio(candidate, background) >= minimumRatio) return candidate
    }
    return target
}
