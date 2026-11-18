package com.kinetra.motion

import java.io.OutputStream

/** Encoder de GIF animado (paleta fixa de 256 cores 3-3-2 com dithering ordenado). */
class GifEncoder(
    private val out: OutputStream,
    private val w: Int,
    private val h: Int,
    private val delayCs: Int
) {
    private var started = false
    private val blk = ByteArray(255)
    private var blkN = 0
    private var acc = 0
    private var accBits = 0
    private var nBits = 9
    private var maxCode = 511
    private var freeEnt = 258
    private var clearFlag = false

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    private fun s(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun header() {
        out.write("GIF89a".toByteArray())
        s(w)
        s(h)
        out.write(0xF7)
        out.write(0)
        out.write(0)
        for (i in 0 until 256) {
            out.write(((i shr 5) and 7) * 255 / 7)
            out.write(((i shr 2) and 7) * 255 / 7)
            out.write((i and 3) * 255 / 3)
        }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    fun addFrame(px: IntArray) {
        if (!started) {
            header()
            started = true
        }
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        out.write(0)
        s(delayCs)
        out.write(0)
        out.write(0)
        out.write(0x2C)
        s(0)
        s(0)
        s(w)
        s(h)
        out.write(0)
        val idx = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val bay = (bayer[(y and 3) * 4 + (x and 3)] - 8) * 4
                val r = (((c shr 16) and 255) + bay).coerceIn(0, 255)
                val g = (((c shr 8) and 255) + bay).coerceIn(0, 255)
                val b = ((c and 255) + bay).coerceIn(0, 255)
                idx[y * w + x] = (((r shr 5) shl 5) or ((g shr 5) shl 2) or (b shr 6)).toByte()
            }
        }
        lzw(idx)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private fun putByte(b: Int) {
        blk[blkN++] = b.toByte()
        if (blkN == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blkN > 0) {
            out.write(blkN)
            out.write(blk, 0, blkN)
            blkN = 0
        }
    }

    private fun emit(code: Int) {
        acc = acc or (code shl accBits)
        accBits += nBits
        while (accBits >= 8) {
            putByte(acc and 0xFF)
            acc = acc ushr 8
            accBits -= 8
        }
        if (freeEnt > maxCode || clearFlag) {
            if (clearFlag) {
                nBits = 9
                maxCode = 511
                clearFlag = false
            } else {
                nBits++
                maxCode = if (nBits == 12) 4096 else (1 shl nBits) - 1
            }
        }
    }

    private fun lzw(pix: ByteArray) {
        out.write(8)
        val clear = 256
        val eoi = 257
        val dict = HashMap<Int, Int>(8192)
        nBits = 9
        maxCode = 511
        freeEnt = 258
        clearFlag = false
        acc = 0
        accBits = 0
        blkN = 0
        emit(clear)
        var prefix = pix[0].toInt() and 255
        for (i in 1 until pix.size) {
            val k = pix[i].toInt() and 255
            val key = (prefix shl 8) or k
            val code = dict[key]
            if (code != null) {
                prefix = code
            } else {
                emit(prefix)
                if (freeEnt < 4096) {
                    dict[key] = freeEnt
                    freeEnt++
                } else {
                    dict.clear()
                    freeEnt = 258
                    clearFlag = true
                    emit(clear)
                }
                prefix = k
            }
        }
        emit(prefix)
        emit(eoi)
        if (accBits > 0) putByte(acc and 0xFF)
        flushBlock()
        out.write(0)
    }
}
