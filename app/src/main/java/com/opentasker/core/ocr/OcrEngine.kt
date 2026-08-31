package com.opentasker.core.ocr

import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.os.SystemClock
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PP-OCRv5 on the device: detect text lines, then recognise each one.
 *
 * Split into [detect] and [recognise] on purpose. Detection is script-independent and is by far the
 * more expensive half of a re-run, so switching the script chip in the review window re-runs only the
 * recogniser over crops that are already in hand — a chip tap is close to instant.
 */
object OcrEngine {

    /** Lines per recognition batch. Larger batches pad more width for no gain. */
    private const val BATCH = 6

    /** Hard cap on a padded batch's width, so one freakishly long line cannot allocate unboundedly. */
    private const val MAX_BATCH_WIDTH = 2400

    /** A detected page: the source image plus every text line lifted out of it, ready to recognise. */
    class Page(
        val image: OcrImage,
        val boxes: List<DbPostProcess.Box>,
        val lines: List<OcrImage.Line>,
    ) {
        /** A majority of vertical strips means the page is set vertically, so columns read right-to-left. */
        val vertical: Boolean = lines.count { it.wasVertical } * 2 > lines.size
    }

    /** Runs the shared detector and lifts out every line. Script-independent. */
    suspend fun detect(
        context: Context,
        image: OcrImage,
        tuning: OcrTuning = OcrTuning.DEFAULT,
    ): Page = withContext(Dispatchers.Default) {
        val application = context.applicationContext
        val session = OcrModels.detection(application)
        val (width, height) = image.detectionSize(tuning.longSide)

        val tensor = OnnxTensor.createTensor(
            OcrModels.environment,
            FloatBuffer.wrap(image.toDetectionTensor(width, height)),
            longArrayOf(1, 3, height.toLong(), width.toLong()),
        )
        val (probability, mapWidth, mapHeight) = tensor.use {
            session.run(mapOf(session.inputNames.first() to it)).use { results ->
                val output = results[0] as OnnxTensor
                val shape = output.info.shape
                val buffer = output.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                Triple(values, shape[shape.size - 1].toInt(), shape[shape.size - 2].toInt())
            }
        }

        val boxes = DbPostProcess.boxes(
            probability = probability,
            width = mapWidth,
            height = mapHeight,
            // The detector's output map can differ in size from its input, so the mapping back to the
            // original image is derived from the map itself rather than assumed to be the input scale.
            scaleX = image.width.toFloat() / mapWidth,
            scaleY = image.height.toFloat() / mapHeight,
            originalWidth = image.width,
            originalHeight = image.height,
            binaryThreshold = tuning.binaryThreshold,
            boxScoreThreshold = tuning.boxScoreThreshold,
            unclipRatio = tuning.unclipRatio,
        )
        Page(image, boxes, boxes.map { image.cropQuad(it.quad) })
    }

    /** Recognises an already-detected [page] under one script. */
    suspend fun recognise(
        context: Context,
        page: Page,
        script: OcrScript = OcrScript.DEFAULT,
        highAccuracy: Boolean = true,
        /**
         * Called after each batch, with how many are done and how many there are.
         *
         * Only 「記事変換」 passes one: recognition is the longest step of a slice it can subdivide,
         * and without it the top progress bar stands still for seconds at a time.
         */
        onBatch: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OcrResult = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        if (page.lines.isEmpty()) return@withContext OcrResult.empty(script, 0L)

        val application = context.applicationContext
        val session = OcrModels.recognition(application, script, highAccuracy)
        val charset = OcrModels.charset(application, script, highAccuracy)
        val inputName = session.inputNames.first()

        // Sort by aspect ratio so each batch pads to a width its members actually need. Without this a
        // short label batched with a full-width sentence pads to the sentence's width for nothing.
        val order = page.lines.indices.sortedBy { page.lines[it].image.run { width.toFloat() / height } }
        val decoded = arrayOfNulls<CtcDecoder.Decoded>(page.lines.size)

        val batches = (order.size + BATCH - 1) / BATCH
        var batch = 0
        for (start in order.indices step BATCH) {
            val chunk = order.subList(start, min(start + BATCH, order.size))
            val widest = chunk.maxOf { page.lines[it].image.run { width.toFloat() / height } }
            val batchWidth = ceil(OcrImage.RECOGNITION_HEIGHT * widest).toInt()
                .coerceIn(OcrImage.RECOGNITION_HEIGHT, MAX_BATCH_WIDTH)

            val data = FloatArray(chunk.size * 3 * OcrImage.RECOGNITION_HEIGHT * batchWidth)
            chunk.forEachIndexed { row, index ->
                page.lines[index].image.writeRecognitionRow(data, row, batchWidth)
            }

            val tensor = OnnxTensor.createTensor(
                OcrModels.environment,
                FloatBuffer.wrap(data),
                longArrayOf(
                    chunk.size.toLong(), 3,
                    OcrImage.RECOGNITION_HEIGHT.toLong(), batchWidth.toLong(),
                ),
            )
            tensor.use {
                session.run(mapOf(inputName to it)).use { results ->
                    val output = results[0] as OnnxTensor
                    val shape = output.info.shape
                    val timesteps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val buffer = output.floatBuffer
                    val row = FloatArray(timesteps * classes)
                    chunk.forEachIndexed { rowIndex, index ->
                        buffer.position(rowIndex * timesteps * classes)
                        buffer.get(row)
                        decoded[index] = CtcDecoder.decode(row, timesteps, classes, charset)
                    }
                }
            }
            onBatch(++batch, batches)
        }

        val candidates = page.boxes.indices.mapNotNull { index ->
            val line = decoded[index] ?: return@mapNotNull null
            if (line.text.isBlank()) null
            else ReadingOrder.Candidate(
                line.text, line.confidence, line.lowestCharacter, page.boxes[index].quad,
            )
        }
        val (blocks, text) = ReadingOrder.assemble(candidates, page.vertical)
        OcrResult(blocks, text, script, SystemClock.elapsedRealtime() - started)
    }

    /** Detect and recognise in one call — for the `ocr.recognize` action, which has no window to keep warm. */
    suspend fun run(
        context: Context,
        image: OcrImage,
        script: OcrScript = OcrScript.DEFAULT,
        highAccuracy: Boolean = true,
        tuning: OcrTuning = OcrTuning.DEFAULT,
    ): OcrResult {
        val started = SystemClock.elapsedRealtime()
        val page = detect(context, image, tuning)
        val result = recognise(context, page, script, highAccuracy)
        return result.copy(elapsedMs = max(result.elapsedMs, SystemClock.elapsedRealtime() - started))
    }
}
