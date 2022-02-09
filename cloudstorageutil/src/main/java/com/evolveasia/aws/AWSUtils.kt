package com.evolveasia.aws

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.text.TextUtils
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtilityOptions
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import java.io.*

class AWSUtils(
    private val context: Context,
    val onAwsImageUploadListener: OnAwsImageUploadListener,
) {
    private var imageFile: File? = null
    private var mTransferUtility: TransferUtility? = null
    private var sS3Client: AmazonS3Client? = null
    private var sCredProvider: CognitoCachingCredentialsProvider? = null
    private lateinit var awsMetaInfo: AwsMetaInfo

    private fun getCredProvider(context: Context): CognitoCachingCredentialsProvider? {
        if (sCredProvider == null) {
            sCredProvider = CognitoCachingCredentialsProvider(
                context.applicationContext,
                awsMetaInfo.serviceConfig.cognitoPoolId,
                getRegions(awsMetaInfo)
            )
        }
        return sCredProvider
    }

    private fun getS3Client(context: Context?): AmazonS3Client? {
        val configuration = ClientConfiguration()
        configuration.maxErrorRetry = 3
        configuration.connectionTimeout = 60* 1000
        configuration.socketTimeout = 60 * 1000
        configuration.protocol = Protocol.HTTP
        configuration.maxConnections = 10
        if (sS3Client == null) {
            sS3Client =
                AmazonS3Client(
                    getCredProvider(context!!),
                    Region.getRegion(awsMetaInfo.serviceConfig.region),
                    configuration
                )
        }
        return sS3Client
    }

    private fun getTransferUtility(context: Context): TransferUtility? {
        if (mTransferUtility == null) {
            val tuOptions = TransferUtilityOptions()
            tuOptions.transferThreadPoolSize = 1 // 10 threads for upload and download operations.

            // Initializes TransferUtility
            mTransferUtility = TransferUtility
                .builder()
                .s3Client(getS3Client(context.applicationContext))
                .context(context.applicationContext)
                .transferUtilityOptions(tuOptions)
                .build()
        }
        return mTransferUtility
    }

    fun beginUpload(awsMetaInfo: AwsMetaInfo, onSuccess: (String) -> Unit) {
        this.awsMetaInfo = awsMetaInfo
        if (TextUtils.isEmpty(awsMetaInfo.imageMetaInfo.imagePath)) {
            onAwsImageUploadListener.onError("Could not find the filepath of the selected file")
            return
        }

        val oldExif = ExifInterface(awsMetaInfo.imageMetaInfo.imagePath)
        val compressedImagePath = compressAwsImage(awsMetaInfo).first
        val compressedBitmap = compressAwsImage(awsMetaInfo).second
        val newExifOrientation = setImageOrientation(oldExif, compressedImagePath)

        if (newExifOrientation != null) {
            try {
                val rotation = getRotation(newExifOrientation)
                if (rotation != null) {
                    val matrix = Matrix()
                    matrix.postRotate(rotation)
                    setPostScale(newExifOrientation, matrix)
                    if (compressedBitmap != null) {
                        val rotatedBitmap = Bitmap.createBitmap(
                            compressedBitmap,
                            0,
                            0,
                            compressedBitmap.width,
                            compressedBitmap.height,
                            matrix,
                            true
                        )
                        if (rotatedBitmap != null) {
                            // rotatedBitmap will be recycled inside addAwsWaterMark function
                            val waterMarkBitmap = addAwsWaterMark(awsMetaInfo, rotatedBitmap)
                            waterMarkBitmap.recycle()
                        }
                        compressedBitmap.recycle()
                    }
                } else {
                    if (compressedBitmap != null) {
                        val newBitmap = Bitmap.createBitmap(
                            compressedBitmap,
                            0,
                            0,
                            compressedBitmap.width,
                            compressedBitmap.height
                        )
                        if (newBitmap != null) {
                            // newBitmap will be recycled inside addAwsWaterMark function
                            val waterMarkBitmap = addAwsWaterMark(awsMetaInfo, newBitmap)
                            waterMarkBitmap.recycle()
                        }
                        compressedBitmap.recycle()
                    }
                }
            } catch (error: Exception) {
                error.printStackTrace()
                awsMetaInfo.imageMetaInfo.imagePath = compressedImagePath
            }
        }
        /*  if (compressedBitmap != null) {
              val newBitmap = Bitmap.createBitmap(
                  compressedBitmap,
                  0,
                  0,
                  compressedBitmap.width,
                  compressedBitmap.height
              )
              if (newBitmap != null) {
                  // newBitmap will be recycled inside addAwsWaterMark function
                  val waterMarkBitmap = addAwsWaterMark(awsMetaInfo, newBitmap)
                  waterMarkBitmap.recycle()
              }
              compressedBitmap.recycle()
          }*/

        val file = File(awsMetaInfo.imageMetaInfo.imagePath)
        imageFile = file
        onAwsImageUploadListener.showProgress()

        val observer = getTransferUtility(context)?.upload(
            awsMetaInfo.serviceConfig.bucketName, //Bucket name
            "${awsMetaInfo.awsFolderPath}/${imageFile?.name}", imageFile
        )
        observer?.setTransferListener(UploadListener(onSuccess))
    }

    private inner class UploadListener(private val onSuccess: (String) -> Unit) : TransferListener {
        override fun onError(id: Int, e: Exception) {
            onAwsImageUploadListener.onError(e.message.toString())
        }

        override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
            onAwsImageUploadListener.onProgressChanged(
                id,
                bytesCurrent.toFloat(),
                bytesTotal.toFloat()
            )
        }

        override fun onStateChanged(id: Int, newState: TransferState) {
            if (newState == TransferState.COMPLETED) {
                val finalImageUrl =
                    "${awsMetaInfo.serviceConfig.url}${awsMetaInfo.awsFolderPath}/${imageFile?.name}"
                onAwsImageUploadListener.onSuccess(finalImageUrl)
                onSuccess(finalImageUrl)
            } else if (newState == TransferState.CANCELED || newState == TransferState.FAILED) {
                onAwsImageUploadListener.onStateChanged(getState(newState))
            }
        }
    }

    private fun compressAwsImage(awsMetaInfo: AwsMetaInfo): Pair<String, Bitmap?> {
        return try {
            val byteArray = streamToByteArray(FileInputStream(awsMetaInfo.imageMetaInfo.imagePath))
            val bitmap = decodeSampledBitmapFromResource(
                byteArray, awsMetaInfo.imageMetaInfo.imageWidth
                    ?: AwsConstant.DEFAULT_IMAGE_WIDTH, awsMetaInfo.imageMetaInfo.imageHeight
                    ?: AwsConstant.DEFAULT_IMAGE_HEIGHT, awsMetaInfo.imageMetaInfo.waterMarkInfo
            )

            val stream = ByteArrayOutputStream()
            bitmap.compress(
                awsMetaInfo.imageMetaInfo.compressFormat,
                awsMetaInfo.imageMetaInfo.compressLevel,
                stream
            )
            val os: OutputStream = FileOutputStream(awsMetaInfo.imageMetaInfo.imagePath)
            os.write(stream.toByteArray())
            os.close()
            Pair(awsMetaInfo.imageMetaInfo.imagePath, bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(awsMetaInfo.imageMetaInfo.imagePath, null)
        }
    }

    private fun getRegions(awsMetaInfo: AwsMetaInfo): Regions {
        return when (awsMetaInfo.serviceConfig.region) {
            "ap-southeast-1" -> Regions.AP_SOUTHEAST_1
            "ap-south-1" -> Regions.AP_SOUTH_1
            "ap-east-1" -> Regions.AP_EAST_1
            else -> throw IllegalArgumentException("Invalid region : add other region if required (Cloud storage util library)")
        }
    }

    private fun getState(newState: TransferState): String {
        return when (newState) {
            TransferState.CANCELED -> AWSTransferState.STATE_CANCELED
            TransferState.COMPLETED -> AWSTransferState.STATE_COMPLETED
            TransferState.FAILED -> AWSTransferState.STATE_FAILED
            else -> AWSTransferState.STATE_UNKNOWN
        }
    }

    fun getRotation(exifOrientation: Int): Float? {
        return when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            else -> null
        }
    }

    private fun setPostScale(exifOrientation: Int, matrix: Matrix) {
        when (exifOrientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> matrix.postScale(-1f, 1f)
        }
    }

    private fun setImageOrientation(oldExif: ExifInterface, newImagePath: String): Int? {
        val exifOrientation = oldExif.getAttribute(ExifInterface.TAG_ORIENTATION)
        if (exifOrientation != null) {
            val newExif = ExifInterface(newImagePath)
            newExif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation)
            newExif.saveAttributes()
            return newExif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
        }
        return null
    }

    interface OnAwsImageUploadListener {
        fun showProgress()
        fun onProgressChanged(id: Int, currentByte: Float, totalByte: Float)
        fun onSuccess(imgUrl: String)
        fun onError(errorMsg: String)
        fun onStateChanged(state: String)
    }
}