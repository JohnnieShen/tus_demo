package com.jshen52.tus_demo

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.tus.android.client.TusPreferencesURLStore
import io.tus.java.client.ProtocolException
import io.tus.java.client.TusClient
import io.tus.java.client.TusUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL

class TusUploadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_URI = "uri"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_CONVERT_TO_PNG = "convert_to_png"

        const val KEY_UPLOAD_URL = "upload_url"

        const val KEY_PROGRESS_UPLOADED = "progress_uploaded"
        const val KEY_PROGRESS_TOTAL = "progress_total"

        const val CHUNK_SIZE = 16
        const val REQUEST_PAYLOAD_SIZE = 512
        const val FINISH_RETRY_DELAY = 1000L
    }

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI) ?: return Result.failure()
        val endpoint = inputData.getString(KEY_ENDPOINT) ?: return Result.failure()
        val convertPng = inputData.getBoolean(KEY_CONVERT_TO_PNG, false)
        val imageUri = uriString.toUri()

        return try {
            val (fileToUpload, contentType) = prepareFile(appContext.contentResolver, imageUri, convertPng)

            val client = TusClient().apply {
                setUploadCreationURL(URL(endpoint))
                enableResuming(
                    TusPreferencesURLStore(appContext.getSharedPreferences("tus_worker", Context.MODE_PRIVATE))
                )
                setHeaders(mapOf("Content-Type" to "application/offset+octet-stream"))
            }

            val uniqueFingerprint = inputData.getString(KEY_FINGERPRINT)
                ?: (fileToUpload.absolutePath + "#" + uriString)

            val upload = TusUpload(fileToUpload).apply {
                fingerprint = uniqueFingerprint
                setMetadata(mapOf(
                    "filename"    to fileToUpload.name,
                    "contentType" to contentType
                ))
            }

            val uploader = client.resumeOrCreateUpload(upload).apply {
                setChunkSize(CHUNK_SIZE * 1024)
                setRequestPayloadSize(REQUEST_PAYLOAD_SIZE * 1024)
            }

            setProgress(workDataOf(
                KEY_PROGRESS_UPLOADED to uploader.offset,
                KEY_PROGRESS_TOTAL to upload.size
            ))

            while (uploader.uploadChunk() > -1) {
                setProgress(workDataOf(
                    KEY_PROGRESS_UPLOADED to uploader.offset,
                    KEY_PROGRESS_TOTAL to upload.size
                ))
            }
            val maxFinishAttempts = 5
            for (attempt in 1..maxFinishAttempts) {
                try {
                    uploader.finish()
                    val uploadUrl = uploader.uploadURL.toString()
                    Log.d("TusUploadWorker", "Upload finished successfully. URL: $uploadUrl")
                    val outputData = workDataOf(KEY_UPLOAD_URL to uploadUrl)
                    return Result.success(outputData)
                } catch (e: IOException) {
                    Log.w("TusUploadWorker", "Failed to finish upload, attempt $attempt/$maxFinishAttempts", e)
                    if (attempt == maxFinishAttempts) {
                        throw e
                    }
                    delay(FINISH_RETRY_DELAY * attempt * attempt)
                }
            }
            return Result.retry()
        } catch (e: ProtocolException) {
            Log.e("TusUploadWorker", "Permanent upload failure", e)
            Result.failure()
        } catch (e: IOException) {
            Log.w("TusUploadWorker", "Transient upload failure, will retry.", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e("TusUploadWorker", "Unexpected error during upload", e)
            Result.failure()
        }
    }

    private suspend fun prepareFile(
        resolver: ContentResolver,
        uri: Uri,
        convertToPng: Boolean
    ): Pair<File, String> = withContext(Dispatchers.IO) {
        val originalType = resolver.getType(uri) ?: "application/octet-stream"
        val ext = when (originalType) {
            "image/png"  -> "png"
            "image/jpeg" -> "jpg"
            else -> uri.lastPathSegment?.substringAfterLast('.') ?: ""
        }
        Log.d("tus demo", "convert: $convertToPng")
        return@withContext if (convertToPng) {
            val bmp = resolver.openInputStream(uri)!!.use(BitmapFactory::decodeStream)
            val outFile = File(appContext.cacheDir, "upload_${System.currentTimeMillis()}.png")
            FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            outFile to "image/png"
        } else {
            val inStream = resolver.openInputStream(uri)
                ?: throw IOException("Cannot open URI: $uri")
            val outFile = File(appContext.cacheDir, "upload_${System.currentTimeMillis()}.$ext")
            FileOutputStream(outFile).use { sink ->
                inStream.copyTo(sink)
            }
            inStream.close()
            outFile to originalType
        }
    }
}