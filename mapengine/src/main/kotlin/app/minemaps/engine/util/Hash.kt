package app.minemaps.engine.util

object Hash {
    fun mix64(v: Long): Long {
        var z = v + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun mix(x: Int, y: Int, salt: Int = 0): Int {
        val k = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
        return (mix64(k xor (salt.toLong() * 0x2545F4914F6CDD1DL)) ushr 16).toInt() and 0x7fffffff
    }
}
