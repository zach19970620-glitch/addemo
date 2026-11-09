package com.haokai.hk5mh210.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 监听 USB UVC 设备插拔（HK5M-H210 等）。
 */
class UsbCameraMonitor(private val context: Context) {

    data class UsbCameraDevice(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val deviceId: Int,
    )

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<UsbCameraDevice>>(emptyList())
    val devices: StateFlow<List<UsbCameraDevice>> = _devices.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refresh()
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        refresh()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun refresh() {
        val list = usbManager.deviceList.values
            .filter { isLikelyUvc(it) }
            .map {
                UsbCameraDevice(
                    deviceName = it.deviceName,
                    vendorId = it.vendorId,
                    productId = it.productId,
                    deviceId = it.deviceId,
                )
            }
        _devices.value = list
    }

    private fun isLikelyUvc(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            // USB Video Class = 14
            if (intf.interfaceClass == 14) return true
            // Misc / IAD often used by UVC composite devices
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MISC &&
                intf.interfaceSubclass == 2
            ) {
                return true
            }
        }
        return false
    }
}
