package com.eve.robot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

/**
 * An explicit voice request can enrol a face. Reference crops and names stay in this app's private
 * files/preferences; the model runs locally and no face image or embedding is sent over a network.
 * Recognition is deliberately conservative: low-score and ambiguous matches return null.
 */
data class EveFaceIdentityProfile(
    val id: String,
    val personKey: String,
    val relationArabic: String,
    val name: String,
    val imageFile: String,
    val createdAtMs: Long
)

data class EveFaceIdentityMatch(
    val profile: EveFaceIdentityProfile,
    val similarity: Float
)

data class EveFaceEnrollmentRequest(
    val person: EveKnownPerson,
    val spokenIntroduction: String
)

object EveFaceEnrollmentParser {
    private val enrollmentPrefix = Regex(
        "^\\s*(?:احفظ|احفظي|تذكر|تذكري|سجل|سجلي)\\s+وجه\\s+(.+)$"
    )

    /** Examples: "احفظ وجه أمي اسمها فاطمة", "تذكري وجه صديقي اسمه أحمد". */
    fun parse(raw: String): EveFaceEnrollmentRequest? {
        val payload = enrollmentPrefix.matchEntire(raw.trim())?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val person = EvePersonUtteranceParser.parseIntroduction(payload) ?: return null
        return EveFaceEnrollmentRequest(person = person, spokenIntroduction = payload)
    }
}

/** MobileFaceNet pair-comparison model. It compares two 112x112 RGB face crops in one local run. */
class EveFaceIdentityMatcher(context: Context) : Closeable {
    companion object {
        const val MODEL_ASSET = "MobileFaceNet.tflite"
        const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 192
        const val DEFAULT_MATCH_THRESHOLD = 0.80f
    }

    private val interpreter: Interpreter

    init {
        val app = context.applicationContext
        val modelBuffer = app.assets.openFd(MODEL_ASSET).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength
                )
            }
        }
        interpreter = Interpreter(
            modelBuffer,
            Interpreter.Options().apply { setNumThreads(2) }
        )
        val inputShape = interpreter.getInputTensor(0).shape()
        require(inputShape.contentEquals(intArrayOf(2, INPUT_SIZE, INPUT_SIZE, 3))) {
            "Unexpected MobileFaceNet input shape: ${inputShape.contentToString()}"
        }
        val outputShape = interpreter.getOutputTensor(0).shape()
        require(outputShape.contentEquals(intArrayOf(2, EMBEDDING_SIZE))) {
            "Unexpected MobileFaceNet output shape: ${outputShape.contentToString()}"
        }
    }

    @Synchronized
    fun similarity(first: Bitmap, second: Bitmap): Float {
        require(!first.isRecycled && !second.isRecycled) { "Cannot compare a recycled face crop." }
        require(first.width >= 24 && first.height >= 24 && second.width >= 24 && second.height >= 24) {
            "Face crops are too small for matching."
        }
        val input = Array(2) { index ->
            val bitmap = if (index == 0) first else second
            val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            try {
                Array(INPUT_SIZE) { y ->
                    Array(INPUT_SIZE) { x ->
                        val pixel = scaled.getPixel(x, y)
                        floatArrayOf(
                            ((android.graphics.Color.red(pixel) - 127.5f) / 128f),
                            ((android.graphics.Color.green(pixel) - 127.5f) / 128f),
                            ((android.graphics.Color.blue(pixel) - 127.5f) / 128f)
                        )
                    }
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
        val embeddings = Array(2) { FloatArray(EMBEDDING_SIZE) }
        interpreter.run(input, embeddings)
        normalizeEmbedding(embeddings[0])
        normalizeEmbedding(embeddings[1])
        var squaredDistance = 0f
        for (i in 0 until EMBEDDING_SIZE) {
            val difference = embeddings[0][i] - embeddings[1][i]
            squaredDistance += difference * difference
        }
        // Unit-normalized embeddings have squared Euclidean distance in [0,4].
        return (1f - squaredDistance / 4f).coerceIn(0f, 1f)
    }

    private fun normalizeEmbedding(vector: FloatArray) {
        var sum = 0.0
        for (value in vector) sum += value * value
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-10f)
        for (i in vector.indices) vector[i] /= norm
    }

    override fun close() = interpreter.close()
}

/**
 * Stores deliberately enrolled face references only in app-private storage. There is no continuous
 * collection of camera frames. The latest camera crop is compared against named reference crops.
 */
class EveFaceIdentityMemory(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("eve_face_identity_memory", Context.MODE_PRIVATE)
    private val folder = File(app.filesDir, "eve_face_profiles").apply { mkdirs() }

    @Synchronized
    fun hasProfiles(): Boolean = readProfiles().isNotEmpty()

    @Synchronized
    fun profiles(): List<EveFaceIdentityProfile> = readProfiles()

    @Synchronized
    fun enroll(person: EveKnownPerson, faceCrop: Bitmap): EveFaceIdentityProfile {
        require(!faceCrop.isRecycled && faceCrop.width >= 24 && faceCrop.height >= 24) {
            "The detected face crop is too small to enrol."
        }
        val all = readProfiles().toMutableList()
        val identityKey = "${person.key}:${EvePersonUtteranceParser.normalize(person.name)}"
        val existing = all.firstOrNull { it.personKey == identityKey }
        val fileName = existing?.imageFile ?: "face_${UUID.randomUUID()}.enc"
        val destination = File(folder, fileName)
        val temporary = File(folder, "$fileName.tmp")
        val scaled = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)
        try {
            val jpeg = java.io.ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                    "Could not encode the private face reference."
                }
                output.toByteArray()
            }
            val sealed = encryptLocal(jpeg)
            FileOutputStream(temporary).use { stream ->
                stream.write(sealed)
                stream.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } finally {
            if (scaled !== faceCrop) scaled.recycle()
            temporary.delete()
        }

        val profile = EveFaceIdentityProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            personKey = identityKey,
            relationArabic = person.relationArabic,
            name = person.name,
            imageFile = fileName,
            createdAtMs = System.currentTimeMillis()
        )
        val replacement = all.filterNot { it.personKey == identityKey } + profile
        writeProfiles(replacement.takeLast(MAX_PROFILES))
        return profile
    }

    @Synchronized
    fun recognize(
        faceCrop: Bitmap,
        matcher: EveFaceIdentityMatcher,
        threshold: Float = EveFaceIdentityMatcher.DEFAULT_MATCH_THRESHOLD,
        minimumMargin: Float = 0.035f
    ): EveFaceIdentityMatch? {
        if (faceCrop.isRecycled || faceCrop.width < 24 || faceCrop.height < 24) return null
        val scored = readProfiles().mapNotNull { profile ->
            val file = File(folder, profile.imageFile)
            if (!file.isFile || file.length() <= 0) return@mapNotNull null
            val reference = try {
                val plain = decryptLocal(file.readBytes())
                BitmapFactory.decodeByteArray(plain, 0, plain.size)
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            val score = try {
                matcher.similarity(reference, faceCrop)
            } catch (_: Exception) {
                return@mapNotNull null
            } finally {
                reference.recycle()
            }
            profile to score
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: 0f
        if (best.second < threshold || (scored.size > 1 && best.second - second < minimumMargin)) return null
        return EveFaceIdentityMatch(best.first, best.second)
    }

    @Synchronized
    fun deleteAll() {
        folder.listFiles()?.forEach { it.delete() }
        prefs.edit().remove(KEY_PROFILES).commit()
    }

    private fun readProfiles(): List<EveFaceIdentityProfile> {
        val stored = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            // Legacy plaintext metadata is read once for migration; all subsequent writes are sealed.
            val json = if (stored.startsWith(ENCRYPTED_PREFIX)) {
                val sealed = Base64.decode(stored.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
                String(decryptLocal(sealed), Charsets.UTF_8)
            } else stored
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val profile = EveFaceIdentityProfile(
                        id = item.optString("id"),
                        personKey = item.optString("personKey"),
                        relationArabic = item.optString("relationArabic"),
                        name = item.optString("name"),
                        imageFile = item.optString("imageFile"),
                        createdAtMs = item.optLong("createdAtMs")
                    )
                    if (profile.id.isNotBlank() && profile.personKey.isNotBlank() &&
                        profile.name.isNotBlank() && profile.imageFile.startsWith("face_")
                    ) add(profile)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeProfiles(profiles: List<EveFaceIdentityProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("personKey", profile.personKey)
                    .put("relationArabic", profile.relationArabic)
                    .put("name", profile.name)
                    .put("imageFile", profile.imageFile)
                    .put("createdAtMs", profile.createdAtMs)
            )
        }
        val sealed = encryptLocal(array.toString().toByteArray(Charsets.UTF_8))
        val encoded = ENCRYPTED_PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
        check(prefs.edit().putString(KEY_PROFILES, encoded).commit()) {
            "Could not persist encrypted private face profile metadata."
        }
    }

    private fun encryptLocal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, localEncryptionKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decryptLocal(sealed: ByteArray): ByteArray {
        require(sealed.size > GCM_IV_BYTES) { "Encrypted face memory is truncated." }
        val iv = sealed.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = sealed.copyOfRange(GCM_IV_BYTES, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, localEncryptionKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun localEncryptionKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val MAX_PROFILES = 24
        private const val INPUT_SIZE = 112
        private const val GCM_IV_BYTES = 12
        private const val ENCRYPTED_PREFIX = "EVE_FACE_V1:"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "eve.face.identity.aes.v1"
    }
}
