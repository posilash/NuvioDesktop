package com.nuvio.app.features.calendar

import com.nuvio.app.core.storage.DesktopStorage

internal actual object CalendarStorage {
    private val store = DesktopStorage.store("nuvio_calendar")

    actual fun loadPayload(key: String): String? = store.getString(key)

    actual fun savePayload(key: String, payload: String) {
        store.putString(key, payload)
    }
}
