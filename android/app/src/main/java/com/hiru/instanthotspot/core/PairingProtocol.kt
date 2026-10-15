package com.hiru.instanthotspot.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** All characteristic values fit the default ATT payload of 20 bytes. */
object PairingProtocol {
    val SERVICE: UUID = UUID.fromString("442faa80-96ba-4c06-b311-0bb4290d1000")
    val CHALLENGE: UUID = UUID.fromString("442faa80-96ba-4c06-b311-0bb4290d1001")
    val IDENTITY: UUID = UUID.fromString("442faa80-96ba-4c06-b311-0bb4290d1002")
    val CONTROL: UUID = UUID.fromString("442faa80-96ba-4c06-b311-0bb4290d1003")
    val STATUS: UUID = UUID.fromString("442faa80-96ba-4c06-b311-0bb4290d1004")
    const val VERSION: Byte = 1
    const val AUTH: Byte = 1
    const val REQUEST: Byte = 2
    const val AUTHENTICATED: Byte = 1
    const val USER_ACTION_REQUIRED: Byte = 2
    const val REJECTED: Byte = 3
    private val random = SecureRandom()

    fun validCode(code: String): Boolean = code.length == 10 &&
        code.all { it.code in 33..126 } && code.any { it in 'A'..'Z' } &&
        code.any { it in 'a'..'z' } && code.any { it in '0'..'9' } &&
        code.any { !it.isLetterOrDigit() }

    fun deriveKey(code: String): ByteArray {
        require(validCode(code))
        val spec = PBEKeySpec(code.toCharArray(), "HiruInstantHotspot/v1".toByteArray(), 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun nonce(): ByteArray = ByteArray(16).also(random::nextBytes)
    fun idBytes(id: UUID): ByteArray = ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
    fun idFrom(bytes: ByteArray): UUID {
        require(bytes.size == 16)
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
    fun tag(key: ByteArray, purpose: String, nonce: ByteArray, server: UUID, client: UUID): ByteArray {
        require(nonce.size == 16)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update("Hiru/v1/$purpose".toByteArray(StandardCharsets.US_ASCII))
        mac.update(nonce)
        mac.update(idBytes(server))
        mac.update(idBytes(client))
        return mac.doFinal().copyOf(16)
    }
    fun matches(expected: ByteArray, actual: ByteArray) = MessageDigest.isEqual(expected, actual)
}

/** Monotonic times are supplied by the caller, so expiry and replay rules are testable. */
class AuthSession(val createdAt: Long, val nonce: ByteArray = PairingProtocol.nonce()) {
    var client: UUID? = null
    var authenticated = false
        private set
    var requestUsed = false
        private set
    fun expired(now: Long) = now - createdAt >= 30_000L
    fun authenticate(key: ByteArray, server: UUID, proof: ByteArray, now: Long): Boolean {
        val peer = client ?: return false
        if (expired(now) || authenticated) return false
        authenticated = PairingProtocol.matches(PairingProtocol.tag(key, "client", nonce, server, peer), proof)
        return authenticated
    }
    fun acceptRequest(key: ByteArray, server: UUID, proof: ByteArray, now: Long): Boolean {
        val peer = client ?: return false
        if (!authenticated || requestUsed || expired(now)) return false
        if (!PairingProtocol.matches(PairingProtocol.tag(key, "request", nonce, server, peer), proof)) return false
        requestUsed = true
        return true
    }
}
