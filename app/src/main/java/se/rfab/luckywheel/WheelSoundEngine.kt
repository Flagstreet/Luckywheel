package se.rfab.luckywheel

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Hanterar klickljudet för hjulet.
 *
 * Genererar ett syntetiskt klickljud (decayed sine burst) programmatiskt
 * och laddar det i SoundPool för låg latens och tät upprepning.
 *
 * Livscykel: anropa [init] i onResume-ekvivalent (DisposableEffect),
 *            anropa [release] i onPause-ekvivalent (onDispose).
 */
class WheelSoundEngine(private val context: Context) {

    private var soundPool: SoundPool? = null
    private var soundId: Int = 0
    private var loaded = false

    /** Initierar SoundPool och laddar klickljudet. */
    fun init() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
            .also { pool ->
                pool.setOnLoadCompleteListener { _, _, status ->
                    if (status == 0) loaded = true
                }
                soundId = pool.load(ensureClickWav().absolutePath, 1)
            }
    }

    /** Spelar ett klickljud. Är no-op om ljud inte är laddat ännu. */
    fun playClick() {
        if (loaded) soundPool?.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    /** Frigör SoundPool-resurser. Anropas när composable lämnar komposition. */
    fun release() {
        soundPool?.release()
        soundPool = null
        loaded = false
    }

    // ── Intern WAV-generering ─────────────────────────────────────────────────

    private fun ensureClickWav(): File {
        val file = File(context.cacheDir, "wheel_click.wav")
        writeClickWav(file)   // Liten fil (~800 bytes), alltid färsk
        return file
    }

    private fun writeClickWav(file: File) {
        val sampleRate = 44100
        val numSamples = sampleRate * CLICK_DURATION_MS / 1000   // ≈ 353 samplingar

        // Snabbt klingande sinuspuls – låter som ett mekaniskt klick
        val pcm = ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            (Short.MAX_VALUE * CLICK_AMPLITUDE
                    * sin(2.0 * PI * CLICK_FREQ_HZ * t)
                    * exp(-CLICK_DECAY * t))
                .toInt().toShort()
        }

        // Skriv som little-endian mono PCM WAV
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF-huvud
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt-chunk
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)          // chunk-storlek
        buf.putShort(1)         // PCM
        buf.putShort(1)         // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)  // byte rate
        buf.putShort(2)         // block align
        buf.putShort(16)        // bitar per sampel

        // data-chunk
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm) buf.putShort(s)

        buf.flip()
        FileOutputStream(file).channel.use { it.write(buf) }
    }

    companion object {
        private const val CLICK_DURATION_MS = 8       // ms
        private const val CLICK_FREQ_HZ     = 1500.0  // Hz – mekanisk klickton
        private const val CLICK_DECAY       = 300.0   // Snabb avklingning → kortare klick
        private const val CLICK_AMPLITUDE   = 0.8     // 0..1
    }
}
