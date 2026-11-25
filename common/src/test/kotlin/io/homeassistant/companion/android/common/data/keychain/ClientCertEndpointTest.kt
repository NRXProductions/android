package io.homeassistant.companion.android.common.data.keychain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ClientCertEndpointTest {

    @Test
    fun `Given the same host in a different case when comparing then the endpoints are equal`() {
        val endpoint = ClientCertEndpoint("Home.Example.com", 443)
        val other = ClientCertEndpoint("home.example.com", 443)

        assertEquals(endpoint, other)
        assertEquals(endpoint.hashCode(), other.hashCode())
    }

    @Test
    fun `Given the same host on another port when comparing then the endpoints are different`() {
        assertNotEquals(ClientCertEndpoint("home.example.com", 443), ClientCertEndpoint("home.example.com", 8123))
    }

    @Test
    fun `Given another host on the same port when comparing then the endpoints are different`() {
        assertNotEquals(ClientCertEndpoint("a.example.com", 443), ClientCertEndpoint("b.example.com", 443))
    }

    @Test
    fun `Given a bracketed IPv6 host when comparing with its bare form then the endpoints are equal`() {
        assertEquals(ClientCertEndpoint("::1", 8123), ClientCertEndpoint("[::1]", 8123))
    }

    @ParameterizedTest
    @ValueSource(strings = ["home.example.com:443", "192.168.1.2:8123", "::1:8123", "fe80::1:443"])
    fun `Given an endpoint when parsing its string form then the same endpoint is returned`(value: String) {
        val endpoint = ClientCertEndpoint.parse(value)

        assertEquals(value, endpoint?.toString())
        assertEquals(endpoint, ClientCertEndpoint.parse(endpoint.toString()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "home.example.com", "home.example.com:", ":443", "home.example.com:https"])
    fun `Given a value which is not an endpoint when parsing then null is returned`(value: String) {
        assertNull(ClientCertEndpoint.parse(value))
    }
}
