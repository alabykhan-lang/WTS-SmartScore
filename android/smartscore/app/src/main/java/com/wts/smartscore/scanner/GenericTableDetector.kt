package com.wts.smartscore.scanner

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.Core
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A score cell found from the printed grid rather than a manifest. */
data class GenericScoreCell(
    val rowIndex: Int,
    val columnIndex: Int,
    val rect: Rect,
    val confidence: Double
)

/**
 * Table geometry retained with each generic page. Coordinates are in the
 * corrected source bitmap, so a later review/export can point back to the
 * exact pixels that produced a reading.
 */
data class GenericTableDetection(
    val tableRect: Rect,
    val rowLines: List<Int>,
    val columnLines: List<Int>,
    val scoreColumns: List<Int>,
    val cells: List<GenericScoreCell>,
    val confidence: Double,
    val method: String
) {
    val rowCount: Int get() = (rowLines.size - 1).coerceAtLeast(0)
    val columnCount: Int get() = (columnLines.size - 1).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("method", method)
        put("confidence", confidence)
        put("table_rect", rectJson(tableRect))
        put("row_lines", JSONArray().apply { rowLines.forEach { line -> put(line) } })
        put("column_lines", JSONArray().apply { columnLines.forEach { line -> put(line) } })
        put("score_columns", JSONArray().apply { scoreColumns.forEach { column -> put(column) } })
        put("row_count", rowCount)
        put("column_count", columnCount)
        put("cells", JSONArray().apply {
            cells.forEach { cell ->
                put(JSONObject().apply {
                    put("row", cell.rowIndex + 1)
                    put("column", cell.columnIndex + 1)
                    put("rect", rectJson(cell.rect))
                    put("confidence", cell.confidence)
                })
            }
        })
    }

    private fun rectJson(rect: Rect): JSONObject = JSONObject().apply {
        put("left", rect.left)
        put("top", rect.top)
        put("right", rect.right)
        put("bottom", rect.bottom)
        put("width", rect.width())
        put("height", rect.height())
    }
}

/**
 * Lightweight grid understanding for ordinary record sheets. It deliberately
 * finds the printed table before looking at handwriting; no document identity
 * or page-wide OCR is needed to produce editable score cells.
 */
object GenericTableDetector {
    private const val MAX_ANALYSIS_DIMENSION = 1800

    fun detect(bitmap: Bitmap): GenericTableDetection? {
        if (bitmap.width < 80 || bitmap.height < 80) return null
        val rgba = Mat()
        val gray = Mat()
        val binary = Mat()
        val horizontal = Mat()
        val vertical = Mat()
        val combined = Mat()
        val connected = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val analysisWidth: Int
        val analysisHeight: Int
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val scale = min(1.0, MAX_ANALYSIS_DIMENSION.toDouble() / max(gray.cols(), gray.rows()).toDouble())
            analysisWidth = max(1, (gray.cols() * scale).roundToInt())
            analysisHeight = max(1, (gray.rows() * scale).roundToInt())
            val analysis = Mat()
            try {
                if (analysisWidth != gray.cols() || analysisHeight != gray.rows()) {
                    Imgproc.resize(gray, analysis, Size(analysisWidth.toDouble(), analysisHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                } else {
                    gray.copyTo(analysis)
                }
                // Otsu handles the high-contrast printed grid returned by the
                // Google document scanner and keeps handwriting as ink too.
                Imgproc.threshold(analysis, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
                val horizontalKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(max(12, analysis.cols() / 28).toDouble(), 1.0)
                )
                val verticalKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    // Score boxes can be short and separated by a row line;
                    // a page-height kernel silently deletes those useful
                    // boundaries on the recovered V2 sheet.
                    Size(1.0, max(8, analysis.rows() / 40).toDouble())
                )
                try {
                    Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, horizontalKernel)
                    Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, verticalKernel)
                    Core.bitwise_or(horizontal, vertical, combined)
                    Imgproc.dilate(combined, connected, kernel, Point(-1.0, -1.0), 2)
                } finally {
                    horizontalKernel.release()
                    verticalKernel.release()
                }

                val horizontalLines = lineCenters(horizontal, true)
                val verticalLines = lineCenters(vertical, false)
                val candidate = findTableRect(connected, horizontalLines, verticalLines, analysis.cols(), analysis.rows())
                    ?: fallbackRect(horizontalLines, verticalLines, analysis.cols(), analysis.rows())
                if (candidate == null) return null

                val localRows = boundedLines(horizontalLines, candidate.top, candidate.bottom, candidate.height, true)
                val localColumns = boundedLines(verticalLines, candidate.left, candidate.right, candidate.width, false)
                if (localRows.size < 3 || localColumns.size < 3) return null

                val rowLines = localRows.map { (it / scale).roundToInt() }.distinct().sorted()
                val columnLines = localColumns.map { (it / scale).roundToInt() }.distinct().sorted()
                val tableRect = Rect(
                    (candidate.left / scale).roundToInt().coerceIn(0, bitmap.width - 1),
                    (candidate.top / scale).roundToInt().coerceIn(0, bitmap.height - 1),
                    (candidate.right / scale).roundToInt().coerceIn(1, bitmap.width),
                    (candidate.bottom / scale).roundToInt().coerceIn(1, bitmap.height)
                )
                val scoreColumns = chooseScoreColumns(columnLines)
                if (scoreColumns.isEmpty()) return null

                val rowCount = rowLines.size - 1
                val cells = scoreColumns.flatMap { columnIndex ->
                    (0 until rowCount).mapNotNull { rowIndex ->
                        val left = columnLines[columnIndex]
                        val right = columnLines[columnIndex + 1]
                        val top = rowLines[rowIndex]
                        val bottom = rowLines[rowIndex + 1]
                        val insetX = max(2, ((right - left) * 0.08).roundToInt())
                        val insetY = max(2, ((bottom - top) * 0.12).roundToInt())
                        val rect = Rect(left + insetX, top + insetY, right - insetX, bottom - insetY)
                        if (rect.width() < 4 || rect.height() < 4) null
                        else GenericScoreCell(rowIndex, scoreColumns.indexOf(columnIndex), rect, cellConfidence(rect, tableRect))
                    }
                }.sortedWith(compareBy<GenericScoreCell> { it.rowIndex }.thenBy { it.columnIndex })
                if (cells.isEmpty()) return null

                val regularity = spacingRegularity(rowLines).coerceIn(0.0, 1.0)
                val area = tableRect.width().toDouble() * tableRect.height().toDouble()
                val coverage = (area / (bitmap.width.toDouble() * bitmap.height.toDouble()).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                val confidence = (0.45 + regularity * 0.30 + coverage * 0.25).coerceIn(0.0, 0.99)
                return GenericTableDetection(tableRect, rowLines, columnLines, scoreColumns, cells, confidence, "OPENCV_GRID")
            } finally {
                analysis.release()
            }
        } finally {
            rgba.release()
            gray.release()
            binary.release()
            horizontal.release()
            vertical.release()
            combined.release()
            connected.release()
            kernel.release()
        }
    }

    private fun lineCenters(mask: Mat, horizontal: Boolean): List<Int> {
        val limit = if (horizontal) mask.cols() * 0.20 else mask.rows() * 0.16
        val active = mutableListOf<Int>()
        val length = if (horizontal) mask.rows() else mask.cols()
        for (index in 0 until length) {
            val rowOrColumn = if (horizontal) mask.row(index) else mask.col(index)
            try {
                if (Core.countNonZero(rowOrColumn) >= limit) active += index
            } finally {
                rowOrColumn.release()
            }
        }
        if (active.isEmpty()) return emptyList()
        val centers = mutableListOf<Int>()
        var start = active.first()
        var previous = start
        active.drop(1).forEach { value ->
            if (value - previous > 2) {
                centers += ((start + previous) / 2)
                start = value
            }
            previous = value
        }
        centers += ((start + previous) / 2)
        return centers
    }

    private fun findTableRect(mask: Mat, rows: List<Int>, columns: List<Int>, width: Int, height: Int): org.opencv.core.Rect? {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            return contours.mapNotNull { contour ->
                try {
                    val rect = Imgproc.boundingRect(contour)
                    if (rect.width < width * 0.25 || rect.height < height * 0.12) return@mapNotNull null
                    val rowCount = rows.count { it in rect.y..(rect.y + rect.height) }
                    val columnCount = columns.count { it in rect.x..(rect.x + rect.width) }
                    if (rowCount < 3 || columnCount < 3) return@mapNotNull null
                    val areaScore = (rect.width.toDouble() * rect.height / (width.toDouble() * height)).coerceIn(0.0, 1.0)
                    Triple(rect, rowCount + columnCount, areaScore)
                } finally {
                    contour.release()
                }
            }.maxWithOrNull(compareBy<Triple<org.opencv.core.Rect, Int, Double>> { it.second }.thenBy { it.third })?.first
        } finally {
            hierarchy.release()
        }
    }

    private fun fallbackRect(rows: List<Int>, columns: List<Int>, width: Int, height: Int): org.opencv.core.Rect? {
        if (rows.size < 3 || columns.size < 3) return null
        val top = rows.first().coerceAtLeast(0)
        val bottom = rows.last().coerceAtMost(height - 1)
        val left = columns.first().coerceAtLeast(0)
        val right = columns.last().coerceAtMost(width - 1)
        return if (right - left >= width * 0.25 && bottom - top >= height * 0.12) {
            org.opencv.core.Rect(left, top, right - left, bottom - top)
        } else null
    }

    private fun boundedLines(lines: List<Int>, start: Int, end: Int, span: Int, horizontal: Boolean): List<Int> {
        val selected = lines.filter { it in start..end }.toMutableList()
        if (selected.firstOrNull() != start) selected.add(0, start)
        if (selected.lastOrNull() != end) selected += end
        return selected.distinct().sorted().filterIndexed { index, value ->
            index == 0 || index == selected.lastIndex || value - selected[index - 1] >= max(3, span / if (horizontal) 120 else 180)
        }
    }

    private fun chooseScoreColumns(lines: List<Int>): List<Int> {
        val count = lines.size - 1
        if (count <= 0) return emptyList()
        val widths = (0 until count).map { lines[it + 1] - lines[it] }
        val median = widths.sorted()[widths.size / 2].coerceAtLeast(1)
        val likely = widths.mapIndexedNotNull { index, width -> index.takeIf { width <= median * 1.8 } }
        val contiguousSuffix = likely.filter { it >= max(0, count - 6) }
        val selected = if (contiguousSuffix.isNotEmpty()) contiguousSuffix else (max(0, count - 4) until count).toList()
        return selected.takeLast(4)
    }

    private fun spacingRegularity(lines: List<Int>): Double {
        val gaps = lines.zipWithNext().map { (a, b) -> (b - a).toDouble() }.filter { it > 0 }
        if (gaps.size < 2) return 0.0
        val mean = gaps.average().coerceAtLeast(1.0)
        return (1.0 - gaps.map { kotlin.math.abs(it - mean) / mean }.average()).coerceIn(0.0, 1.0)
    }

    private fun cellConfidence(cell: Rect, table: Rect): Double {
        val area = cell.width().toDouble() * cell.height().toDouble()
        val tableArea = table.width().toDouble() * table.height().toDouble()
        return (0.60 + (area / tableArea.coerceAtLeast(1.0)).coerceAtMost(0.10)).coerceIn(0.0, 0.90)
    }
}
