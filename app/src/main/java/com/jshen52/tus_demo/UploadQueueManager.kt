package com.jshen52.tus_demo

import android.content.Context
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

object UploadQueueManager {

    data class QueueItem(
        val id: UUID = UUID.randomUUID(),
        val uri: Uri,
        val endpoint: String,
        val workId: UUID? = null,
        var progress: Float = 0f,
        var status: Status = Status.PENDING
    ) {
        enum class Status { PENDING, RUNNING, PAUSED, SUCCESS, FAILED }
    }

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var loopJob: Job? = null

    fun enqueue(uri: Uri, endpoint: String) {
        _queue.update { it + QueueItem(uri = uri, endpoint = endpoint) }
    }

    fun start(context: Context) {
        if (_running.value) return
        _running.value = true
        loopJob = CoroutineScope(Dispatchers.IO).launch {
            val wm = WorkManager.getInstance(context)

            queueLoop@ while (isActive && _running.value) {
                val next = _queue.value.firstOrNull {
                    it.status == QueueItem.Status.PENDING || it.status == QueueItem.Status.PAUSED
                } ?: break@queueLoop

                val work = OneTimeWorkRequestBuilder<TusUploadWorker>()
                    .setInputData(workDataOf(
                        TusUploadWorker.KEY_URI to next.uri.toString(),
                        TusUploadWorker.KEY_ENDPOINT to next.endpoint,
                        TusUploadWorker.KEY_FINGERPRINT to (next.uri.lastPathSegment + "#" + next.id)
                    ))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                _queue.update { currentQueue ->
                    currentQueue.map { item ->
                        if (item.id == next.id) item.copy(workId = work.id, status = QueueItem.Status.RUNNING)
                        else item
                    }
                }

                val flow = wm.getWorkInfoByIdFlow(work.id)
                val observerJob = launch {
                    flow.collect { wi: WorkInfo? ->
                        wi?.let {
                            when (wi.state) {
                                WorkInfo.State.RUNNING -> {
                                    val uploaded = wi.progress.getLong(TusUploadWorker.KEY_PROGRESS_UPLOADED, 0)
                                    val total = wi.progress.getLong(TusUploadWorker.KEY_PROGRESS_TOTAL, 1)
                                    val newProgress = if (total > 0) uploaded.toFloat() / total else 0f
                                    _queue.update { currentQueue ->
                                        currentQueue.map { item ->
                                            if (item.id == next.id) item.copy(progress = newProgress)
                                            else item
                                        }
                                    }
                                }
                                WorkInfo.State.SUCCEEDED -> {
                                    _queue.update { currentQueue ->
                                        currentQueue.map { item ->
                                            if (item.id == next.id) item.copy(progress = 1f, status = QueueItem.Status.SUCCESS)
                                            else item
                                        }
                                    }
                                    cancel()
                                }
                                WorkInfo.State.FAILED -> {
                                    _queue.update { currentQueue ->
                                        currentQueue.map { item ->
                                            if (item.id == next.id) item.copy(status = QueueItem.Status.FAILED)
                                            else item
                                        }
                                    }
                                    cancel()
                                }
                                WorkInfo.State.CANCELLED -> {
                                    cancel()
                                }
                                else -> Unit
                            }
                        }
                    }
                }

                wm.enqueue(work)
                observerJob.join()
            }

            _running.value = false
        }
    }

    fun pause(context: Context) {
        loopJob?.cancel()
        _running.value = false

        val runningItem = _queue.value.firstOrNull { it.status == QueueItem.Status.RUNNING }
        runningItem?.workId?.let {
            WorkManager.getInstance(context).cancelWorkById(it)
        }

        _queue.update { currentQueue ->
            currentQueue.map { item ->
                if (item.status == QueueItem.Status.RUNNING) {
                    item.copy(status = QueueItem.Status.PAUSED)
                } else {
                    item
                }
            }
        }
    }

    fun clear(context: Context) {
        loopJob?.cancel()
        val wm = WorkManager.getInstance(context)
        _queue.value.mapNotNull { it.workId }.forEach { wm.cancelWorkById(it) }
        _queue.value = emptyList()
        _running.value = false
    }
}