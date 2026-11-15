package nl.bromnav

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** On-device OCR (ML Kit, bundled Latin model) → [ReceiptParser]. */
object ReceiptOcr {
    val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun toLines(t: Text): List<ReceiptParser.Line> = t.textBlocks.flatMap { b ->
        b.lines.map { l -> val r = l.boundingBox; ReceiptParser.Line(l.text, r?.left ?: 0, r?.top ?: 0, r?.right ?: 0, r?.bottom ?: 0) }
    }

    /**
     * Blocking: tries the 4 rotations (receipts are often photographed sideways/upside down)
     * and returns the first that parses, with the raw lines of the best attempt.
     */
    data class Result(val receipt: ReceiptParser.Receipt?, val lines: List<ReceiptParser.Line>, val rotation: Int)

    /**
     * Only a rotation in which the text lines are horizontal is trusted: ML Kit also reads
     * sideways text, but then "above/below" is meaningless and the wrong line becomes the street.
     */
    fun parseBitmap(bmp: Bitmap): Result {
        var best = Result(null, emptyList(), 0)
        for (deg in intArrayOf(0, 90, 270, 180)) {
            val img = if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
            val lines = toLines(Tasks.await(recognizer.process(InputImage.fromBitmap(img, 0))))
            if (!ReceiptParser.isHorizontal(lines)) continue
            if (lines.size > best.lines.size) best = Result(null, lines, deg)
            ReceiptParser.parse(lines)?.let { return Result(it, lines, deg) }
        }
        return best
    }
}
