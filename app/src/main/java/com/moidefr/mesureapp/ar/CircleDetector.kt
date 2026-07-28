package com.moidefr.mesureapp.ar

import android.graphics.PointF
import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A detected object, in the camera image's own pixel coordinates. */
data class DetectedCircle(val centerX: Float, val centerY: Float, val radius: Float)

private const val MIN_CIRCULARITY = 0.6

/**
 * Finds round objects in a region of interest around ([roiCenterX], [roiCenterY]) — in the camera
 * image's own pixel coordinates, not screen coordinates.
 *
 * This used to run a Hough Circle Transform directly on the ROI. Tested empirically against real
 * outdoor photos (a pétanque board on grass/dead leaves, and separately a board with heavy router/
 * saw-mark wood grain): Hough is unusably noisy on both — hundreds of false circles from leaf
 * texture on the first, and on the second it fixated entirely on the wood-grain arcs and missed
 * the real puck outright, regardless of blur/threshold tuning.
 *
 * What actually works on both test photos: Otsu-threshold the ROI in both directions (the object
 * can be darker OR lighter than its surroundings — a black puck and a light board need opposite
 * thresholds), clean up the small texture-noise blobs with a morphological open/close pass, then
 * keep only the resulting contours whose shape is actually circular
 * (`4π·area / perimeter²`, close to 1.0 for a real circle, low for the ragged blobs texture noise
 * produces). This throws away the many small, ragged wood-grain/leaf-vein blobs that a plain
 * threshold also picks up, while reliably keeping the one clean, round, puck-shaped blob.
 *
 * Returns every candidate that passes, not just the best match — a debug view can show them all;
 * [detectObjectCenter] below picks the one closest to the tap for actual use.
 *
 * [minRadius]/[maxRadius] (pixels) should bracket the real object's actual apparent size at the
 * tap's estimated depth (see ArTapPipeline's physically-grounded sizing) — a size-agnostic bound
 * like a fraction of [roiSize] let large, low-detail regions (a shadow edge, a plank-seam split)
 * pass the circularity filter by accident when the ROI happened to be sized generously.
 */
fun detectCirclesInRoi(
    image: Image,
    roiCenterX: Float,
    roiCenterY: Float,
    roiSize: Int,
    minRadius: Int,
    maxRadius: Int,
): List<DetectedCircle> {
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
    Imgproc.GaussianBlur(roiMat, blurred, Size(5.0, 5.0), 0.0)

    val minArea = Math.PI * minRadius * minRadius
    val maxArea = Math.PI * maxRadius * maxRadius

    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
    val result = mutableListOf<DetectedCircle>()

    for (baseThresholdType in intArrayOf(Imgproc.THRESH_BINARY_INV, Imgproc.THRESH_BINARY)) {
        val mask = Mat()
        Imgproc.threshold(blurred, mask, 0.0, 255.0, baseThresholdType + Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 2)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area in minArea..maxArea) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val perimeter = Imgproc.arcLength(contour2f, true)
                if (perimeter > 0.0) {
                    val circularity = 4.0 * Math.PI * area / (perimeter * perimeter)
                    if (circularity >= MIN_CIRCULARITY) {
                        val center = Point()
                        val radius = FloatArray(1)
                        Imgproc.minEnclosingCircle(contour2f, center, radius)
                        result += DetectedCircle(
                            centerX = (roiX + center.x).toFloat(),
                            centerY = (roiY + center.y).toFloat(),
                            radius = radius[0],
                        )
                    }
                }
                contour2f.release()
            }
            contour.release()
        }
        hierarchy.release()
        mask.release()
    }

    kernel.release()
    fullMat.release()
    imageMat.release()
    roiMat.release()
    blurred.release()

    return result
}

/**
 * Among the objects found in a ROI around ([tapX], [tapY]), returns the center of the one closest
 * to the tap — an approximation of which real object was tapped. Returns null if none is found.
 */
fun detectObjectCenter(
    image: Image,
    tapX: Float,
    tapY: Float,
    roiSize: Int,
    minRadius: Int,
    maxRadius: Int,
): PointF? {
    val closest = detectCirclesInRoi(image, tapX, tapY, roiSize, minRadius, maxRadius)
        .minByOrNull { hypot((it.centerX - tapX).toDouble(), (it.centerY - tapY).toDouble()) }
        ?: return null
    return PointF(closest.centerX, closest.centerY)
}
