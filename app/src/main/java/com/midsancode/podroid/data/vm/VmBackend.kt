package com.midsancode.podroid.data.vm

/**
 * VM 后端抽象。
 *
 * 把「如何启动并驱动一台 guest VM」与具体实现解耦：
 *  - [QemuBackend]：QEMU TCG 软件模拟（当前默认，任何 arm64 设备可用）
 *  - AVF/pKVM 硬件虚拟化后端：将来接入（需 Android 13+ 且设备支持 pKVM）
 *
 * 约定：后端只负责进程/虚拟机的生命周期与串口 I/O，**状态上报统一走
 * `VmManager.onSerialLine` / `onProcessLaunched` / `onProcessExited` /
 * `onStartFailure`**（与既有实现一致），UI 通过 `VmManager.status` 观察。
 */
interface VmBackend {

    /** 后端类型标识（诊断/日志用），如 "qemu"、"avf"。 */
    val kind: String

    /** 当前是否处于运行态。 */
    val isRunning: Boolean

    /**
     * 启动虚拟机。非阻塞：内部在 IO 协程里拉起进程/虚拟机，并通过 VmManager 上报进度。
     * 失败时调用 `VmManager.onStartFailure`。
     */
    fun start()

    /** 停止虚拟机（幂等）。停止后通过 VmManager 上报 Stopped。 */
    fun stop()

    /**
     * 向 guest 串口/控制台写入一行文本（不含换行，实现方自行补 \n）。
     * a) 终端交互；b) 登录/引导脚本注入。
     */
    fun writeConsole(text: String)
}

/** 可选的 VM 后端类型。 */
enum class VmBackendKind {
    /** QEMU TCG 软件模拟（默认、通用）。 */
    QEMU,

    /**
     * AVF / pKVM 硬件虚拟化后端 —— 预留，尚未实现。
     * 工厂目前会回退到 QEMU；接入后在支持设备上使用以摆脱 TCG 软模拟。
     */
    AVF,
}
