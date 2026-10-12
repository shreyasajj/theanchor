package com.anchor.ui.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BedroomCopyTest {

    @Test
    fun `names the app and when it opens again`() {
        assertThat(BedroomCopy.body("YouTube", 7 * 60)).isEqualTo("YouTube stays closed in the bedroom until 07:00.")
    }

    @Test
    fun `falls back when the app has no label`() {
        assertThat(BedroomCopy.body(null, 6 * 60 + 30)).isEqualTo("This app stays closed in the bedroom until 06:30.")
    }

    @Test
    fun `title and eyebrow`() {
        assertThat(BedroomCopy.TITLE).isEqualTo("Not in the bedroom")
        assertThat(BedroomCopy.EYEBROW).isEqualTo("Bedroom")
    }
}
