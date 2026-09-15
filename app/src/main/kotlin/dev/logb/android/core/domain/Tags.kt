package dev.logb.android.core.domain

import dev.logb.android.core.network.LogbJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.pow

data class TagCount(val tag: String, val count: Int)

enum class TagError { EMPTY, TOO_LONG, TOO_MANY }

sealed interface AddResult {
    data class Added(val tags: List<String>) : AddResult
    data class Refused(val error: TagError) : AddResult
}

data class SplitResult(val tags: List<String>, val text: String, val error: TagError?)

/** Port of `logb/frontend/src/lib/tags.ts` (and `src/domain/tags.rs` for fold, count and JSON). */
object Tags {
    const val MAX_TAGS = 10
    const val MAX_TAG_CHARS = 32
    const val PALETTE_SIZE = 8

    private val marks = Regex("\\p{M}")

    /**
     * Not `Regex("(?U)\\s+")`: Android's on-device ICU-backed regex engine rejects the `(?U)`
     * inline flag (`PatternSyntaxException`), even though the JVM's own `java.util.regex` (and so
     * Robolectric, which runs unit tests on the host JVM) accepts it -- a class of bug unit tests
     * cannot catch. This explicit class matches the same characters on both engines: the web's
     * `/\s+/g` (JS `\s`, `tags.ts`) plus U+0085 (NEL), which Rust's `split_whitespace` also treats
     * as whitespace (`src/domain/tags.rs`).
     */
    private val spaces = Regex("[\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    /** Not the device locale: Turkish "INFO" must still fold to "info", as on the server. */
    fun fold(tag: String): String = marks.replace(Normalizer.normalize(tag, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    fun normalizeTag(raw: String): String = spaces.replace(raw.trim(), " ")

    private fun length(s: String) = s.codePointCount(0, s.length)

    fun addTag(tags: List<String>, raw: String): AddResult {
        val tag = normalizeTag(raw)
        if (tag.isEmpty()) return AddResult.Refused(TagError.EMPTY)
        if (length(tag) > MAX_TAG_CHARS) return AddResult.Refused(TagError.TOO_LONG)
        if (tags.any { fold(it) == fold(tag) }) return AddResult.Added(tags)
        if (tags.size >= MAX_TAGS) return AddResult.Refused(TagError.TOO_MANY)
        return AddResult.Added(tags + tag)
    }

    fun removeTag(tags: List<String>, tag: String): List<String> = tags.filter { it != tag }

    /** Every comma-terminated segment becomes a tag; a refused one stays in the text, ahead of what follows. */
    fun splitTyped(tags: List<String>, value: String): SplitResult {
        if (!value.contains(',')) return SplitResult(tags, value, null)
        val parts = value.split(',')
        val rest = parts.last()
        var current = tags
        var error: TagError? = null
        val failed = mutableListOf<String>()
        for (part in parts.dropLast(1)) {
            if (part.isBlank()) continue
            when (val r = addTag(current, part)) {
                is AddResult.Added -> current = r.tags
                is AddResult.Refused -> if (r.error != TagError.EMPTY) { failed += part; error = r.error }
            }
        }
        val text = (if (rest.isNotEmpty()) failed + rest else failed).joinToString(",")
        return SplitResult(current, text, error)
    }

    fun suggestTags(all: List<TagCount>, current: List<String>, text: String, limit: Int = 8): List<String> {
        val have = current.map(::fold).toSet()
        val q = fold(normalizeTag(text))
        val candidates = all.filter { fold(it.tag) !in have }
        if (q.isEmpty()) {
            val collator = Collator.getInstance(Locale.ROOT)
            return candidates.sortedWith(compareByDescending<TagCount> { it.count }.thenComparator { a, b -> collator.compare(a.tag, b.tag) }).take(limit).map { it.tag }
        }
        val prefix = candidates.filter { fold(it.tag).startsWith(q) }
        val contains = candidates.filter { !fold(it.tag).startsWith(q) && fold(it.tag).contains(q) }
        return (prefix + contains).take(limit).map { it.tag }
    }

    /** FNV-1a over the folded code points: the same slot on every device, nothing stored. */
    fun colorIndex(tag: String): Int {
        var hash = 0x811c9dc5L
        fold(tag).codePoints().forEach { cp ->
            hash = hash xor cp.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return (hash % PALETTE_SIZE).toInt()
    }

    fun carries(tags: List<String>, wanted: String): Boolean = fold(wanted).let { w -> tags.any { fold(it) == w } }

    /** `api::tags::count`: by folded tag; the spelling most rows use, ties alphabetical. */
    fun count(columns: List<String>): List<TagCount> {
        val byFold = linkedMapOf<String, MutableMap<String, Int>>()
        for (column in columns) for (tag in fromJson(column)) {
            val spellings = byFold.getOrPut(fold(tag)) { mutableMapOf() }
            spellings[tag] = (spellings[tag] ?: 0) + 1
        }
        return byFold.values.map { spellings ->
            val best = spellings.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first()
            TagCount(best.key, spellings.values.sum())
        }.sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag })
    }

    private val listSerializer = ListSerializer(String.serializer())

    fun toJson(tags: List<String>): String = LogbJson.encodeToString(listSerializer, tags)

    fun fromJson(text: String): List<String> = runCatching { LogbJson.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())

    /** WCAG 2.x contrast ratio between two ARGB colours. */
    fun contrastRatio(fg: Long, bg: Long): Double {
        fun lum(argb: Long): Double {
            val channels = listOf(16, 8, 0).map { shift -> ((argb shr shift) and 0xFF) / 255.0 }
                .map { c -> if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
        }
        val (hi, lo) = listOf(lum(fg), lum(bg)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
