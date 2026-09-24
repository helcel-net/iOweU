package net.helcel.owu.store

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.helcel.owu.BuildConfig
import net.helcel.owu.crypto.Keys
import net.helcel.owu.crypto.Identity
import net.helcel.owu.demo.Demo
import net.helcel.owu.helper.defaultPreferences

/**
 * The app's one identity and one store, opened once per process. [init] does
 * the Keystore work and so belongs off the main thread.
 */
object Repo {
    lateinit var store: IouStore
        private set
    lateinit var signer: Identity
        private set

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    val me: String get() = signer.publicKey

    @Synchronized
    fun init(context: Context) {
        if (_ready.value) return
        store = IouStore(context.filesDir)
        signer = Identity.load(context)
        if (BuildConfig.DEBUG) {
            Log.i("owu", "identity ${signer.publicKey}")
            // A debug install starts with something to look at, once. R8 drops
            // this and Demo with it from a release.
            if (Demo.wanted(store)) Demo.fill(store, signer)
        }
        _ready.value = true
    }

    /** How a key is shown: you, your name for it, its own, or its fingerprint. */
    fun nameOf(publicKey: String): String = when {
        publicKey == me -> "You"
        else -> store.nameFor(publicKey) ?: store.metName(publicKey) ?: Keys.fingerprint(publicKey)
    }

    /** Whether you have named this key. An OwU owed by a contact reads as that
     *  person's promise however it reached you; one owed by a key you never met
     *  is a stranger's, whatever the person handing it over calls them. */
    fun knows(publicKey: String): Boolean = publicKey == me || store.nameFor(publicKey) != null

    /** Becomes the identity from a backup. Everything signed from here on is that one. */
    @Synchronized
    fun adopt(identity: Identity) {
        signer = identity
    }

    fun myName(context: Context): String = defaultPreferences(context).getString(KEY_NAME, "") ?: ""

    fun setMyName(context: Context, name: String) {
        defaultPreferences(context).edit { putString(KEY_NAME, name) }
    }

    private const val KEY_NAME = "my_name"
}
