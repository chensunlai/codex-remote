package dev.codexremote.app.data

import dev.codexremote.app.model.GatewayConfig
import org.junit.Assert.*
import org.junit.Test

class GatewayEndpointsTest {
    @Test fun oldConfigurationsRemainHttpsOnlyAndIgnoreExtraAddresses() {
        val config = GatewayConfig("https://public.example/", "key", endpoints = listOf("http://192.168.1.2:6767"))
        assertEquals(listOf("https://public.example"), gatewayEndpoints(config))
        assertTrue(runCatching { gatewayEndpoints(GatewayConfig("http://192.168.1.2", "key")) }.isFailure)
    }

    @Test fun configuredOrderIsPreservedAndPrimaryIsTheLastFallback() {
        val config = GatewayConfig("https://public.example", "key", allowHttp = true, multipleEndpoints = true,
            endpoints = listOf("http://192.168.1.2:6767/", "https://vpn.example/prefix/", "http://192.168.1.2:6767"))
        assertEquals(listOf("http://192.168.1.2:6767", "https://vpn.example/prefix", "https://public.example"), gatewayEndpoints(config))
        assertEquals(listOf("https://vpn.example/prefix", "https://public.example"), gatewayEndpoints(config.copy(allowHttp = false)))
        assertEquals(listOf("https://public.example"), gatewayEndpoints(config.copy(multipleEndpoints = false)))
    }

    @Test fun acceptsIpv6AndRejectsAmbiguousCredentialOrQueryAddresses() {
        assertEquals("http://[::1]:6767", normalizeGatewayUrl("http://[::1]:6767/"))
        assertEquals("https://example.com", normalizeGatewayUrl("example.com"))
        listOf("https://user:password@example.com", "https://example.com?key=secret", "https://example.com#path", "ftp://example.com")
            .forEach { assertTrue(it, runCatching { normalizeGatewayUrl(it) }.isFailure) }
    }
}
