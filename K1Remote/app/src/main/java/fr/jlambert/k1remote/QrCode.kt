package fr.jlambert.k1remote

/**
 * Générateur de QR code minimal, sans dépendance (mode octets, correction M, versions 1 à 15).
 * Algorithme standard ISO/IEC 18004, d'après l'implémentation de référence de Nayuki (licence MIT).
 */
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {

    fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y][x]

    companion object {
        private val ECC_PER_BLOCK_M = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24)
        private val NUM_BLOCKS_M = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10)
        private const val MAX_VERSION = 15

        /** Encode du texte (UTF-8). Renvoie null si trop long. */
        fun encode(text: String): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            var version = 1
            while (true) {
                if (version > MAX_VERSION) return null
                val capacityBits = numDataCodewords(version) * 8
                val ccBits = if (version <= 9) 8 else 16
                if (4 + ccBits + data.size * 8 <= capacityBits) break
                version++
            }
            // Flux de bits : mode octets (0100), longueur, données, terminaison, bourrage
            val bits = ArrayList<Boolean>()
            fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits += (v ushr i) and 1 != 0 }
            put(4, 4)
            put(data.size, if (version <= 9) 8 else 16)
            for (b in data) put(b.toInt() and 0xFF, 8)
            val capacity = numDataCodewords(version) * 8
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val dataCodewords = ByteArray(bits.size / 8)
            for (i in bits.indices) if (bits[i]) dataCodewords[i ushr 3] = (dataCodewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()

            val all = addEccAndInterleave(dataCodewords, version)
            val size = version * 4 + 17
            // Choix du masque qui pénalise le moins
            var best: Array<BooleanArray>? = null
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                val m = Builder(version, size)
                m.drawFunctionPatterns()
                m.drawCodewords(all)
                m.applyMask(mask)
                m.drawFormatBits(mask)
                val pen = m.penalty()
                if (pen < bestPenalty) { bestPenalty = pen; best = m.modules }
            }
            return QrCode(size, best!!)
        }

        private fun numRawDataModules(ver: Int): Int {
            var result = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val numAlign = ver / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (ver >= 7) result -= 36
            }
            return result
        }

        private fun numDataCodewords(ver: Int): Int =
            numRawDataModules(ver) / 8 - ECC_PER_BLOCK_M[ver] * NUM_BLOCKS_M[ver]

        private fun addEccAndInterleave(data: ByteArray, ver: Int): ByteArray {
            val numBlocks = NUM_BLOCKS_M[ver]
            val blockEccLen = ECC_PER_BLOCK_M[ver]
            val rawCodewords = numRawDataModules(ver) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLen = rawCodewords / numBlocks
            val divisor = rsDivisor(blockEccLen)
            val blocks = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val datLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
                val dat = data.copyOfRange(k, k + datLen)
                k += datLen
                val block = ByteArray(shortBlockLen + 1)
                System.arraycopy(dat, 0, block, 0, dat.size)
                val ecc = rsRemainder(dat, divisor)
                System.arraycopy(ecc, 0, block, block.size - blockEccLen, ecc.size)
                blocks += block
            }
            val result = ByteArray(rawCodewords)
            var idx = 0
            for (i in blocks[0].indices) {
                for (j in blocks.indices) {
                    // Les blocs courts n'ont pas d'octet de données à la position shortBlockLen - blockEccLen
                    if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                        result[idx++] = blocks[j][i]
                    }
                }
            }
            return result
        }

        private fun rsMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in result.indices) {
                    result[j] = rsMultiply(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = rsMultiply(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor rsMultiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }
    }

    private class Builder(val version: Int, val size: Int) {
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

        fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = alignmentPositions()
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) alignment(pos[i], pos[j])
            }
            drawFormatBits(0)   // réserve la zone
            drawVersion()
        }

        private fun finder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) set(xx, yy, dist != 2 && dist != 4)
            }
        }

        private fun alignment(x: Int, y: Int) {
            for (dy in -2..2) for (dx in -2..2) set(x + dx, y + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        fun drawFormatBits(mask: Int) {
            val data = (0 shl 3) or mask          // niveau M = 00
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val c = i / 3
                set(a, c, b); set(c, a, b)
            }
        }

        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y][x] && i < data.size * 8) {
                            modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (!isFunction[y][x] && invert) modules[y][x] = !modules[y][x]
            }
        }

        /** Pénalité simplifiée (règles 1 à 4 de la norme). */
        fun penalty(): Int {
            var result = 0
            // Règle 1 : suites de 5+ modules identiques ; règle 3 : motifs 1011101 bordés de 4 blancs
            for (pass in 0..1) {
                for (a in 0 until size) {
                    var runColor = false; var run = 0
                    for (b in 0 until size) {
                        val c = if (pass == 0) modules[a][b] else modules[b][a]
                        if (b > 0 && c == runColor) {
                            run++
                            if (run == 5) result += 3 else if (run > 5) result++
                        } else { runColor = c; run = 1 }
                    }
                    for (b in 0..size - 11) {
                        fun m(k: Int) = if (pass == 0) modules[a][b + k] else modules[b + k][a]
                        val core = m(0) && !m(1) && m(2) && m(3) && m(4) && !m(5) && m(6)
                        if (core && !m(7) && !m(8) && !m(9) && !m(10)) result += 40
                        val core2 = !m(0) && !m(1) && !m(2) && !m(3) && m(4) && !m(5) && m(6) && m(7) && m(8) && !m(9) && m(10)
                        if (core2) result += 40
                    }
                }
            }
            // Règle 2 : blocs 2x2
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            // Règle 4 : équilibre noir / blanc
            var dark = 0
            for (row in modules) for (c in row) if (c) dark++
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }
    }
}
