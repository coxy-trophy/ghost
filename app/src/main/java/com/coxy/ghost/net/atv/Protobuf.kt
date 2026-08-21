package com.coxy.ghost.net.atv

import java.io.ByteArrayOutputStream

/**
 * Just enough protobuf to talk to a television.
 *
 * The Android TV remote protocol is protobuf over TLS, and this project has no protobuf runtime —
 * adding one for six message types, none of which has a repeated field of any depth, would be a
 * dependency and a codegen step to encode about forty bytes at a time.
 *
 * Only the wire types these messages actually use are implemented: varint (0) and
 * length-delimited (2). Anything else is skipped on read rather than failing, because a TV firmware
 * is free to add fields this code has never heard of and an unknown field is not an error.
 */
internal object Protobuf {

    /** Builds one message body. Fields must be written in any order the receiver accepts. */
    class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long): Writer = apply {
            if (value == 0L) return@apply     // proto3 default; omitted on the wire
            tag(field, 0)
            writeVarint(value)
        }

        fun varint(field: Int, value: Int): Writer = varint(field, value.toLong())

        fun bytes(field: Int, value: ByteArray): Writer = apply {
            if (value.isEmpty()) return@apply
            tag(field, 2)
            writeVarint(value.size.toLong())
            out.write(value)
        }

        fun string(field: Int, value: String): Writer = bytes(field, value.toByteArray())

        /** A nested message, written as a length-delimited field. */
        fun message(field: Int, build: Writer.() -> Unit): Writer =
            bytes(field, Writer().apply(build).build())

        fun build(): ByteArray = out.toByteArray()

        private fun tag(field: Int, wire: Int) = writeVarint(((field shl 3) or wire).toLong())

        private fun writeVarint(v: Long) {
            var value = v
            while (true) {
                val b = (value and 0x7F).toInt()
                value = value ushr 7
                if (value == 0L) {
                    out.write(b); return
                }
                out.write(b or 0x80)
            }
        }
    }

    /** One decoded field. Exactly one of [varint] / [bytes] is meaningful, per [wire]. */
    data class Field(val number: Int, val wire: Int, val varint: Long, val bytes: ByteArray?) {
        // Generated equals/hashCode on a ByteArray compares references, which makes any accidental
        // use of them silently wrong; these messages are compared by field number in practice.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = number * 31 + wire
    }

    /** Decode a message body into its fields, in the order they appear. */
    fun parse(data: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var i = 0
        while (i < data.size) {
            val (tag, afterTag) = readVarint(data, i) ?: return fields
            i = afterTag
            val number = (tag ushr 3).toInt()
            when (val wire = (tag and 0x7L).toInt()) {
                0 -> {
                    val (v, next) = readVarint(data, i) ?: return fields
                    fields += Field(number, wire, v, null)
                    i = next
                }
                2 -> {
                    val (len, next) = readVarint(data, i) ?: return fields
                    val end = next + len.toInt()
                    if (len < 0 || end > data.size) return fields
                    fields += Field(number, wire, 0, data.copyOfRange(next, end))
                    i = end
                }
                5 -> i += 4       // fixed32, unused here but has to be stepped over
                1 -> i += 8       // fixed64, likewise
                else -> return fields
            }
        }
        return fields
    }

    /** First field with [number], or null. */
    fun List<Field>.field(number: Int): Field? = firstOrNull { it.number == number }

    private fun readVarint(data: ByteArray, from: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = from
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    /**
     * Frame a message for the wire: a varint length, then the body.
     *
     * The protocol's own framing. Messages here are well under 128 bytes so the prefix is almost
     * always a single byte, but writing a real varint costs nothing and a device-info string long
     * enough to cross the boundary would otherwise corrupt every message after it.
     */
    fun frame(body: ByteArray): ByteArray {
        val prefix = Writer().apply { }.let {
            val out = ByteArrayOutputStream()
            var value = body.size.toLong()
            while (true) {
                val b = (value and 0x7F).toInt()
                value = value ushr 7
                if (value == 0L) { out.write(b); break }
                out.write(b or 0x80)
            }
            out.toByteArray()
        }
        return prefix + body
    }
}
