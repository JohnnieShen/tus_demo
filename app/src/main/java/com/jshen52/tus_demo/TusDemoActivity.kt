package com.jshen52.tus_demo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
class TusDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var endpoint by remember { mutableStateOf("https://api.vectorcam.org/specimens/1/images/tus") }
            var pickedImageUri by remember { mutableStateOf<Uri?>(null) }

            var uploading by remember { mutableStateOf(false) }
            var progress by remember { mutableStateOf(0f) }
            var statusMessage by remember { mutableStateOf("") }
            var workId by remember { mutableStateOf<UUID?>(null) }

            var testResult by remember { mutableStateOf("") }
            val pickImageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri: Uri? -> pickedImageUri = uri }

            val queueState by UploadQueueManager.queue.collectAsState()
            val queueRunning by UploadQueueManager.running.collectAsState()

            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(workId) {
                workId?.let { id ->
                    WorkManager.getInstance(applicationContext)
                        .getWorkInfoByIdLiveData(id)
                        .observe(lifecycleOwner, { workInfo ->
                            handleWorkInfo(
                                workInfo = workInfo,
                                onProgress = { p -> progress = p },
                                onStatusUpdate = { msg -> statusMessage = msg },
                                onUploadingChange = { isUploading -> uploading = isUploading }
                            )
                        })
                }
            }

            val scrollState = rememberScrollState()
            Scaffold(topBar = {
                TopAppBar(title = { Text("Tus Upload Demo") })
            }) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text("Tus endpoint URL") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { pickImageLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pick an image")
                    }

                    pickedImageUri?.let {
                        Text("Selected: ${it.lastPathSegment}", style = MaterialTheme.typography.bodyMedium)
                    }

                    if (uploading) {
                        Button(
                            onClick = {
                                workId?.let {
                                    WorkManager.getInstance(applicationContext).cancelWorkById(it)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Cancel Upload") }
                    } else {
                        Button(
                            onClick = {
                                val workRequest = createWorkRequest(pickedImageUri!!, endpoint)
                                workId = workRequest.id
                                WorkManager.getInstance(applicationContext).enqueue(workRequest)
                            },
                            enabled = !uploading && endpoint.isNotBlank() && pickedImageUri != null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Upload") }
                    }

                    if (uploading || statusMessage.startsWith("Finished")) {
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (statusMessage.isNotBlank()) {
                        Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

//                    if (testResult.isNotBlank()) {
//                        Text(
//                            text = testResult,
//                            style = MaterialTheme.typography.bodySmall,
//                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
//                        )
//                    }

                    Button(
                        onClick = {
                            pickedImageUri?.let { UploadQueueManager.enqueue(it, endpoint) }
                        },
                        enabled = pickedImageUri != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enqueue Selected Image") }

                    Button(
                        onClick = {
                            if (queueRunning) {
                                UploadQueueManager.pause(applicationContext)
                            } else {
                                UploadQueueManager.start(applicationContext)
                            }
                        },
                        enabled = queueState.any { it.status == UploadQueueManager.QueueItem.Status.PENDING },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (queueRunning)
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        else ButtonDefaults.buttonColors()
                    ) { Text(if (queueRunning) "Pause Queue" else "Start Queue") }

                    Button(
                        onClick = { UploadQueueManager.clear(applicationContext) },
                        enabled = queueState.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear Queue") }

                    queueState.forEach { item ->
                        Column(Modifier.fillMaxWidth()) {
                            Text("• ${item.uri.lastPathSegment} — ${item.status}")
                            if (item.status == UploadQueueManager.QueueItem.Status.RUNNING ||
                                item.status == UploadQueueManager.QueueItem.Status.PENDING
                            ) {
                                LinearProgressIndicator(
                                    progress = item.progress,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Divider()
                    }
                }
            }
        }
    }

    private fun handleWorkInfo(
        workInfo: WorkInfo?,
        onProgress: (Float) -> Unit,
        onStatusUpdate: (String) -> Unit,
        onUploadingChange: (Boolean) -> Unit
    ) {
        if (workInfo == null) return

        when (workInfo.state) {
            WorkInfo.State.ENQUEUED -> {
                onStatusUpdate("Upload is waiting to start...")
                onUploadingChange(true)
            }
            WorkInfo.State.RUNNING -> {
                onStatusUpdate("Uploading...")
                onUploadingChange(true)
                val uploaded = workInfo.progress.getLong(TusUploadWorker.KEY_PROGRESS_UPLOADED, 0)
                val total = workInfo.progress.getLong(TusUploadWorker.KEY_PROGRESS_TOTAL, 1)
                if (total > 0) {
                    onProgress(uploaded.toFloat() / total)
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                val finalUrl = workInfo.outputData.getString(TusUploadWorker.KEY_UPLOAD_URL)
                onStatusUpdate("Finished! URL: $finalUrl")
                onProgress(1f)
                onUploadingChange(false)
            }
            WorkInfo.State.FAILED -> {
                onStatusUpdate("Error: Upload failed. Check logs for details.")
                onUploadingChange(false)
            }
            WorkInfo.State.CANCELLED -> {
                onStatusUpdate("Upload cancelled by user.")
                onProgress(0f)
                onUploadingChange(false)
            }
            WorkInfo.State.BLOCKED -> {
                onStatusUpdate("Upload is blocked, waiting for constraints.")
                onUploadingChange(true)
            }
        }
    }

    private fun createWorkRequest(uri: Uri, endpoint: String): OneTimeWorkRequest {
        val fingerprint = uri.lastPathSegment + "#" + UUID.randomUUID()

        val inputData = workDataOf(
            TusUploadWorker.KEY_URI to uri.toString(),
            TusUploadWorker.KEY_ENDPOINT to endpoint,
            TusUploadWorker.KEY_FINGERPRINT to fingerprint
        )

        return OneTimeWorkRequestBuilder<TusUploadWorker>()
            .setInputData(inputData)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.SECONDS)
            .build()
    }
}

//@Composable
//private fun EndpointTestUI(endpoint: String, onResult: (String) -> Unit) {
//    val lifecycleOwner = LocalLifecycleOwner.current as ComponentActivity
//
//    fun runEndpointTest(method: String) {
//        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
//            val sb = StringBuilder()
//            try {
//                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
//                    requestMethod = method
//                    setRequestProperty("Tus-Resumable", "1.0.0")
//                    if (method == "POST") {
//                        setRequestProperty("Upload-Length", "1")
//                        val meta = listOf(
//                            "filename ${android.util.Base64.encodeToString("t.bin".toByteArray(), android.util.Base64.NO_WRAP)}",
//                            "contentType ${android.util.Base64.encodeToString("application/octet-stream".toByteArray(), android.util.Base64.NO_WRAP)}"
//                        ).joinToString(",")
//                        setRequestProperty("Upload-Metadata", meta)
//                    }
//                    connectTimeout = 5_000
//                    readTimeout = 5_000
//                }
//                sb.append("$method → ${conn.responseCode}\n")
//                conn.headerFields.forEach { (k, v) ->
//                    sb.append("${k ?: "–"}: ${v.joinToString()}\n")
//                }
//            } catch (e: Exception) {
//                sb.append("Error during $method: ${e.message}")
//            }
//            withContext(Dispatchers.Main) { onResult(sb.toString()) }
//        }
//    }
//    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
//        Button(onClick = { runEndpointTest("OPTIONS") }) { Text("OPTIONS") }
//        Button(onClick = { runEndpointTest("HEAD") }) { Text("HEAD") }
//        Button(onClick = { runEndpointTest("POST") }) { Text("POST") }
//    }
//}