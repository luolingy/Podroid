package com.midsancode.podroid.data.vm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope

/**
 * VM 后端工厂：按 [VmBackendKind] 创建对应实现，并集中处理「后端不可用时的回退」。
 *
 * 当前仅实现 QEMU；AVF/pKVM 预留。将来接入 AVF 时：
 *  - 在 [create] 里判断设备是否支持 pKVM（`android.system.virtualizationservice` 可用性），
 *  - 支持则返回 `AvfBackend`，否则回退 QEMU。
 * 上层（[VmService]）只依赖 [VmBackend] 接口，无需改动。
 */
object VmBackendFactory {

    private const val TAG = "VmBackendFactory"

    /**
     * @param kind 期望的后端类型
     */
    fun create(kind: VmBackendKind, context: Context, scope: CoroutineScope): VmBackend {
        return when (kind) {
            VmBackendKind.QEMU -> QemuBackend(context, scope)

            VmBackendKind.AVF -> {
                // AVF/pKVM 后端尚未实现：确定性回退到 QEMU，避免上层拿到 null。
                Log.w(TAG, "AVF 后端尚未实现，回退到 QEMU（TCG）")
                QemuBackend(context, scope)
            }
        }
    }

    /**
     * 依据当前设置选择后端。默认 QEMU。
     * 将来可根据设备能力（pKVM 支持 + Android 13+）自动升级到 AVF。
     */
    fun createDefault(context: Context, scope: CoroutineScope): VmBackend =
        create(VmBackendKind.QEMU, context, scope)
}
