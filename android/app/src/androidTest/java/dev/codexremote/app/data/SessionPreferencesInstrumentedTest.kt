package dev.codexremote.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codexremote.app.model.NewSessionOptions
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionPreferencesInstrumentedTest {
    @Test
    fun retainsConfigurationButUsesTheUserSelectedDirectory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = SessionPreferences(context)
        val scope = "test-${UUID.randomUUID()}"
        val serviceId = UUID.randomUUID().toString()
        val expected = NewSessionOptions(
            cwd = "/workspace/project",
            model = "gpt-fixture",
            effort = "max",
            approvalPolicy = "on-request",
            sandbox = "workspace-write",
            networkAccess = true,
            permissionProfile = ":workspace",
        )
        val selectedDirectory = "/workspace/another-project"

        preferences.save(scope, serviceId, expected)

        assertEquals(
            expected.copy(cwd = selectedDirectory),
            preferences.load(scope, serviceId, selectedDirectory),
        )
    }
}
