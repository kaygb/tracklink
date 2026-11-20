package com.kezez.tracklink.tracker

import android.content.Context
import java.io.File

/**
 * 离线队列：轨迹点先落盘再上传，断网/进程被杀都不会丢。
 * 容量上限 3000 点（约 24 小时高频记录），超出后丢弃最老的。
 */
class QueueStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "pending_points.json")
    private val lock = Any()
    private val points = ArrayList<TrackPoint>()

    init {
        synchronized(lock) {
            if (file.exists()) {
                runCatching { file.readText() }
                    .getOrNull()
                    ?.let { points.addAll(TrackPoint.listFromJson(it)) }
            }
        }
    }

    val size: Int get() = synchronized(lock) { points.size }

    fun add(point: TrackPoint) {
        val overflow = synchronized(lock) {
            points.add(point)
            var dropped = 0
            while (points.size > MAX_POINTS) {
                points.removeAt(0)
                dropped++
            }
            persistLocked()
            dropped
        }
        if (overflow > 0) TrackingState.note("队列已满，丢弃最老的 $overflow 个点")
    }

    fun snapshot(): List<TrackPoint> = synchronized(lock) { ArrayList(points) }

    /** 上传成功后移除已确认的点位 */
    fun removeUploaded(uploadedCount: Int) {
        synchronized(lock) {
            repeat(uploadedCount.coerceAtMost(points.size)) { points.removeAt(0) }
            persistLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            points.clear()
            persistLocked()
        }
    }

    private fun persistLocked() {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(TrackPoint.listToJson(points))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        const val MAX_POINTS = 3000
        /** 单次上传的点位数，控制请求体大小 */
        const val BATCH_SIZE = 200
    }
}
