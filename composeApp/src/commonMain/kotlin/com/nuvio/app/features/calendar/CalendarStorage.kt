package com.nuvio.app.features.calendar

/** The last built calendar, per profile key, so the tab opens without refetching. */
internal expect object CalendarStorage {
    fun loadPayload(key: String): String?
    fun savePayload(key: String, payload: String)
}
