package dev.codexremote.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowConnectionRegistryTest {
    @Test fun changingGatewayPausesAndForgetsOldConnections() {
        val registry = WindowConnectionRegistry()
        val old = mutableListOf<Boolean>()
        val current = mutableListOf<Boolean>()
        registry.register("window", old::add)
        registry.setSuspended("window", true)
        registry.clear()
        registry.register("window", current::add)
        assertEquals(listOf(false, true, true), old)
        assertEquals(listOf(false), current)
    }

    @Test fun successfulStopNeverResumesTheOldConnection() {
        val registry = WindowConnectionRegistry()
        val old = mutableListOf<Boolean>()
        val current = mutableListOf<Boolean>()
        registry.register("window", old::add)
        registry.setSuspended("window", true)
        registry.invalidate("window")
        registry.setSuspended("window", false)
        registry.register("window", current::add)
        assertEquals(listOf(false, true), old)
        assertEquals(listOf(false), current)
    }

    @Test fun failedOperationResumesTheSameConnection() {
        val registry = WindowConnectionRegistry()
        val events = mutableListOf<Boolean>()
        registry.register("window", events::add)
        registry.setSuspended("window", true)
        registry.setSuspended("window", false)
        assertEquals(listOf(false, true, false), events)
    }

    @Test fun mountingDuringManagementIsSuspendedAndOtherWindowsRemainConnected() {
        val registry = WindowConnectionRegistry()
        val current = mutableListOf<Boolean>()
        val other = mutableListOf<Boolean>()
        registry.register("other", other::add)
        registry.setSuspended("window", true)
        registry.register("window", current::add)
        registry.setSuspended("window", false)
        assertEquals(listOf(true, false), current)
        assertEquals(listOf(false), other)
    }

    @Test fun disposingAnOldRegistrationDoesNotUnregisterItsReplacement() {
        val registry = WindowConnectionRegistry()
        val old = mutableListOf<Boolean>()
        val replacement = mutableListOf<Boolean>()
        val disposeOld = registry.register("window", old::add)
        registry.register("window", replacement::add)
        disposeOld()
        registry.setSuspended("window", true)
        assertEquals(listOf(false), old)
        assertEquals(listOf(false, true), replacement)
    }
}
