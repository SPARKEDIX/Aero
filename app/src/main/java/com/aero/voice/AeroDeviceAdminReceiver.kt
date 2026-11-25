package com.aero.voice

import android.app.admin.DeviceAdminReceiver

/** Device-admin receiver: enables lockNow() and keyguard bypass for AI lock/unlock. */
class AeroDeviceAdminReceiver : DeviceAdminReceiver()
