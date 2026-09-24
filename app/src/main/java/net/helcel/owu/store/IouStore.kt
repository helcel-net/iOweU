package net.helcel.owu.store

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.IouJson
import net.helcel.owu.ledger.Metadata
import net.helcel.owu.ledger.Verdict
import net.helcel.owu.ledger.Verifier
import java.io.File

/** A promise kept ready to make: no chain, no signature. Handing one over
 *  mints a fresh OwU - new id, signed there and then - so one "1 Beer" here
 *  becomes as many beers as you hand out. Stays until deleted. */
@Serializable
data class Template(
    val id: String,
    val metadata: Metadata,
    @SerialName("created_at") val createdAt: Long,
)

/** Somebody you have met and named. The name is yours; the key is what makes
 *  it worth anything. With it, an OwU arriving through a third party still
 *  reads as "owed by Alice": the signature proves the key, this says whose. */
@Serializable
data class Contact(
    @SerialName("pub_key") val publicKey: String,
    val name: String,
)

/** A key that introduced itself on the air, with the name it gave. Not a
 *  [Contact]: nobody vouched for it, and it only makes a screen readable. */
@Serializable
data class Met(
    @SerialName("pub_key") val publicKey: String,
    val name: String,
)

/** What happened when a chain arrived from outside. */
sealed class Merge {
    object Added : Merge()
    object Extended : Merge()
    object Unchanged : Merge()

    /** Same OwU, different history: neither side is a prefix of the other. */
    data class Fork(val existing: Iou) : Merge()
    data class Invalid(val reason: String) : Merge()
}

/** Everything the app keeps: a JSON file per OwU and per template, plus the
 *  address book. Small enough to live in memory, written straight to disk. */
class IouStore(root: File) {
    private val iouDir = File(root, "ious").apply { mkdirs() }
    private val templateDir = File(root, "templates").apply { mkdirs() }
    private val contactsFile = File(root, "contacts.json")
    private val metFile = File(root, "met.json")

    private val _ious = MutableStateFlow(loadDir(iouDir, Iou.serializer()).associateBy { it.id })
    val ious: StateFlow<Map<String, Iou>> = _ious

    private val _templates = MutableStateFlow(loadDir(templateDir, Template.serializer()).associateBy { it.id })
    val templates: StateFlow<Map<String, Template>> = _templates

    private val _contacts = MutableStateFlow(loadContacts())
    val contacts: StateFlow<List<Contact>> = _contacts

    private val _met = MutableStateFlow(loadList(metFile, Met.serializer()))

    /** Names keys have given for themselves, for reading screens by. */
    val met: StateFlow<List<Met>> = _met

    // --- OwUs -------------------------------------------------------------

    /** Stores a chain we made ourselves, which [net.helcel.owu.ledger.Ledger] has already verified. */
    fun put(iou: Iou) {
        write(File(iouDir, "${iou.id}.json"), IouJson.encode(iou))
        _ious.update { it + (iou.id to iou) }
    }

    fun remove(id: String) {
        File(iouDir, "$id.json").delete()
        _ious.update { it - id }
    }

    /** Takes a chain from a peer. Only a valid extension of what we have, or
     *  one new to us, is stored; a fork is reported, not resolved, since only
     *  the debtor can decide it. */
    fun merge(incoming: Iou): Merge = compare(incoming).also { verdict ->
        when (verdict) {
            Merge.Added, Merge.Extended -> put(incoming)
            else -> {}
        }
    }

    /** What [merge] would make of [incoming], without keeping it: an OwU looked
     *  over before anyone agrees. A [Merge.Fork] means it and the copy you hold
     *  each carry signatures the other does not - someone signed twice. */
    fun compare(incoming: Iou): Merge {
        val verdict = Verifier.verify(incoming)
        if (verdict is Verdict.Invalid) return Merge.Invalid("block ${verdict.sequence}: ${verdict.reason}")
        val existing = _ious.value[incoming.id] ?: return Merge.Added
        val common = minOf(existing.chain.size, incoming.chain.size)
        if (existing.metadata != incoming.metadata || existing.chain.take(common) != incoming.chain.take(common)) {
            return Merge.Fork(existing)
        }
        if (incoming.chain.size <= existing.chain.size) return Merge.Unchanged
        return Merge.Extended
    }

    // --- templates ----------------------------------------------------------

    fun putTemplate(template: Template) {
        write(File(templateDir, "${template.id}.json"), json.encodeToString(Template.serializer(), template))
        _templates.update { it + (template.id to template) }
    }

    fun removeTemplate(id: String) {
        File(templateDir, "$id.json").delete()
        _templates.update { it - id }
    }

    // --- contacts ----------------------------------------------------------

    fun nameFor(publicKey: String): String? = _contacts.value.firstOrNull { it.publicKey == publicKey }?.name

    /** The name a key gave for itself, if we have heard it introduce itself. */
    fun metName(publicKey: String): String? = _met.value.firstOrNull { it.publicKey == publicKey }?.name

    /** Remembers what a key calls itself. Never touches what you have named it. */
    fun putMet(publicKey: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || metName(publicKey) == clean) return
        val list = _met.value.filter { it.publicKey != publicKey } + Met(publicKey, clean)
        write(metFile, json.encodeToString(ListSerializer(Met.serializer()), list))
        _met.value = list
    }

    fun putContact(contact: Contact) = saveContacts(
        _contacts.value.filter { it.publicKey != contact.publicKey } + contact
    )

    fun removeContact(publicKey: String) = saveContacts(_contacts.value.filter { it.publicKey != publicKey })

    private fun saveContacts(list: List<Contact>) {
        write(contactsFile, json.encodeToString(ListSerializer(Contact.serializer()), list))
        _contacts.value = list
    }

    private fun loadContacts(): List<Contact> = loadList(contactsFile, Contact.serializer())

    private fun <T> loadList(file: File, serializer: KSerializer<T>): List<T> =
        if (!file.exists()) emptyList() else try {
            json.decodeFromString(ListSerializer(serializer), file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "unreadable ${file.name}", e)
            emptyList()
        }

    // --- files -------------------------------------------------------------

    private fun <T> loadDir(dir: File, serializer: KSerializer<T>): List<T> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { f ->
            try {
                json.decodeFromString(serializer, f.readText())
            } catch (e: Exception) {
                // Leave it in place: a corrupt file is worth more to a
                // developer than a clean directory is to the app.
                Log.w(TAG, "skipping unreadable ${f.name}", e)
                null
            }
        }

    /** Write-then-rename, so a crash mid-write cannot leave a half file behind. */
    private fun write(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        private const val TAG = "IouStore"
        private val json get() = IouJson.json
    }
}
