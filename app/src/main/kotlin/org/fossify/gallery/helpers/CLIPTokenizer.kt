package org.fossify.gallery.helpers

import android.content.Context
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Kotlin port of OpenAI CLIP's `simple_tokenizer.py`. Reads
 * `assets/clip/vocab.json` and `assets/clip/merges.txt` (the standard
 * `openai/clip-vit-base-patch32` files) and produces the int32 token
 * sequence the MobileCLIP text encoder expects.
 *
 * Output is padded/truncated to [CONTEXT_LENGTH] tokens with `<|startoftext|>`
 * (49406) prepended and `<|endoftext|>` (49407) appended; pad token id is 0.
 */
class CLIPTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    private val byteEncoder: Map<Byte, Char>,
) {

    private val cache = HashMap<String, String>()

    private val pat = Regex(
        """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
        RegexOption.IGNORE_CASE,
    )

    fun encode(text: String): IntArray {
        val tokens = ArrayList<Int>(CONTEXT_LENGTH)
        tokens.add(SOT_ID)
        val cleaned = text.trim().replace(Regex("\\s+"), " ").lowercase()
        for (m in pat.findAll(cleaned)) {
            val token = m.value
            val bytes = token.toByteArray(Charsets.UTF_8)
            val mapped = buildString(bytes.size) {
                for (b in bytes) append(byteEncoder[b])
            }
            for (bpeTok in bpe(mapped).split(' ')) {
                val id = encoder[bpeTok] ?: continue
                tokens.add(id)
                if (tokens.size >= CONTEXT_LENGTH - 1) break
            }
            if (tokens.size >= CONTEXT_LENGTH - 1) break
        }
        tokens.add(EOT_ID)
        val out = IntArray(CONTEXT_LENGTH) // zero-padded by default
        for (i in tokens.indices) out[i] = tokens[i]
        return out
    }

    private fun bpe(token: String): String {
        cache[token]?.let { return it }
        if (token.isEmpty()) return token

        // Treat each character as a starting "symbol"; the very last symbol is
        // suffixed with "</w>" to mark end-of-word (the CLIP convention).
        var word = ArrayList<String>(token.length)
        for (i in 0 until token.length - 1) word.add(token[i].toString())
        word.add(token.last().toString() + "</w>")

        var pairs = getPairs(word)
        if (pairs.isEmpty()) {
            val res = token + "</w>"
            cache[token] = res
            return res
        }

        while (true) {
            // Pick the highest-priority (= lowest rank) merge in the current word.
            var bestRank = Int.MAX_VALUE
            var bestPair: Pair<String, String>? = null
            for (p in pairs) {
                val r = bpeRanks[p] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    bestPair = p
                }
            }
            if (bestPair == null) break

            val (first, second) = bestPair
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                val j = indexOfFrom(word, first, i)
                if (j < 0) {
                    while (i < word.size) merged.add(word[i++])
                    break
                }
                while (i < j) merged.add(word[i++])
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged.add(first + second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i += 1
                }
            }
            word = merged
            if (word.size == 1) break
            pairs = getPairs(word)
        }

        val joined = word.joinToString(" ")
        cache[token] = joined
        return joined
    }

    private fun indexOfFrom(list: List<String>, target: String, from: Int): Int {
        for (i in from until list.size) if (list[i] == target) return i
        return -1
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        if (word.size < 2) return emptySet()
        val out = HashSet<Pair<String, String>>(word.size - 1)
        for (i in 0 until word.size - 1) out.add(word[i] to word[i + 1])
        return out
    }

    companion object {
        const val CONTEXT_LENGTH = 77
        const val SOT_ID = 49406 // <|startoftext|>
        const val EOT_ID = 49407 // <|endoftext|>

        @Volatile
        private var INSTANCE: CLIPTokenizer? = null

        fun get(context: Context): CLIPTokenizer {
            INSTANCE?.let { return it }
            synchronized(CLIPTokenizer::class.java) {
                INSTANCE?.let { return it }
                val t = build(context)
                INSTANCE = t
                return t
            }
        }

        private fun build(context: Context): CLIPTokenizer {
            val assets = context.applicationContext.assets

            // vocab.json: HF format — { "<|endoftext|>": 49407, "of</w>": 539, ... }
            val encoder = HashMap<String, Int>(50_000)
            assets.open("clip/vocab.json").use { input ->
                val root = JsonParser.parseReader(InputStreamReader(input, Charsets.UTF_8))
                    .asJsonObject
                for ((k, v) in root.entrySet()) encoder[k] = v.asInt
            }

            // merges.txt: first line is the header "#version: 0.2", then one
            // BPE pair per line ("i n" → ("i","n") with rank = line index).
            val bpeRanks = HashMap<Pair<String, String>, Int>(50_000)
            assets.open("clip/merges.txt").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { br ->
                    var first = true
                    var rank = 0
                    while (true) {
                        val line = br.readLine() ?: break
                        if (first) { first = false; if (line.startsWith("#")) continue }
                        val parts = line.split(' ')
                        if (parts.size == 2) bpeRanks[parts[0] to parts[1]] = rank++
                    }
                }
            }

            return CLIPTokenizer(encoder, bpeRanks, bytesToUnicode())
        }

        /**
         * Builds CLIP's byte-to-unicode mapping: each of the 256 raw bytes
         * gets a printable unicode char so the BPE merges can operate on
         * Strings without losing information across UTF-8 boundaries.
         */
        private fun bytesToUnicode(): Map<Byte, Char> {
            val printable = HashSet<Int>()
            for (b in '!'.code..'~'.code) printable.add(b)
            for (b in '¡'.code..'¬'.code) printable.add(b)
            for (b in '®'.code..'ÿ'.code) printable.add(b)
            val map = HashMap<Byte, Char>(256)
            var n = 0
            for (b in 0..255) {
                val codepoint = if (b in printable) b else (256 + n++)
                map[b.toByte()] = codepoint.toChar()
            }
            return map
        }
    }
}
