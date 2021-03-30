package com.getbouncer.scan.payment.carddetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import com.getbouncer.scan.framework.FetchedData
import com.getbouncer.scan.framework.TrackedImage
import com.getbouncer.scan.framework.UpdatingResourceFetcher
import com.getbouncer.scan.framework.ml.TFLAnalyzerFactory
import com.getbouncer.scan.framework.ml.TensorFlowLiteAnalyzer
import com.getbouncer.scan.framework.util.indexOfMax
import com.getbouncer.scan.framework.util.maxAspectRatioInSize
import com.getbouncer.scan.framework.util.scaleAndCenterWithin
import com.getbouncer.scan.payment.crop
import com.getbouncer.scan.payment.hasOpenGl31
import com.getbouncer.scan.payment.scale
import com.getbouncer.scan.payment.size
import com.getbouncer.scan.payment.toRGBByteBuffer
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val TRAINED_IMAGE_SIZE = Size(224, 224)

/** model returns whether or not there is a card present */
private const val NUM_CLASS = 3

class CardDetect private constructor(interpreter: Interpreter) :
    TensorFlowLiteAnalyzer<CardDetect.Input, ByteBuffer, CardDetect.Prediction, Array<FloatArray>>(interpreter) {

    companion object {
        /**
         * Given a card finder region of a preview image, calculate the associated card detection
         * square.
         */
        private fun calculateCardDetectionFromCardFinder(previewImage: Size, cardFinder: Rect): Rect {
            val cardDetectionSquareSize = maxAspectRatioInSize(previewImage, 1F)
            return Rect(
                /* left */
                max(0, cardFinder.centerX() - cardDetectionSquareSize.width / 2),
                /* top */
                max(0, cardFinder.centerY() - cardDetectionSquareSize.height / 2),
                /* right */
                min(previewImage.width, cardFinder.centerX() + cardDetectionSquareSize.width / 2),
                /* bottom */
                min(previewImage.height, cardFinder.centerY() + cardDetectionSquareSize.height / 2)
            )
        }

        /**
         * Calculate what portion of the full image should be cropped for card detection based on
         * the position of card finder within the preview image.
         */
        private fun calculateCrop(fullImage: Size, previewImage: Size, cardFinder: Rect): Rect {
            require(
                cardFinder.left >= 0 &&
                    cardFinder.right <= previewImage.width &&
                    cardFinder.top >= 0 &&
                    cardFinder.bottom <= previewImage.height
            ) { "Card finder is outside preview image bounds" }

            // Calculate the card detection square based on the card finder, limited by the preview
            val cardDetectionSquare =
                calculateCardDetectionFromCardFinder(
                    previewImage,
                    cardFinder
                )

            val scaledPreviewImage = previewImage.scaleAndCenterWithin(fullImage)
            val previewScale = scaledPreviewImage.width().toFloat() / previewImage.width

            // Scale the cardDetectionSquare to match the scaledPreviewImage
            val scaledCardDetectionSquare = Rect(
                (cardDetectionSquare.left * previewScale).roundToInt(),
                (cardDetectionSquare.top * previewScale).roundToInt(),
                (cardDetectionSquare.right * previewScale).roundToInt(),
                (cardDetectionSquare.bottom * previewScale).roundToInt()
            )

            // Position the scaledCardDetectionSquare on the fullImage
            return Rect(
                max(0, scaledCardDetectionSquare.left + scaledPreviewImage.left),
                max(0, scaledCardDetectionSquare.top + scaledPreviewImage.top),
                min(fullImage.width, scaledCardDetectionSquare.right + scaledPreviewImage.left),
                min(fullImage.height, scaledCardDetectionSquare.bottom + scaledPreviewImage.top),
            )
        }

        /**
         * Convert a camera preview image into a CardDetect input
         */
        fun cameraPreviewToInput(
            cameraPreviewImage: TrackedImage<Bitmap>,
            previewSize: Size,
            cardFinder: Rect
        ) = Input(
            TrackedImage(
                cameraPreviewImage.image
                    .crop(
                        calculateCrop(
                            cameraPreviewImage.image.size(),
                            previewSize,
                            cardFinder,
                        )
                    )
                    .scale(TRAINED_IMAGE_SIZE)
                    .toRGBByteBuffer()
                    .also { cameraPreviewImage.tracker.trackResult("card_detect_image_cropped") },
                cameraPreviewImage.tracker,
            )
        )
    }

    data class Input(val cardDetectImage: TrackedImage<ByteBuffer>)

    /**
     * A prediction returned by this analyzer.
     */
    data class Prediction(
        val side: Side,
        val noCardProbability: Float,
        val noPanProbability: Float,
        val panProbability: Float,
    ) {
        val maxConfidence = max(max(noCardProbability, noPanProbability), panProbability)

        /**
         * Force a generic toString method to prevent leaking information about this class' parameters after R8. Without
         * this method, this `data class` will automatically generate a toString which retains the original names of the
         * parameters even after obfuscation.
         */
        override fun toString(): String {
            return "Prediction"
        }

        enum class Side {
            NO_CARD,
            NO_PAN,
            PAN,
        }
    }

    override suspend fun interpretMLOutput(data: Input, mlOutput: Array<FloatArray>): Prediction {
        val side = when (val index = mlOutput[0].indexOfMax()) {
            0 -> Prediction.Side.NO_PAN
            1 -> Prediction.Side.NO_CARD
            2 -> Prediction.Side.PAN
            else -> throw EnumConstantNotPresentException(
                Prediction.Side::class.java,
                index.toString(),
            )
        }

        data.cardDetectImage.tracker.trackResult("card_detect_prediction_complete")

        return Prediction(
            side = side,
            noPanProbability = mlOutput[0][0],
            noCardProbability = mlOutput[0][1],
            panProbability = mlOutput[0][2],
        )
    }

    override suspend fun transformData(data: Input): ByteBuffer = data.cardDetectImage.image

    override suspend fun executeInference(
        tfInterpreter: Interpreter,
        data: ByteBuffer,
    ): Array<FloatArray> {
        val mlOutput = arrayOf(FloatArray(NUM_CLASS))
        tfInterpreter.run(data, mlOutput)
        return mlOutput
    }

    /**
     * A factory for creating instances of this analyzer.
     */
    class Factory(
        context: Context,
        fetchedModel: FetchedData,
        threads: Int = DEFAULT_THREADS,
    ) : TFLAnalyzerFactory<Input, Prediction, CardDetect>(context, fetchedModel) {
        companion object {
            private const val USE_GPU = false
            private const val DEFAULT_THREADS = 4
        }

        override val tfOptions: Interpreter.Options = Interpreter
            .Options()
            .setUseNNAPI(USE_GPU && hasOpenGl31(context))
            .setNumThreads(threads)

        override suspend fun newInstance(): CardDetect? = createInterpreter()?.let { CardDetect(it) }
    }

    /**
     * A fetcher for downloading model data.
     */
    class ModelFetcher(context: Context) : UpdatingResourceFetcher(context) {
        override val assetFileName: String = "UX.0.25.106.8.tflite"
        override val resourceModelVersion: String = "0.25.106.8"
        override val resourceModelHash: String = "c2a39c9034a9f0073933488021676c46910cec0d1bf330ac22a908dcd7dd448a"
        override val resourceModelHashAlgorithm: String = "SHA-256"
        override val modelClass: String = "card_detection"
        override val modelFrameworkVersion: Int = 1
    }
}