package dev.butan.core

/**
 * Minimal Bech32 (BIP-173) codec used by the age key format.
 *
 * Age uses Bech32 without the 90 character length limit, with the HRPs
 * `age` (recipient) and `AGE-SECRET-KEY-` (identity).
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private val CHARSET_REV = IntArray(128) { -1 }.also { table ->
        CHARSET.forEachIndexed { index, char -> table[char.code] = index }
    }

    private val GENERATORS = intArrayOf(
        0x3b6a57b2,
        0x26508e6d,
        0x1ea119fa,
        0x3d4233dd,
        0x2a1462b3,
    )

    fun encode(hrp: String, data: ByteArray, uppercase: Boolean = false): String {
        val lowerHrp = hrp.lowercase()
        require(lowerHrp.isNotEmpty()) { "empty HRP" }
        require(lowerHrp.all { it.code in 33..126 }) { "invalid HRP" }

        val values = convertBits(data, 8, 5, pad = true)
        val checksum = createChecksum(lowerHrp, values)

        val output = StringBuilder(lowerHrp).append('1')
        values.forEach { output.append(CHARSET[it]) }
        checksum.forEach { output.append(CHARSET[it]) }

        return if (uppercase) output.toString().uppercase() else output.toString()
    }

    /** Returns the lower-cased HRP and decoded payload, or null if the string is invalid. */
    fun decode(input: String): Pair<String, ByteArray>? {
        if (input.isEmpty()) return null
        if (input.any { it.code !in 33..126 }) return null

        val hasLower = input.any { it in 'a'..'z' }
        val hasUpper = input.any { it in 'A'..'Z' }
        if (hasLower && hasUpper) return null

        val normalized = input.lowercase()
        val separator = normalized.lastIndexOf('1')
        if (separator < 1 || separator + 7 > normalized.length) return null

        val hrp = normalized.substring(0, separator)
        val dataPart = normalized.substring(separator + 1)
        val values = IntArray(dataPart.length)

        for (index in dataPart.indices) {
            val value = CHARSET_REV[dataPart[index].code]
            if (value < 0) return null
            values[index] = value
        }

        if (polymod(hrpExpand(hrp) + values) != 1) return null

        val payload = convertBits(values.copyOf(values.size - 6), 5, 8, pad = false) ?: return null
        return hrp to payload
    }

    private fun createChecksum(hrp: String, values: IntArray): IntArray {
        val expanded = hrpExpand(hrp) + values + IntArray(6)
        val mod = polymod(expanded) xor 1
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    private fun hrpExpand(hrp: String): IntArray {
        val expanded = IntArray(hrp.length * 2 + 1)
        for (index in hrp.indices) {
            expanded[index] = hrp[index].code ushr 5
        }
        expanded[hrp.length] = 0
        for (index in hrp.indices) {
            expanded[hrp.length + 1 + index] = hrp[index].code and 31
        }
        return expanded
    }

    private fun polymod(values: IntArray): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (index in 0 until 5) {
                if (((top ushr index) and 1) == 1) {
                    checksum = checksum xor GENERATORS[index]
                }
            }
        }
        return checksum
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray {
        val output = ArrayList<Int>(data.size * from / to + 1)
        val maxValue = (1 shl to) - 1
        val maxAccumulator = (1 shl (from + to - 1)) - 1
        var accumulator = 0
        var bits = 0

        for (byte in data) {
            val value = byte.toInt() and 0xff
            require(value ushr from == 0) { "input value exceeds $from bits" }
            accumulator = ((accumulator shl from) or value) and maxAccumulator
            bits += from
            while (bits >= to) {
                bits -= to
                output.add((accumulator ushr bits) and maxValue)
            }
        }

        if (pad) {
            if (bits > 0) {
                output.add((accumulator shl (to - bits)) and maxValue)
            }
        } else if (bits >= from || ((accumulator shl (to - bits)) and maxValue) != 0) {
            return IntArray(0)
        }

        return output.toIntArray()
    }

    private fun convertBits(data: IntArray, from: Int, to: Int, pad: Boolean): ByteArray? {
        val output = ArrayList<Byte>(data.size * from / to + 1)
        val maxValue = (1 shl to) - 1
        val maxAccumulator = (1 shl (from + to - 1)) - 1
        var accumulator = 0
        var bits = 0

        for (value in data) {
            if (value < 0 || value ushr from != 0) return null
            accumulator = ((accumulator shl from) or value) and maxAccumulator
            bits += from
            while (bits >= to) {
                bits -= to
                output.add(((accumulator ushr bits) and maxValue).toByte())
            }
        }

        if (pad) {
            if (bits > 0) {
                output.add(((accumulator shl (to - bits)) and maxValue).toByte())
            }
        } else if (bits >= from || ((accumulator shl (to - bits)) and maxValue) != 0) {
            return null
        }

        return output.toByteArray()
    }
}
