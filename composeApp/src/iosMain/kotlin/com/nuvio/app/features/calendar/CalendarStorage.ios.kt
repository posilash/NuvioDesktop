package com.nuvio.app.features.calendar

import platform.Foundation.NSUserDefaults

internal actual object CalendarStorage {
    actual fun loadPayload(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key)

    actual fun savePayload(key: String, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = key)
    }
}
