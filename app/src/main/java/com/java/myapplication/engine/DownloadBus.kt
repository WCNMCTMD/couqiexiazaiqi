package com.java.myapplication.engine

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 进程内下载事件总线（Service ↔ ViewModel 解耦）。
 *
 * 为什么不用 Binder：本项目只需"服务 → UI 单向上报进度"，用进程内共享的
 * SharedFlow 最简单、零序列化、零泄露风险。Service 与 Activity 同进程。
 *
 * 安全：只传任务进度/结果，不携带任何凭证。
 */
object DownloadBus {

    sealed interface Event {
        data class Progress(
            val taskId: Long,
            val progress: Float,
            val done: Int,
            val total: Int,
            val speed: String
        ) : Event

        data class Done(val taskId: Long, val outputPath: String) : Event
        data class Failed(val taskId: Long, val error: String) : Event

        /** 服务整体状态（用于 UI 显示"后台正在下载 N 项"） */
        data class ServiceState(val running: Boolean, val activeCount: Int) : Event
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Event> = _events

    fun emit(e: Event) {
        _events.tryEmit(e)
    }
}