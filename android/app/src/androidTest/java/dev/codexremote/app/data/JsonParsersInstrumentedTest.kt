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
    fun sessionRetainsGatewayOccupancyState() {
        val session = parseSession(
            JSONObject(
                """{"id":"thread-1","preview":"fixture","locked":true}""",
            ),
        )

        assertTrue(session.locked)
    }

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
                  "collaborationMode": {
                    "mode": "plan",
                    "settings": {
                      "model": "gpt-fixture",
                      "reasoning_effort": "medium",
                      "developer_instructions": null
                    }
                  },
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
        assertEquals("plan", detail.settings.collaborationMode)
    }

    @Test
    fun composerCapabilitiesUseNativeProtocolFields() {
        val modes = parseCollaborationModes(
            JSONObject(
                """
                {
                  "data": [
                    {"name": "Plan", "mode": "plan", "model": null, "reasoning_effort": "medium"},
                    {"name": "Default", "mode": "default", "model": null, "reasoning_effort": null}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val files = parseFileSearchResults(
            JSONObject(
                """
                {
                  "files": [
                    {
                      "root": "/workspace",
                      "path": "src/App.kt",
                      "match_type": "file",
                      "file_name": "App.kt"
                    },
                    {
                      "root": "/workspace",
                      "path": "docs",
                      "match_type": "directory",
                      "file_name": "docs"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val limits = parseRateLimits(
            JSONObject(
                """
                {
                  "rateLimits": {
                    "primary": {"usedPercent": 25.5, "windowDurationMins": 300, "resetsAt": 1000},
                    "secondary": null,
                    "planType": "pro"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("plan", "default"), modes.map { it.mode })
        assertEquals("medium", modes.first().effort)
        assertEquals("/workspace/src/App.kt", files.first().path)
        assertEquals("DIRECTORY", files.last().type.name)
        assertEquals(25.5, limits?.primary?.usedPercent ?: 0.0, 0.001)
        assertEquals("pro", limits?.planType)
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
