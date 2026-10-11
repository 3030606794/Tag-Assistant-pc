package cn.guangyao.eplan

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A fuse rating belongs to its printed function line, not the nearest device identifier. */
internal object LayoutFuseRatings {
    private val rating = Regex("^[（(]\\s*([0-9]+(?:[.][0-9]+)?)\\s*A\\s*[）)]$", RegexOption.IGNORE_CASE)
    private val prefix = Regex("^[（(]\\s*[0-9]+(?:[.][0-9]+)?\\s*A\\s*[）)]", RegexOption.IGNORE_CASE)

    internal fun isRating(box: PdfTextBox): Boolean = !box.blue && rating.matches(box.text.trim())

    /** Native ID/current/caption identity permits printed overlap, never a neighboring row. */
    internal fun samePrintedLine(current: PdfTextBox, caption: PdfTextBox): Boolean {
        if (!isRating(current)) return false
        val difference = abs(current.angle - caption.angle) % 360
        if (min(difference, 360 - difference) > 3) return false
        val radians = Math.toRadians(caption.angle.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        val x = current.baselineX - caption.baselineX
        val y = current.baselineY - caption.baselineY
        val font = min(current.fontSize, caption.fontSize).coerceAtLeast(.5f)
        val along = x * dx + y * dy
        return abs(-x * dy + y * dx) <= max(.55f, font * .25f) &&
            along >= -current.advance - max(2f, font * 1.5f) &&
            along <= caption.advance + max(2f, font * 1.5f)
    }

    private fun nativeCurrent(page: PdfPageText, deviceId: String, caption: PdfTextBox): PdfTextBox? {
        fun native(box: PdfTextBox) = box.sourceObject >= 0 && box.sourceRun >= 0 &&
            box.sourceSequence >= 0 && box.sourceEndSequence >= box.sourceSequence
        if (!native(caption)) return null
        val preceding = page.boxes.filter { native(it) && it.sourceEndSequence < caption.sourceSequence }
            .sortedByDescending { it.sourceSequence }.take(2)
        if (preceding.size != 2) return null
        val current = preceding[0]
        val id = preceding[1]
        if (EplanParsing.deviceId(id.text, requireLeader = false) != deviceId ||
            !id.blue || !caption.blue || current.sourceRun <= id.sourceRun || caption.sourceRun <= current.sourceRun ||
            id.sourceEndSequence >= current.sourceSequence || !samePrintedLine(current, caption)) return null
        return current
    }

    fun decorate(page: PdfPageText, deviceId: String, function: AbFunctionEvidence): AbFunctionEvidence {
        if (EplanParsing.deviceCategory(deviceId) != "FU" || prefix.containsMatchIn(function.text)) return function
        val anchor = function.box
        nativeCurrent(page, deviceId, anchor)?.let { current ->
            val text = "(${rating.matchEntire(current.text.trim())!!.groupValues[1]}A)" + function.text
            return function.copy(text = text, box = EplanParsing.mergeBoxes(listOf(anchor, current), text))
        }
        val radians = Math.toRadians(anchor.angle.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        val candidates = page.boxes.mapNotNull { candidate ->
            val value = rating.matchEntire(candidate.text.trim()) ?: return@mapNotNull null
            val difference = abs(candidate.angle - anchor.angle) % 360
            if (min(difference, 360 - difference) > 3) return@mapNotNull null
            val font = min(candidate.fontSize, anchor.fontSize).coerceAtLeast(.5f)
            val offsetX = anchor.baselineX - candidate.baselineX
            val offsetY = anchor.baselineY - candidate.baselineY
            val across = abs(-offsetX * dy + offsetY * dx)
            val gap = offsetX * dx + offsetY * dy - candidate.advance
            // Tight baseline alignment matters on the 3.46-point fuse rows in real EPLAN layouts.
            if (across > max(.55f, font * .25f) || gap < -font * .3f || gap > max(2f, font * 1.5f)) null
            else Triple(candidate, "(${value.groupValues[1]}A)", abs(gap) + across * 4f)
        }.sortedBy { it.third }
        val chosen = candidates.firstOrNull() ?: return function
        if (candidates.drop(1).any { abs(it.third - chosen.third) < .5f && it.second != chosen.second }) return function
        val text = chosen.second + function.text
        return function.copy(text = text, box = EplanParsing.mergeBoxes(listOf(anchor, chosen.first), text))
    }
}
