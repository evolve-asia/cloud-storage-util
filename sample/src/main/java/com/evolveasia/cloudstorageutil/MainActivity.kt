package com.evolveasia.cloudstorageutil

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.widget.ProgressBar
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.evolve.cameralib.EvolveImagePicker
import com.evolveasia.aws.AWSUtils
import com.evolveasia.aws.AwsMetaInfo
import java.net.URISyntaxException

open class MainActivity : AppCompatActivity(), AWSUtils.OnAwsImageUploadListener{

    private var progressBar: ProgressBar? = null

    companion object {
        private const val COGNITO_IDENTITY_ID: String =
            "YOUR_COGNITO_IDENTITY_ID"
        private const val BUCKET_NAME: String = "YOUR_BUCKET_NAME"
        private const val COGNITO_REGION: String = "YOUR_COGNITO_REGION"
        private const val S3_URL: String = "https://$BUCKET_NAME.s3.$COGNITO_REGION.amazonaws.com/"
    }

    private var imageUrlList = mutableListOf<Uri?>()

    private val evolveActivityResultLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val photoUri: Uri? = result.data?.data
            if (photoUri != null) {
                if (imageUrlList.size != 3) {
                    imageUrlList.add(photoUri)
                }
                if (imageUrlList.size == 3) {
                    imageUrlList.forEach {
                        val path: String? = getPath(it!!)
                        val awsConfig = AwsMetaInfo.AWSConfig(
                            BUCKET_NAME,
                            COGNITO_IDENTITY_ID,
                            COGNITO_REGION,
                            S3_URL
                        )
                        val gcsMetaData = AwsMetaInfo.Builder().apply {
                            serviceConfig = awsConfig
                            this.awsFolderPath = getStoragePath()
                            imageMetaInfo = AwsMetaInfo.ImageMetaInfo().apply {
                                this.imagePath = path!!
                                this.mediaType = "image/jpeg"
                                compressLevel = 80
                                compressFormat = Bitmap.CompressFormat.JPEG
                                waterMarkInfo = getWaterMarkInfo()
                            }
                        }.build()
                        val awsUtil = AWSUtils(this, this)
                        awsUtil.beginUpload(gcsMetaData) { url ->
                            println("Uri itttttttt -> $url")
                        }
                    }
                }
            }
        }

    override
    fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progressBar = findViewById(R.id.progressBar)
        findViewById<AppCompatButton>(R.id.btn_upload).setOnClickListener {
            openCamera()
        }
    }

    override fun showProgress() {
        progressBar?.visibility = View.VISIBLE
    }

    override fun onProgressChanged(id: Int, currentByte: Float, totalByte: Float) {
        /*   val value = currentByte / totalByte
           val percentage = value * 100
           println("byte percentage---->${percentage.toInt()}")*/
    }

    override fun onSuccess(imgUrl: String) {
        progressBar?.visibility = View.GONE
        println("ImageUrl-----------------------> $imgUrl  ------> ${System.currentTimeMillis()}")
    }

    override fun onError(errorMsg: String) {
        progressBar?.visibility = View.GONE
    }

    override fun onStateChanged(state: String) {

    }

    private fun openCamera() {
        EvolveImagePicker
            .with(this)
            .start(
                evolveActivityResultLauncher,
                forceImageCapture = true
            )
    }

    private fun getWaterMarkInfo(): AwsMetaInfo.WaterMarkInfo {
        val dataList = mutableListOf<Pair<String, String>>()
        dataList.add(Pair("Outlet name", "Test-krishna Store"))
        dataList.add(Pair("Location", "27.345, 85.635"))
        dataList.add(Pair("Time", "2019-12-12 13:24:54"))
        dataList.add(Pair("Verified by", "Test User"))
        return AwsMetaInfo.WaterMarkInfo(dataList)
    }

    @SuppressLint("NewApi")
    @Throws(URISyntaxException::class)
    protected fun getPath(uri: Uri): String? {
        var uri = uri
        var selection: String? = null
        var selectionArgs: Array<String>? = null

        if (DocumentsContract.isDocumentUri(applicationContext, uri)) {
            when {
                isExternalStorageDocument(uri) -> {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split =
                        docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                }
                isDownloadsDocument(uri) -> {
                    try {
                        val id = DocumentsContract.getDocumentId(uri)
                        uri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"),
                            java.lang.Long.valueOf(id)
                        )
                    } catch (e: NumberFormatException) {
                        return null
                    }
                }
                isMediaDocument(uri) -> {
                    val docId = DocumentsContract.getDocumentId(uri)
                    val split =
                        docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    val type = split[0]
                    when (type) {
                        "image" -> {
                            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }
                        "video" -> {
                            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        }
                        "audio" -> {
                            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        }
                    }
                    selection = "_id=?"
                    selectionArgs = arrayOf(split[1])
                }
            }
        }
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            var cursor: Cursor? = null
            try {
                cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
                val columnIndex = cursor!!.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                if (cursor.moveToFirst()) {
                    return cursor.getString(columnIndex)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    private fun getStoragePath(): String {
        return "test/outlet_profile"
    }
}