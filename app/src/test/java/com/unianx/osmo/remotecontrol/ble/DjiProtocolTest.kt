package com.unianx.osmo.remotecontrol.ble

import com.unianx.osmo.remotecontrol.data.ControllerIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjiProtocolTest {
    @Test
    fun `mode switch frame matches DJI documentation sample`() {
        val payload = DjiProtocol.createModeSwitchPayload(
            cameraDeviceIdRaw = 0xFF330000.toInt(),
            mode = CameraMode.Hyperlapse,
        )

        val frame = DjiProtocol.buildFrame(
            cmdSet = DjiProtocol.CmdSetCamera,
            cmdId = DjiProtocol.CmdIdModeSwitch,
            cmdType = DjiProtocol.CmdResponseOrNot,
            seq = 0x0005,
            payload = payload,
        )

        val expected = byteArrayOf(
            0xAA.toByte(), 0x1B, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x05, 0x00, 0x57, 0xEE.toByte(), 0x1D, 0x04, 0x00, 0x00,
            0x33, 0xFF.toByte(), 0x0A, 0x01, 0x47, 0x39, 0x36, 0xF4.toByte(),
            0xFA.toByte(), 0xE1.toByte(), 0xD0.toByte(),
        )

        assertArrayEquals(expected, frame)
    }

    @Test
    fun `connection request payload stores pseudo MAC and verify code`() {
        val identity = ControllerIdentity(
            deviceId = 0x12345678,
            pseudoMac = byteArrayOf(0x38, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte()),
        )

        val payload = DjiProtocol.createConnectionRequestPayload(
            identity = identity,
            verifyMode = 0,
            verifyData = 9527,
        )

        assertEquals(33, payload.size)
        assertEquals(0x38, payload[5].toInt() and 0xFF)
        assertEquals(0xBC, payload[10].toInt() and 0xFF)
        assertEquals(0x37, payload[27].toInt() and 0xFF)
        assertEquals(0x25, payload[28].toInt() and 0xFF)
    }

    @Test
    fun `protocol parser reads GPS sample frame header`() {
        val frame = byteArrayOf(
            0xAA.toByte(), 0x42, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x03, 0x00, 0xD2.toByte(), 0xDD.toByte(), 0x00, 0x17, 0x4F, 0x00,
            0x35, 0x01, 0x8A.toByte(), 0xA5.toByte(), 0x02, 0x00, 0xBC.toByte(), 0xB2.toByte(),
            0xE9.toByte(), 0x43, 0xA3.toByte(), 0x4F, 0x75, 0x0D, 0xB9.toByte(), 0x6D, 0x00,
            0x00, 0xDE.toByte(), 0x58, 0x24, 0x41, 0x8D.toByte(), 0x9A.toByte(), 0xC5.toByte(), 0x41,
            0x00, 0x00, 0x00, 0x00, 0xE8.toByte(), 0x03, 0x00, 0x00, 0xE8.toByte(), 0x03, 0x00, 0x00,
            0x0A, 0x00, 0x00, 0x00, 0x0E, 0x00, 0x00, 0x00, 0x63, 0xB5.toByte(), 0xD1.toByte(), 0x31,
        )

        val parsed = DjiProtocol.parseFrame(frame)

        assertEquals(0x17, parsed.cmdId)
        assertEquals(0x00, parsed.cmdSet)
        assertEquals(0x0003, parsed.seq)
        assertEquals(48, parsed.payload.size)
    }

    @Test
    fun `stream decoder reassembles frame from split notifications`() {
        val fullFrame = DjiProtocol.buildFrame(
            cmdSet = DjiProtocol.CmdSetConnection,
            cmdId = DjiProtocol.CmdIdConnection,
            cmdType = DjiProtocol.CmdWaitResult,
            seq = 0x1234,
            payload = DjiProtocol.createConnectionRequestPayload(
                identity = ControllerIdentity(
                    deviceId = 0x12345678,
                    pseudoMac = byteArrayOf(0x38, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte()),
                ),
                verifyMode = 2,
                verifyData = 0,
            ),
        )

        val decoder = DjiFrameStreamDecoder()
        val splitIndex = 12

        val firstChunkFrames = decoder.append(fullFrame.copyOfRange(0, splitIndex))
        val secondChunkFrames = decoder.append(fullFrame.copyOfRange(splitIndex, fullFrame.size))

        assertTrue(firstChunkFrames.isEmpty())
        assertEquals(1, secondChunkFrames.size)
        assertEquals(0x1234, secondChunkFrames.single().seq)
        assertEquals(DjiProtocol.CmdIdConnection, secondChunkFrames.single().cmdId)
    }

    @Test
    fun `stream decoder parses multiple concatenated frames`() {
        val first = DjiProtocol.buildFrame(
            cmdSet = DjiProtocol.CmdSetConnection,
            cmdId = DjiProtocol.CmdIdGpsPush,
            cmdType = DjiProtocol.CmdNoResponse,
            seq = 0x0007,
            payload = ByteArray(4) { it.toByte() },
        )
        val second = DjiProtocol.buildFrame(
            cmdSet = DjiProtocol.CmdSetCamera,
            cmdId = DjiProtocol.CmdIdStatusSubscription,
            cmdType = DjiProtocol.CmdNoResponse,
            seq = 0x0008,
            payload = DjiProtocol.createStatusSubscriptionPayload(),
        )

        val decoded = DjiFrameStreamDecoder().append(first + second)

        assertEquals(2, decoded.size)
        assertEquals(0x0007, decoded[0].seq)
        assertEquals(DjiProtocol.CmdIdGpsPush, decoded[0].cmdId)
        assertEquals(0x0008, decoded[1].seq)
        assertEquals(DjiProtocol.CmdIdStatusSubscription, decoded[1].cmdId)
    }
}
