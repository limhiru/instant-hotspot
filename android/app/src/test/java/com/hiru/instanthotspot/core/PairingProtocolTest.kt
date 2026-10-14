package com.hiru.instanthotspot.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PairingProtocolTest {
    private val server = UUID.randomUUID()
    private val client = UUID.randomUUID()
    private val key = ByteArray(32) { it.toByte() }
    private fun session() = AuthSession(1_000).apply { this.client = this@PairingProtocolTest.client }

    @Test fun codeMustHaveTenPrintableAsciiCharactersAndEveryRequiredClass() {
        assertTrue(PairingProtocol.validCode("A7k!P9z#Q2"))
        listOf("", "1234567890", "abcdefgh!1", "ABCDEFGH!1", "Abcdefghi1", "Abcdefgh! ", "가나다abcA!12", "A7k!P9z#Q22").forEach {
            assertFalse(it, PairingProtocol.validCode(it))
        }
    }
    @Test fun registrationCodeDerivesTheSameKeyOnBothDevices() {
        assertArrayEquals(PairingProtocol.deriveKey("A7k!P9z#Q2"), PairingProtocol.deriveKey("A7k!P9z#Q2"))
        assertFalse(PairingProtocol.matches(PairingProtocol.deriveKey("A7k!P9z#Q2"), PairingProtocol.deriveKey("A7k!P9z#Q3")))
    }
    @Test fun requestRequiresAuthenticationAndCanBeUsedOnlyOnce() {
        val s = session()
        val request = PairingProtocol.tag(key, "request", s.nonce, server, client)
        assertFalse(s.acceptRequest(key, server, request, 1_001))
        assertTrue(s.authenticate(key, server, PairingProtocol.tag(key, "client", s.nonce, server, client), 1_001))
        assertTrue(s.acceptRequest(key, server, request, 1_002))
        assertFalse(s.acceptRequest(key, server, request, 1_003))
    }
    @Test fun rejectsWrongKeyReflectionDifferentIdentityAndOldChallenge() {
        val s = session()
        assertFalse(s.authenticate(key, server, PairingProtocol.tag(ByteArray(32), "client", s.nonce, server, client), 1_001))
        assertFalse(s.authenticate(key, server, PairingProtocol.tag(key, "server", s.nonce, server, client), 1_001))
        assertFalse(s.authenticate(key, server, PairingProtocol.tag(key, "client", s.nonce, server, UUID.randomUUID()), 1_001))
        assertFalse(s.authenticate(key, server, PairingProtocol.tag(key, "client", PairingProtocol.nonce(), server, client), 1_001))
    }
    @Test fun authenticationAndRequestsExpireAtThirtySeconds() {
        val s = session()
        val proof = PairingProtocol.tag(key, "client", s.nonce, server, client)
        assertFalse(s.authenticate(key, server, proof, 31_000))
        assertTrue(s.authenticate(key, server, proof, 1_001))
        assertFalse(s.acceptRequest(key, server, PairingProtocol.tag(key, "request", s.nonce, server, client), 31_000))
    }
    @Test fun identitiesRoundTripAndFramesFitDefaultMtu() {
        assertEquals(client, PairingProtocol.idFrom(PairingProtocol.idBytes(client)))
        assertEquals(17, (byteArrayOf(PairingProtocol.AUTH) + PairingProtocol.tag(key, "client", PairingProtocol.nonce(), server, client)).size)
    }
}
