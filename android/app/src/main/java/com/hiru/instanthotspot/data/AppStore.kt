package com.hiru.instanthotspot.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

enum class DeviceRole { PROVIDER, CLIENT }
data class Peer(val id: String, val name: String)

/** Only a derived pairing key is stored, encrypted by a non-exportable Android Keystore key. */
class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("hiru", Context.MODE_PRIVATE)
    val installationId: UUID = prefs.getString("identity", null)?.let(UUID::fromString)
        ?: UUID.randomUUID().also { prefs.edit().putString("identity", it.toString()).commit() }
    var role: DeviceRole?
        get() = prefs.getString("role", null)?.let { runCatching { DeviceRole.valueOf(it) }.getOrNull() }
        set(value) { prefs.edit().putString("role", value?.name).apply() }
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) { prefs.edit().putBoolean("onboarded", value).apply() }
    var routineEnabled: Boolean
        get() = prefs.getBoolean("routine_enabled", false)
        set(value) { prefs.edit().putBoolean("routine_enabled", value).apply() }

    private fun vaultKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("hiru-pairing", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("hiru-pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun key(): ByteArray? = runCatching {
        val raw = prefs.getString("key", null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size >= 28)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, vaultKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size))
        }.also { require(it.size == 32) }
    }.getOrNull()

    fun saveKey(bytes: ByteArray) {
        require(bytes.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, vaultKey())
        val encrypted = cipher.iv + cipher.doFinal(bytes)
        check(prefs.edit().putString("key", Base64.encodeToString(encrypted, Base64.NO_WRAP)).remove("peers").commit())
    }
    fun peers(): List<Peer> = runCatching {
        val array = JSONArray(prefs.getString("peers", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map { Peer(it.getString("id"), it.getString("name")) }
    }.getOrDefault(emptyList())
    fun remember(id: UUID, name: String) = savePeers(peers().filterNot { it.id == id.toString() } + Peer(id.toString(), name.take(60)))
    fun forget(id: String) = savePeers(peers().filterNot { it.id == id })
    fun known(id: UUID) = peers().any { it.id == id.toString() }
    private fun savePeers(peers: List<Peer>) {
        val array = JSONArray()
        peers.takeLast(20).forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString("peers", array.toString()).apply()
    }
}
