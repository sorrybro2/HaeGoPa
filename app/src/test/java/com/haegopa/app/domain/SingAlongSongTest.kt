package com.haegopa.app.domain

import com.haegopa.app.audio.MrSynth
import com.haegopa.app.data.SingAlongCatalog
import com.haegopa.app.domain.model.DriveMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingAlongSongTest {

    private val song = SingAlongCatalog.songs.first() // 112 BPM, 전주 4박, 한 줄 8박

    private fun msAtBeat(beat: Double): Long = (beat * 60_000.0 / song.bpm).toLong()

    @Test
    fun `count-in during intro`() {
        assertEquals(-1, song.lineIndexAt(0))
        assertEquals(4, song.countInAt(0))
        assertEquals(1, song.countInAt(msAtBeat(3.5)))
    }

    @Test
    fun `lyric lines advance every 8 beats`() {
        assertNull(song.countInAt(msAtBeat(4.1)))
        assertEquals(0, song.lineIndexAt(msAtBeat(4.1)))
        assertEquals(1, song.lineIndexAt(msAtBeat(12.5)))
        assertEquals(song.lines.lastIndex, song.lineIndexAt(msAtBeat(song.totalBeats - 0.5)))
    }

    @Test
    fun `song loops after its duration`() {
        assertEquals(song.lineIndexAt(msAtBeat(13.0)), song.lineIndexAt(song.durationMs + msAtBeat(13.0)))
    }

    @Test
    fun `line progress goes from 0 to 1`() {
        assertTrue(song.lineProgressAt(msAtBeat(4.2)) < 0.1f)
        assertTrue(song.lineProgressAt(msAtBeat(11.8)) > 0.9f)
    }

    @Test
    fun `MR synth renders one audible beat`() {
        val synth = MrSynth(song, sampleRate = 22_050)
        val pcm = synth.renderBeat(globalBeat = 8)
        assertEquals(synth.samplesPerBeat, pcm.size)
        assertTrue(pcm.any { it.toInt() != 0 })
    }

    @Test
    fun `drive mode follows the speed rule`() {
        assertEquals(DriveMode.PARKED, DriveMode.fromSpeed(0))
        assertEquals(DriveMode.DRIVING, DriveMode.fromSpeed(1))
        assertEquals(DriveMode.DRIVING, DriveMode.fromSpeed(60))
    }
}
