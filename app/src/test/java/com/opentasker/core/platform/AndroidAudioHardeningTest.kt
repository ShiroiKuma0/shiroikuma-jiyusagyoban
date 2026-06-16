package com.opentasker.core.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAudioHardeningTest {
    @Test
    fun restrictionStartsAtAndroid17() {
        assertFalse(AndroidAudioHardening.isRestricted(36))
        assertTrue(AndroidAudioHardening.isRestricted(37))
        assertTrue(AndroidAudioHardening.isRestricted(38))
    }

    @Test
    fun soundAndTtsFailuresAreHonest() {
        val sound = AndroidAudioHardening.soundPlaybackFailure()
        val tts = AndroidAudioHardening.ttsFailure()

        assertTrue(sound.message.contains("Android 17+"))
        assertTrue(sound.message.contains("without a media foreground-service type"))
        assertTrue(tts.message.contains("TTS"))
        assertTrue(tts.message.contains("without a media foreground-service type"))
    }
}
