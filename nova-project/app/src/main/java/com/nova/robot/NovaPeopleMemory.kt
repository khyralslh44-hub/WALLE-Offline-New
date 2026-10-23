package com.nova.robot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Nova's experimental, fully on-device people memory.
 *
 * It stores compact LBP texture descriptors and the user-provided name/relation, never face photos.
 * LBP is deliberately treated as a conservative prototype, not a security-grade face recognizer:
 * pose, lighting, camera, and similar-looking people can affect results. Uncertain matches are null.
 */
internal data class NovaKnownPerson(
    val id: String,
    val relation: String,
    val name: String,
    val descriptor: FloatArray,
    val enrolledAtMillis: Long
)

internal data class NovaPersonMatch(
    val person: NovaKnownPerson,
    val distance: Float
)

internal data class NovaEnrollmentRequest(val relation: String, val name: String)

internal object NovaEnrollmentParser {
    fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي')
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Examples: احفظ وجه أمي اسمها فاطمة / احفظ وجه صديقي اسمه أحمد. */
    fun parse(raw: String): NovaEnrollmentRequest? {
        val q = normalize(raw)
        if (!q.startsWith("احفظ وجه ") && !q.startsWith("احفظي وجه ") &&
            !q.startsWith("تذكر وجه ") && !q.startsWith("تذكري وجه ")) return null

        val relationAndName = q.substringAfter("وجه ").trim()
        val patterns = listOf(
            Triple("الأم", "^(?:امي|والدتي) اسمها (.+)$", "female"),
            Triple("الأب", "^(?:ابي|ابوي|والدي) اسمه (.+)$", "male"),
            Triple("الأخ", "^(?:اخي|اخوي) اسمه (.+)$", "male"),
            Triple("الأخت", "^(?:اختي) اسمها (.+)$", "female"),
            Triple("الصديق", "^(?:صديقي|صاحبي) اسمه (.+)$", "male"),
            Triple("الصديقة", "^(?:صديقتي|صاحبتي) اسمها (.+)$", "female"),
            Triple("الجدة", "^(?:جدتي|تيته) اسمها (.+)$", "female"),
            Triple("الجد", "^(?:جدي|جدو) اسمه (.+)$", "male"),
            Triple("شخص من العائلة", "^(?:قريبي|قريبتي) (?:اسمه|اسمها) (.+)$", "any"),
            Triple("صديق", "^(?:صديق|صديقة) اسمه? (.+)$", "any")
        )
        for ((relation, pattern, _) in patterns) {
            val match = Regex(pattern).matchEntire(relationAndName) ?: continue
            val normalizedName = match.groupValues[1].trim().takeIf { it.isNotBlank() } ?: continue
            val displayName = Regex("(?:اسمها|اسمه)\\s+(.+)$")
                .find(raw.trim())?.groupValues?.get(1)?.trim()
                ?: normalizedName
            val name = displayName.replace(Regex("[^\\p{L}\\p{N}\\s'-]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (name.isBlank() || name.length > 42 || name.split(' ').size > 3) return null
            if (name.split(' ').any { it.length < 2 }) return null
            return NovaEnrollmentRequest(relation, name)
        }
        return null
    }
}

internal class NovaPeopleMemory(context: Context) : AutoCloseable {
    private val prefs = context.applicationContext.getSharedPreferences("nova_people_memory_v1", Context.MODE_PRIVATE)
    private val faceModel = NovaMobileFaceNet(context)

    @Synchronized
    fun count(): Int = readAll().size

    @Synchronized
    fun enroll(request: NovaEnrollmentRequest, faceCrop: Bitmap): NovaKnownPerson {
        require(!faceCrop.isRecycled && faceCrop.width >= 24 && faceCrop.height >= 24) {
            "A fresh, sufficiently large face crop is required."
        }
        val descriptor = extractDescriptor(faceCrop)
        val existing = readAll().firstOrNull {
            normalize(it.name) == normalize(request.name) && normalize(it.relation) == normalize(request.relation)
        }
        val profile = NovaKnownPerson(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            relation = request.relation,
            name = request.name,
            descriptor = descriptor,
            enrolledAtMillis = System.currentTimeMillis()
        )
        val all = readAll().filterNot { it.id == profile.id } + profile
        writeAll(all.takeLast(MAX_PROFILES))
        return profile
    }

    @Synchronized
    fun recognize(faceCrop: Bitmap): NovaPersonMatch? {
        if (faceCrop.isRecycled || faceCrop.width < 24 || faceCrop.height < 24) return null
        val descriptor = runCatching { extractDescriptor(faceCrop) }.getOrNull() ?: return null
        val scored = readAll()
            .filter { it.descriptor.size == descriptor.size }
            .map { it to if (faceModel.isAvailable && descriptor.size != NovaLbpDescriptor.SIZE) cosineDistance(it.descriptor, descriptor) else NovaLbpDescriptor.distance(it.descriptor, descriptor) }
            .filter { it.second.isFinite() }
            .sortedBy { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second
        // Intentionally conservative until a real-world held-out evaluation calibrates thresholds.
        if (best.second > MAX_ACCEPT_DISTANCE) return null
        if (second != null && second - best.second < MIN_MATCH_MARGIN) return null
        return NovaPersonMatch(best.first, best.second)
    }

    @Synchronized
    fun forgetAll() {
        prefs.edit().remove(KEY_PROFILES).commit()
    }

    private fun extractDescriptor(faceCrop: Bitmap): FloatArray =
        faceModel.extract(faceCrop) ?: NovaLbpDescriptor.extract(faceCrop)

    private fun cosineDistance(first: FloatArray, second: FloatArray): Float {
        if (first.size != second.size || first.isEmpty()) return Float.POSITIVE_INFINITY
        var dot = 0.0
        var a = 0.0
        var b = 0.0
        for (i in first.indices) {
            dot += first[i].toDouble() * second[i]
            a += first[i].toDouble() * first[i]
            b += second[i].toDouble() * second[i]
        }
        if (a <= 1e-12 || b <= 1e-12) return Float.POSITIVE_INFINITY
        return (1.0 - dot / kotlin.math.sqrt(a * b)).toFloat().coerceAtLeast(0f)
    }

    override fun close() {
        faceModel.close()
    }

    private fun readAll(): List<NovaKnownPerson> {
        val stored = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val json = NovaLocalCipher.decryptOrLegacy(stored) ?: return emptyList()
        val needsMigration = !stored.startsWith("enc:v1:")
        val people = runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val descriptorArray = obj.optJSONArray("descriptor") ?: continue
                    if (descriptorArray.length() !in 16..NovaLbpDescriptor.SIZE) continue
                    val descriptor = FloatArray(descriptorArray.length()) { index ->
                        descriptorArray.optDouble(index, Double.NaN).toFloat()
                    }
                    if (descriptor.any { !it.isFinite() }) continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val relation = obj.optString("relation").takeIf { it.isNotBlank() } ?: continue
                    add(NovaKnownPerson(id, relation, name, descriptor, obj.optLong("enrolledAtMillis")))
                }
            }
        }.getOrDefault(emptyList())
        // Migrate any legacy plaintext profile store to authenticated AES-GCM encryption.
        if (needsMigration && people.isNotEmpty()) writeAll(people)
        return people
    }

    private fun writeAll(people: List<NovaKnownPerson>) {
        val array = JSONArray()
        people.forEach { person ->
            val descriptor = JSONArray()
            person.descriptor.forEach { descriptor.put(it.toDouble()) }
            array.put(JSONObject()
                .put("id", person.id)
                .put("relation", person.relation)
                .put("name", person.name)
                .put("descriptor", descriptor)
                .put("enrolledAtMillis", person.enrolledAtMillis))
        }
        val encryptedJson = NovaLocalCipher.encrypt(array.toString())
        check(prefs.edit().putString(KEY_PROFILES, encryptedJson).commit()) {
            "Could not save Nova's local people memory."
        }
    }

    private fun normalize(value: String) = NovaEnrollmentParser.normalize(value)

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val MAX_PROFILES = 24
        private const val MAX_ACCEPT_DISTANCE = 0.42f
        private const val MIN_MATCH_MARGIN = 0.035f
    }
}

/** Small grayscale Local Binary Pattern descriptor: no downloaded or pretrained model required. */
internal object NovaLbpDescriptor {
    private const val SIDE = 64
    private const val GRID = 4
    private const val CELLS = GRID * GRID
    private const val BINS = 256
    const val SIZE = CELLS * BINS

    fun extract(bitmap: Bitmap): FloatArray {
        require(!bitmap.isRecycled && bitmap.width >= 8 && bitmap.height >= 8)
        val scaled = Bitmap.createScaledBitmap(bitmap, SIDE, SIDE, true)
        try {
            val gray = IntArray(SIDE * SIDE)
            for (y in 0 until SIDE) for (x in 0 until SIDE) {
                val pixel = scaled.getPixel(x, y)
                gray[y * SIDE + x] =
                    (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            }
            val out = FloatArray(SIZE)
            for (y in 1 until SIDE - 1) for (x in 1 until SIDE - 1) {
                val center = gray[y * SIDE + x]
                var code = 0
                if (gray[(y - 1) * SIDE + (x - 1)] >= center) code = code or 1
                if (gray[(y - 1) * SIDE + x] >= center) code = code or 2
                if (gray[(y - 1) * SIDE + (x + 1)] >= center) code = code or 4
                if (gray[y * SIDE + (x + 1)] >= center) code = code or 8
                if (gray[(y + 1) * SIDE + (x + 1)] >= center) code = code or 16
                if (gray[(y + 1) * SIDE + x] >= center) code = code or 32
                if (gray[(y + 1) * SIDE + (x - 1)] >= center) code = code or 64
                if (gray[y * SIDE + (x - 1)] >= center) code = code or 128
                val cellX = (x * GRID / SIDE).coerceIn(0, GRID - 1)
                val cellY = (y * GRID / SIDE).coerceIn(0, GRID - 1)
                out[(cellY * GRID + cellX) * BINS + code] += 1f
            }
            for (cell in 0 until CELLS) {
                val start = cell * BINS
                var total = 0f
                for (i in 0 until BINS) total += out[start + i]
                if (total > 0f) for (i in 0 until BINS) out[start + i] /= total
            }
            return out
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Mean per-cell chi-square distance, approximately 0 for identical crops. */
    fun distance(first: FloatArray, second: FloatArray): Float {
        if (first.size != SIZE || second.size != SIZE) return Float.POSITIVE_INFINITY
        var total = 0.0
        for (i in first.indices) {
            val a = first[i].coerceAtLeast(0f)
            val b = second[i].coerceAtLeast(0f)
            val denominator = a + b
            if (denominator > 1e-9f) {
                val delta = a - b
                total += (delta * delta / denominator).toDouble()
            }
        }
        return (total / (2.0 * CELLS)).toFloat().coerceAtLeast(0f)
    }
}
