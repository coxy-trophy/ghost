package com.coxy.ghost.memory

import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * A self-contained BERT WordPiece tokenizer for the on-device MiniLM-L6-v2 embedder (Phase 6.1).
 *
 * The MiniLM TFLite model takes pre-tokenised `input_ids` + `attention_mask` (int32, padded to 128)
 * — it does not tokenise internally — so we reproduce the `bert-base-uncased` tokenisation that
 * MiniLM was distilled from: lowercase → NFD accent-strip → split on whitespace + punctuation →
 * greedy longest-match WordPiece with `##` continuation subwords. The vocab is bundled as the
 * `vocab.txt` asset (line number = token id).
 *
 * This is deliberately a faithful-but-not-byte-perfect reimplementation: semantic recall only needs
 * the embeddings to be close, and minor tokenisation differences wash out in cosine similarity.
 * No third-party tokenizer dependency — pure Kotlin, loaded once.
 *
 * Thread-safe: [encode] only reads the immutable vocab map + the precomputed special-token ids.
 */
class BertTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val padId: Int,
    private val unkId: Int,
    private val clsId: Int,
    private val sepId: Int
) {
    fun encode(text: String, maxLen: Int = MAX_LEN): Tokenized {
        val tokens = tokenize(text)
        // [CLS] + tokens (truncated to leave room for [CLS]/[SEP]) + [SEP], then pad to maxLen.
        val body = tokens.take(maxLen - 2)
        val ids = IntArray(maxLen) { padId }
        val mask = IntArray(maxLen)
        ids[0] = clsId; mask[0] = 1
        body.forEachIndexed { i, t -> ids[i + 1] = vocab[t] ?: unkId; mask[i + 1] = 1 }
        ids[body.size + 1] = sepId; mask[body.size + 1] = 1
        return Tokenized(ids, mask)
    }

    private fun tokenize(text: String): List<String> {
        val cleaned = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
        val out = mutableListOf<String>()
        for (raw in cleaned.split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            for (tok in splitPunctuation(raw)) {
                if (tok.isEmpty()) continue
                val subs = wordpiece(tok)
                if (subs == null) out.add(UNK_TOKEN) else out.addAll(subs)
            }
        }
        return out
    }

    /** Split a whitespace token into runs of alphanumerics and individual punctuation chars. */
    private fun splitPunctuation(token: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in token) {
            if (ch.isLetterOrDigit()) {
                buf.append(ch)
            } else {
                if (buf.isNotEmpty()) { out += buf.toString(); buf.clear() }
                if (!ch.isWhitespace()) out += ch.toString()
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    /** Greedy longest-match WordPiece; returns the subword list, or null if any piece is OOV. */
    private fun wordpiece(token: String): List<String>? {
        if (token.length > MAX_CHARS_PER_WORD) return null
        if (token.length == 1 && !token[0].isLetterOrDigit()) {
            // A standalone punctuation char — emit only if it's in the vocab, else UNK.
            return if (token in vocab) listOf(token) else null
        }
        val subs = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found: String? = null
            while (start < end) {
                val sub = if (start == 0) token.substring(start, end) else "##" + token.substring(start, end)
                if (sub in vocab) { found = sub; break }
                end--
            }
            if (found == null) return null
            subs += found
            start = end
        }
        return subs
    }

    data class Tokenized(val inputIds: IntArray, val attentionMask: IntArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = inputIds.contentHashCode()
    }

    companion object {
        private const val MAX_LEN = 128
        private const val MAX_CHARS_PER_WORD = 100
        private const val UNK_TOKEN = "[UNK]"
        private const val CLS_TOKEN = "[CLS]"
        private const val SEP_TOKEN = "[SEP]"
        private const val PAD_TOKEN = "[PAD]"

        fun load(vocabReader: BufferedReader): BertTokenizer {
            val vocab = HashMap<String, Int>(60000)
            var id = 0
            vocabReader.lineSequence().forEach { line ->
                // vocab.txt lines are "<token>\n" (no trailing id); line number is the id.
                val tok = line.trimEnd('\r', '\n')
                if (tok.isNotEmpty()) vocab[tok] = id
                id++
            }
            fun idOf(t: String, fallback: Int) = vocab[t] ?: fallback
            return BertTokenizer(
                vocab,
                padId = idOf(PAD_TOKEN, 0),
                unkId = idOf(UNK_TOKEN, 100),
                clsId = idOf(CLS_TOKEN, 101),
                sepId = idOf(SEP_TOKEN, 102)
            )
        }
    }
}