package dev.openz.core

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private fun camera(chunk: Int = 64 * 1024): Pair<FakeCamera, NikonCamera> {
        val fake = FakeCamera(chunk)
        return fake to NikonCamera(PtpSession(fake), sleep = {})
    }

    @Test
    fun stringsRoundTrip() {
        val bytes = PtpWriter().string("Z 8").string("").toByteArray()
        val r = PtpReader(bytes)
        assertEquals("Z 8", r.string())
        assertEquals("", r.string())
    }

    @Test
    fun truncatedDataIsRejected() {
        assertFailsWith<PtpException> { PtpReader(byteArrayOf(1, 2)).u32() }
    }

    @Test
    fun containerRoundTrip() {
        val c = PtpContainer(ContainerType.COMMAND, Op.GET_DEVICE_INFO, 7, byteArrayOf(1, 2, 3, 4))
        val d = PtpContainer.decode(c.encode())
        assertEquals(c.code, d.code)
        assertEquals(7, d.transactionId)
        assertContentEquals(c.payload, d.payload)
    }

    @Test
    fun connectReadsDeviceInfo() {
        val (fake, cam) = camera()
        val info = cam.connect()
        assertEquals("Z 8", info.model)
        assertEquals("Nikon Corporation", info.manufacturer)
        assertTrue(cam.supports(Op.NIKON_START_LIVE_VIEW))
        assertEquals(Op.OPEN_SESSION, fake.log.first().first)
    }

    @Test
    fun reassemblesContainersDeliveredInTinyChunks() {
        val (_, cam) = camera(chunk = 5)
        assertEquals("Z 8", cam.connect().model)
    }

    @Test
    fun reopensWhenSessionAlreadyOpen() {
        val fake = FakeCamera()
        PtpSession(fake).open() // leaves the fake camera with an open session
        val cam = NikonCamera(PtpSession(fake), sleep = {})
        assertEquals("Z 8", cam.connect().model)
    }

    @Test
    fun parsesEnumPropertyAndSetsValue() {
        val (fake, cam) = camera()
        cam.connect()
        val iso = cam.getProp(Prop.EXPOSURE_INDEX)
        assertEquals(listOf(100L, 400L, 800L, 1600L), iso.choices)
        assertEquals(400L, iso.current.num)
        assertTrue(iso.writable)
        cam.setProp(iso, 800)
        assertContentEquals(byteArrayOf(0x20, 0x03), fake.setValues[Prop.EXPOSURE_INDEX]) // 800 LE u16
    }

    @Test
    fun expandsRangePropertyIntoChoices() {
        val (_, cam) = camera()
        cam.connect()
        val bias = cam.getProp(Prop.EXPOSURE_BIAS)
        assertEquals(listOf(-3000L, -2000L, -1000L, 0L, 1000L, 2000L, 3000L), bias.choices)
        assertEquals("-3.0", Format.exposureBias(bias.choices.first()))
    }

    @Test
    fun readOnlyPropertyRefusesWrites() {
        val (_, cam) = camera()
        cam.connect()
        val status = cam.getProp(Prop.NIKON_LIVE_VIEW_STATUS)
        assertFailsWith<PtpException> { cam.setProp(status, 1) }
    }

    @Test
    fun liveViewStartsAndYieldsJpeg() {
        val (fake, cam) = camera()
        cam.connect()
        cam.startLiveView()
        assertTrue(cam.liveViewRunning)
        val frame = assertNotNull(cam.liveViewFrame())
        assertContentEquals(fake.jpeg, frame)
    }

    @Test
    fun recordingTogglesState() {
        val (_, cam) = camera()
        cam.connect()
        cam.startRecording()
        assertTrue(cam.recording)
        cam.stopRecording()
        assertEquals(false, cam.recording)
    }

    @Test
    fun jpegExtractionIgnoresHeaderAndHandlesGarbage() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
        val framed = ByteArray(10) { 0 } + jpeg + ByteArray(3) { 0 }
        assertContentEquals(jpeg, NikonCamera.extractJpeg(framed))
        assertNull(NikonCamera.extractJpeg(ByteArray(20)))
    }

    @Test
    fun walksFoldersAndReadsObjectInfo() {
        val (fake, cam) = camera()
        fake.fileBytes = ByteArray(100)
        cam.connect()
        val handles = cam.listHandles()
        assertEquals(listOf(0x10001L to 200L, 0x10001L to 201L), handles)
        val obj = cam.objectInfo(201)
        assertEquals("DSC_0002.MOV", obj.name)
        assertEquals(3840, obj.width)
        assertTrue(obj.isVideo)
    }

    @Test
    fun downloadsInChunksWithProgress() {
        val (fake, cam) = camera()
        fake.fileBytes = ByteArray(10 * 1024 * 1024) { (it % 251).toByte() } // spans 3 chunks of 4 MiB
        cam.connect()
        val obj = cam.objectInfo(200)
        val out = ByteArrayOutputStream()
        var last = 0L
        cam.download(obj, out) { done, total ->
            assertEquals(fake.fileBytes.size.toLong(), total)
            assertTrue(done > last)
            last = done
        }
        assertEquals(fake.fileBytes.size.toLong(), last)
        assertContentEquals(fake.fileBytes, out.toByteArray())
    }

    @Test
    fun diagnosticDumpListsProperties() {
        val (_, cam) = camera()
        cam.connect()
        val dump = cam.diagnosticDump()
        assertTrue("ExposureIndex(ISO)" in dump)
        assertTrue("Z 8" in dump)
    }

    @Test
    fun formatsExposureValues() {
        assertEquals("1/60", Format.shutter(0x0001003C))
        assertEquals("1/80", Format.shutter(125))
        assertEquals("1\"", Format.shutter(10000))
        assertEquals("1.3\"", Format.shutter(0x000D000A))
        assertEquals("Bulb", Format.shutter(0xFFFFFFFFL))
        assertEquals("f/2.8", Format.aperture(280))
        assertEquals("f/11", Format.aperture(1100))
        assertEquals("+0.3", Format.exposureBias(300))
        assertEquals("-0.7", Format.exposureBias(-700))
        assertEquals("0.0", Format.exposureBias(0))
        assertEquals("Daylight", Format.whiteBalance(4))
        assertEquals("M", Format.exposureProgram(1))
    }
}
