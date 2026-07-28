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

/**
 * Runs a Hough Circle Transform on a region of interest around ([tapX], [tapY]) — in the camera
 * image's own pixel coordinates, not screen coordinates — and returns the lowest point of the
 * circle that best matches the tapped object: an approximation of where it touches the ground.
 *
 * Returns null if no circle is found in the ROI.
 */
fun detectGroundContactPoint(
    image: Image,
    tapX: Float,
    tapY: Float,
    roiSize: Int = 400,
): PointF? {
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
    val roiX = (tapX.roundToInt() - half).coerceIn(0, max(0, width - 1))
    val roiY = (tapY.roundToInt() - half).coerceIn(0, max(0, height - 1))
    val roiWidth = min(roiSize, width - roiX)
    val roiHeight = min(roiSize, height - roiY)
    if (roiWidth <= 0 || roiHeight <= 0) {
        fullMat.release()
        return null
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
        roiSize / 10,
        roiSize / 2,
    )

    // The tap is the best guess of where the object is; among the circles Hough found in the
    // ROI, keep the one whose center falls closest to the tap.
    val tapInRoiX = tapX - roiX
    val tapInRoiY = tapY - roiY
    var best: DoubleArray? = null
    var bestDistance = Double.MAX_VALUE
    for (i in 0 until circles.cols()) {
        val circle = circles.get(0, i)
        val distance = hypot(circle[0] - tapInRoiX, circle[1] - tapInRoiY)
        if (distance < bestDistance) {
            bestDistance = distance
            best = circle
        }
    }

    fullMat.release()
    roiMat.release()
    blurred.release()
    circles.release()

    val chosen = best ?: return null
    val centerX = roiX + chosen[0]
    val centerY = roiY + chosen[1]
    val radius = chosen[2]
    return PointF(centerX.toFloat(), (centerY + radius).toFloat())
}
