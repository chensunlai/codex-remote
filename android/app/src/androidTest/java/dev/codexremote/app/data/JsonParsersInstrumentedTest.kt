package dev.codexremote.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonParsersInstrumentedTest {
    @Test
    fun pendingRequestsRetainInteractiveProtocolFields() {
        val pending = parsePending(
            JSONArray(
                """
                [
                  {
                    "requestId": "input-1",
                    "serviceId": "service-1",
                    "method": "item/tool/requestUserInput",
                    "createdAt": "2026-08-16T00:00:00Z",
                    "params": {
                      "threadId": "thread-1",
                      "turnId": "turn-1",
                      "questions": [{
                        "id": "mode",
                        "header": "Mode",
                        "question": "Choose a mode",
                        "isOther": true,
                        "isSecret": false,
                        "options": [{"label": "Compact", "description": "Show summaries"}]
                      }]
                    }
                  },
                  {
                    "requestId": "permission-1",
                    "serviceId": "service-1",
                    "method": "permissions/request",
                    "params": {
                      "permissions": {
                        "network": {"enabled": true},
                        "fileSystem": {"read": ["/workspace"]}
                      }
                    }
                  },
                  {
                    "requestId": "tool-1",
                    "serviceId": "service-1",
                    "method": "mcp/elicitation/create",
                    "params": {
                      "mode": "form",
                      "serverName": "fixture",
                      "requestedSchema": {"type": "object"}
                    }
                  }
                ]
                """.trimIndent(),
            ),
        )

        assertEquals(3, pending.size)
        assertEquals("thread-1", pending[0].threadId)
        assertEquals("turn-1", pending[0].turnId)
        assertEquals("mode", pending[0].questions.single().id)
        assertTrue(pending[0].questions.single().isOther)
        assertFalse(pending[0].questions.single().isSecret)
        assertEquals("Compact", pending[0].questions.single().options.single().label)
        assertTrue(pending[1].permissionsJson.orEmpty().contains("network"))
        assertTrue(pending[1].permissionsJson.orEmpty().contains("fileSystem"))
        assertEquals("form", pending[2].elicitationMode)
        assertTrue(pending[2].paramsJson.contains("requestedSchema"))
    }
}
