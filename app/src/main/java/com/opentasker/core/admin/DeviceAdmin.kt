package com.opentasker.core.admin

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin receiver — its only purpose is to let the user grant 白い熊 自由作業盤 the
 * `force-lock` policy so the `screen.lockdown` action can call `DevicePolicyManager.lockNow(...)`.
 * No callbacks are needed.
 */
class DeviceAdmin : DeviceAdminReceiver()
