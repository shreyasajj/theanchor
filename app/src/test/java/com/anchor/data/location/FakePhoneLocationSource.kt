package com.anchor.data.location

/** A PhoneLocationSource that returns whatever [fix] holds and counts calls. */
class FakePhoneLocationSource(var fix: PhoneFix? = null) : PhoneLocationSource {
    var calls = 0
        private set

    override suspend fun current(): PhoneFix? {
        calls++
        return fix
    }
}

fun fixAt(lat: Double, lon: Double, accuracy: Float? = 20f) = PhoneFix(lat, lon, accuracy, timeMillis = 0L)
