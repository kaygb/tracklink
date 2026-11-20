package com.kezez.tracklink.tracker

import kotlinx.coroutines.flow.MutableStateFlow

/** 单例运行状态，UI 与前台服务之间共享（进程内） */
object TrackingState {
    val running = MutableStateFlow(false)
    val sleeping = MutableStateFlow(false)
    val pending = MutableStateFlow(0)
    val recorded = MutableStateFlow(0)
    val distanceM = MutableStateFlow(0.0)
    val lastFixAt = MutableStateFlow(0L)
    val lastUploadAt = MutableStateFlow(0L)
    val message = MutableStateFlow("")

    /** 服务器连接状态：null=尚未上报过，true=最近一次上报成功，false=失败 */
    val serverOk = MutableStateFlow<Boolean?>(null)

    /** GNSS 卫星：可见数 / 参与定位数 / 按星座摘要（如「北斗 5/8 · GPS 4/6」） */
    val satellitesVisible = MutableStateFlow(0)
    val satellitesUsed = MutableStateFlow(0)
    val gnssSummary = MutableStateFlow("")

    fun note(msg: String) {
        message.value = msg
    }
}
