package com.coxy.ghost.contacts

internal object ContactQuery {
    fun normalize(value: String): String? = value.trim().takeIf { it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl) }
}
internal data class ContactMatch(val id: Long, val name: String, val number: String)
internal data class ContactSnapshot(val status: String, val matches: List<ContactMatch> = emptyList())
