package net.helcel.owu.helper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.helcel.owu.crypto.Keys
import net.helcel.owu.ledger.IouJson

/** What goes in the identity QR code: who you are and how to address you. */
@Serializable
data class IdentityCard(
    @SerialName("v") val version: Int = 1,
    val name: String,
    val key: String,
) {
    fun encode(): String = IouJson.json.encodeToString(serializer(), this)

    companion object {
        /** Null unless [text] is a card carrying a usable key. */
        fun decode(text: String): IdentityCard? = try {
            IouJson.json.decodeFromString(serializer(), text).takeIf { Keys.decode(it.key) != null }
        } catch (e: Exception) {
            null
        }
    }
}
