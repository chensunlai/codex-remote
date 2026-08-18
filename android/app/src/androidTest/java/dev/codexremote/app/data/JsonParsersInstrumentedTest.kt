package dev.codexremote.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonParsersInstrumentedTest {
    @Test
    fun threadRetainsTurnLifecycleAndParsedCommandActions() {
        val detail = parseThread(
            JSONObject(
                """
                {
                  "model": "gpt-fixture",
                  "reasoningEffort": "max",
                  "approvalPolicy": "on-request",
                  "sandbox": {
                    "type": "workspaceWrite",
                    "networkAccess": true,
                    "writableRoots": ["/workspace"]
                  },
                  "activePermissionProfile": {"id": ":workspace", "extends": null},
                  "thread": {
                    "id": "thread-1",
                    "name": "Fixture",
                    "cwd": "/workspace",
                    "status": {"type": "idle"},
                    "turns": [{
                      "id": "turn-1",
                      "status": "completed",
                      "startedAt": 100,
                      "completedAt": 103,
                      "durationMs": 3200,
                      "error": null,
                      "items": [{
                        "type": "commandExecution",
                        "id": "command-1",
                        "command": "rg TODO src",
                        "status": "completed",
                        "commandActions": [{
                          "type": "search",
                          "command": "rg TODO src",
                          "query": "TODO",
                          "path": "src"
                        }],
                        "aggregatedOutput": "src/App.kt:1: TODO",
                        "exitCode": 0,
                        "durationMs": 250
                      }]
                    }]
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("turn-1", detail.messages.single().turnId)
        assertEquals("search", detail.messages.single().commandActions.single().type)
        assertEquals("TODO", detail.messages.single().commandActions.single().query)
        assertEquals(100_000L, detail.turns.single().startedAtMs)
        assertEquals(103_000L, detail.turns.single().completedAtMs)
        assertEquals(3_200L, detail.turns.single().durationMs)
        assertEquals("gpt-fixture", detail.settings.model)
        assertEquals("max", detail.settings.effort)
        assertEquals(":workspace", detail.settings.permissionProfile)
        assertEquals("workspace-write", detail.settings.sandbox)
        assertTrue(detail.settings.networkAccess)
    }

    @Test
    fun permissionProfilesAndGoalUseNativeProtocolFields() {
        val profiles = parsePermissionProfiles(
            JSONObject(
                """
                {
                  "data": [
                    {"id": ":read-only", "description": "Read only", "allowed": true},
                    {"id": "blocked", "description": null, "allowed": false}
                  ],
                  "nextCursor": null
                }
                """.trimIndent(),
            ),
        )
        val goal = parseThreadGoal(
            JSONObject(
                """
                {
                  "threadId": "thread-1",
                  "objective": "Finish the fixture",
                  "status": "paused",
                  "tokenBudget": 50000,
                  "tokensUsed": 1200,
                  "timeUsedSeconds": 90,
                  "createdAt": 10,
                  "updatedAt": 20
                }
                """.trimIndent(),
            ),
        )

        assertEquals(2, profiles.size)
        assertEquals(":read-only", profiles.first().id)
        assertFalse(profiles.last().allowed)
        assertEquals("Finish the fixture", goal?.objective)
        assertEquals("paused", goal?.status)
        assertEquals(50_000L, goal?.tokenBudget)
        assertEquals(1_200L, goal?.tokensUsed)
    }

    @Test
    fun tokenUsageRequiresRealContextWindowNotification() {
        val parsed = parseThreadTokenUsage(
            JSONObject(
                """
                {
                  "threadId": "thread-1",
                  "turnId": "turn-1",
                  "tokenUsage": {
                    "total": {"totalTokens": 90000},
                    "last": {"totalTokens": 24000},
                    "modelContextWindow": 128000
                  }
                }
                """.trimIndent(),
            ),
        )
        val unavailable = parseThreadTokenUsage(
            JSONObject(
                """
                {"tokenUsage": {"last": {"totalTokens": 24000}, "modelContextWindow": null}}
                """.trimIndent(),
            ),
        )

        assertEquals(24_000L, parsed?.usedTokens)
        assertEquals(128_000L, parsed?.contextWindow)
        assertEquals(null, unavailable)
    }

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
