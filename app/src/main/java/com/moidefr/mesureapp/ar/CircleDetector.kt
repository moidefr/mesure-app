package com.moidefr.mesureapp.ar

import android.graphics.PointF
import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A circle found by Hough, in the camera image's own pixel coordinates. */
data class DetectedCircle(val centerX: Float, val centerY: Float, val radius: Float)

/**
 * Runs a Hough Circle Transform on a region of interest around ([tapX], [tapY]) — in the camera
 * image's own pixel coordinates, not screen coordinates — and returns every circle Hough found in
 * that ROI (empty if none). Exposed separately from [detectObjectCenter] so a debug view can show
 * every candidate, not just the one that gets picked.
 *
 * Measured empirically against real outdoor test photos (pétanque board, dead leaves, grass):
 * a whole-frame scan is unusably noisy (hundreds of false circles from leaf/grass texture), but a
 * tight ROI like this one reliably finds real objects, provided minRadius isn't set too high —
 * `roiSize / 10` used to exclude legitimately-sized objects outright (e.g. a 32px-radius puck in a
 * 400px ROI was rejected by a 40px floor).
 */
fun detectCirclesInRoi(image: Image, roiCenterX: Float, roiCenterY: Float, roiSize: Int): List<DetectedCircle> {
    val plane = image.planes[0]
    val rowStride = plane.rowStride
    val width = image.width
    val height = image.height

    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)

    // The Y plane's row stride can exceed the image width (device-dependent row padding), so the
    // buffer is first wrapped at its real stride, then cropped down to the actual image size.
    val fullMat = Mat(height, rowStride, CvType.CV_8UC1)
    fullMat.put(0, 0, data)
    val imageMat = fullMat.submat(Rect(0, 0, width, height))

    val half = roiSize / 2
    val roiX = (roiCenterX.roundToInt() - half).coerceIn(0, max(0, width - 1))
    val roiY = (roiCenterY.roundToInt() - half).coerceIn(0, max(0, height - 1))
    val roiWidth = min(roiSize, width - roiX)
    val roiHeight = min(roiSize, height - roiY)
    if (roiWidth <= 0 || roiHeight <= 0) {
        fullMat.release()
        return emptyList()
    }

    val roiMat = imageMat.submat(Rect(roiX, roiY, roiWidth, roiHeight))
    val blurred = Mat()
    Imgproc.GaussianBlur(roiMat, blurred, Size(9.0, 9.0), 2.0)

    val circles = Mat()
    Imgproc.HoughCircles(
        blurred,
        circles,
        Imgproc.HOUGH_GRADIENT,
        1.0,
        blurred.rows() / 8.0,
        100.0,
        30.0,
        max(10, roiSize / 20),
        roiSize / 2,
    )

    val result = (0 until circles.cols()).map { i ->
        val c = circles.get(0, i)
        DetectedCircle(roiX + c[0].toFloat(), roiY + c[1].toFloat(), c[2].toFloat())
    }

    fullMat.release()
    roiMat.release()
    blurred.release()
    circles.release()

    return result
}

/**
 * Among the circles Hough finds in a ROI around ([tapX], [tapY]), returns the center of the one
 * closest to the tap — an approximation of which real object was tapped. Returns null if no
 * circle is found in the ROI.
 */
fun detectObjectCenter(image: Image, tapX: Float, tapY: Float, roiSize: Int = 400): PointF? {
    val closest = detectCirclesInRoi(image, tapX, tapY, roiSize)
        .minByOrNull { hypot((it.centerX - tapX).toDouble(), (it.centerY - tapY).toDouble()) }
        ?: return null
    return PointF(closest.centerX, closest.centerY)
}
