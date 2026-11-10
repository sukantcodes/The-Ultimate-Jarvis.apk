package com.jarvis.assistant.provider

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Tiny, valid sample files that capability probes send to a model: a small picture, a one-page PDF and a short
 * silent recording. They are generated, not downloaded, and contain nothing personal.
 */
object ProbeAssets {

    /** A [size] x [size] two-tone RGB PNG. Large enough for vision models that reject 1-pixel images. */
    fun png(size: Int = 64): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until size) {
            raw.write(0) // filter: none
            for (x in 0 until size) {
                val left = x < size / 2
                val top = y < size / 2
                val white = left == top
                val v = if (white) 235 else 25
                raw.write(v); raw.write(v); raw.write(if (white) 235 else 120)
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) compressed.write(buf, 0, deflater.deflate(buf))
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteBuffer.allocate(13).putInt(size).putInt(size).put(8).put(2).put(0).put(0).put(0).array()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    /** A valid one-page PDF whose text reads "JARVIS probe page". */
    fun pdf(): ByteArray {
        val stream = "BT /F1 18 Tf 20 50 Td (JARVIS probe page) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 240 100] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets.add(sb.length)
            sb.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xrefAt = sb.length
        sb.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(String.format("%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** Mono 16-bit PCM WAV of silence. */
    fun silentWav(ms: Int = 600, sampleRate: Int = 16_000): ByteArray {
        val samples = sampleRate * ms / 1000
        val dataLen = samples * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataLen)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataLen)
        return buf.array()
    }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
