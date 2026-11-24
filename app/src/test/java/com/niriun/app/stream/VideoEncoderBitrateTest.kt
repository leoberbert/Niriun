package com.niriun.app.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEncoderBitrateTest {
    @Test
    fun usesTheReferenceTableForStandardSizes() {
        assertEquals(1_612_800, VideoEncoder.bitrateFor(640, 480, 30, hevc = false))
        assertEquals(3_628_800, VideoEncoder.bitrateFor(1280, 720, 30, hevc = false))
        assertEquals(6_894_720, VideoEncoder.bitrateFor(1920, 1080, 30, hevc = false))
        assertEquals(16_000_000, VideoEncoder.bitrateFor(3840, 2160, 30, hevc = false))
    }

    @Test
    fun highFrameRateAddsHalfAndIsCappedAt16Mbps() {
        assertEquals(10_342_080, VideoEncoder.bitrateFor(1920, 1080, 60, hevc = false))
        assertEquals(16_000_000, VideoEncoder.bitrateFor(3840, 2160, 60, hevc = false))
    }

    @Test
    fun hevcNeedsFourFifthsOfTheBitrate() {
        assertEquals(2_903_040, VideoEncoder.bitrateFor(1280, 720, 30, hevc = true))
    }

    @Test
    fun unknownSizesUseTheLinearFormula() {
        assertEquals((800 * 2.7 * 600 + 1_500_000).toInt(), VideoEncoder.bitrateFor(800, 600, 30, hevc = false))
    }
}
