import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// FFT Utilities - Cooley-Tukey radix-2 DIT
// FFT Utilities - Cooley-Tukey radix-2 DIT
object FFTUtil {

    fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        if (n <= 1) return

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // Cooley-Tukey iterative FFT
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len

            for (i in 0 until n step len) {
                for (j in 0 until halfLen) {
                    val wr = cos(angle * j)
                    val wi = sin(angle * j)

                    val idx1 = i + j
                    val idx2 = i + j + halfLen

                    val tr = real[idx2] * wr - imag[idx2] * wi
                    val ti = real[idx2] * wi + imag[idx2] * wr

                    real[idx2] = real[idx1] - tr
                    imag[idx2] = imag[idx1] - ti
                    real[idx1] += tr
                    imag[idx1] += ti
                }
            }
            len = len shl 1
        }
    }

    fun ifft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size

        // Conjugate
        for (i in 0 until n) imag[i] = -imag[i]

        // Forward FFT
        fft(real, imag)

        // Conjugate and scale
        val scale = 1.0 / n
        for (i in 0 until n) {
            real[i] *= scale
            imag[i] = -imag[i] * scale
        }
    }
}