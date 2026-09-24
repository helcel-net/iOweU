package net.helcel.owu.ledger

import kotlinx.serialization.json.Json

/**
 * The transport and storage encoding. Only [net.helcel.owu.crypto.Canonical]
 * bytes are ever signed, so this can grow fields freely.
 */
object IouJson {
    val json = Json {
        classDiscriminator = "action"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(iou: Iou): String = json.encodeToString(Iou.serializer(), iou)
    fun decode(text: String): Iou = json.decodeFromString(Iou.serializer(), text)

    fun encode(agreement: ExchangeAgreement): String = json.encodeToString(ExchangeAgreement.serializer(), agreement)
    fun decodeAgreement(text: String): ExchangeAgreement = json.decodeFromString(ExchangeAgreement.serializer(), text)
}
