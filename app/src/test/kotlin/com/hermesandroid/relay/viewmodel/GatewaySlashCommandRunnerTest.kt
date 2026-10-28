package com.hermesandroid.relay.viewmodel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class GatewaySlashCommandRunnerTest {
    private class DispatchRequired : Exception("use command.dispatch")

    private class Harness {
        var execution: Result<JsonObject> = Result.success(buildJsonObject { put("output", "done") })
        var dispatch: Result<JsonObject> = execution
        val executions = mutableListOf<String>()
        val dispatches = mutableListOf<Pair<String, String?>>()
        val notices = mutableListOf<String>()
        val prompts = mutableListOf<Pair<String, String>>()
        val prefills = mutableListOf<String>()
        val runner = GatewaySlashCommandRunner(
            slashExec = { executions += it; execution },
            commandDispatch = { name, arg -> dispatches += name to arg; dispatch },
            canDispatch = { it is DispatchRequired },
            showNotice = { notices += it },
            submitPrompt = { display, message -> prompts += display to message },
            prefill = { prefills += it },
        )
    }

    @Test
    fun successfulSlashGoalSubmitsKickoffOnceWithoutDispatch() {
        val harness = Harness()
        harness.execution = Result.success(buildJsonObject {
            put("type", "send")
            put("display", "/goal ship the cache")
            put("message", "  Expanded goal kickoff\nKeep this exact body.\n")
            put("notice", "Goal set")
        })

        runImmediately { harness.runner.run("/goal ship the cache") }

        assertEquals(listOf("/goal ship the cache"), harness.executions)
        assertTrue(harness.dispatches.isEmpty())
        assertEquals(listOf("/goal ship the cache" to "  Expanded goal kickoff\nKeep this exact body.\n"), harness.prompts)
        assertEquals(listOf("Goal set"), harness.notices)
        assertFalse(harness.notices.any { it.contains("Expanded goal") || it == "(no output)" })
    }

    @Test
    fun bothEntryPointsSubmitSendAndSkillDirectivesWithLiteralDisplay() {
        for (fallback in listOf(false, true)) {
            for (type in listOf("send", "skill")) {
                val harness = Harness()
                val directive = buildJsonObject {
                    put("type", type)
                    put("message", "private expanded instruction")
                }
                harness.execution = if (fallback) Result.failure(DispatchRequired()) else Result.success(directive)
                harness.dispatch = Result.success(directive)

                runImmediately { harness.runner.run("/codex review") }

                assertEquals(listOf("/codex review" to "private expanded instruction"), harness.prompts)
                assertEquals(if (fallback) listOf("codex" to "review") else emptyList<Pair<String, String?>>(), harness.dispatches)
                assertTrue(harness.notices.isEmpty())
            }
        }
    }

    @Test
    fun refusalDoesNotDispatchOrSubmitUnlessTheProtocolAllowsFallback() {
        val harness = Harness()
        harness.execution = Result.failure(Exception("permission denied"))

        runImmediately { harness.runner.run("/goal make a change") }

        assertTrue(harness.dispatches.isEmpty())
        assertTrue(harness.prompts.isEmpty())
        assertEquals(listOf("/goal failed: permission denied"), harness.notices)
    }

    @Test
    fun fallbackFailureIsReportedWithoutASecondAttempt() {
        val harness = Harness()
        harness.execution = Result.failure(DispatchRequired())
        harness.dispatch = Result.failure(Exception("unsupported command"))

        runImmediately { harness.runner.run("/goal resume") }

        assertEquals(listOf("goal" to "resume"), harness.dispatches)
        assertTrue(harness.prompts.isEmpty())
        assertEquals(listOf("/goal failed: unsupported command"), harness.notices)
    }

    @Test
    fun successfulAliasUsesTheSameDirectiveHandlingAndBoundsRecursion() {
        val harness = Harness()
        harness.execution = Result.success(buildJsonObject { put("type", "alias"); put("target", "again") })

        runImmediately { harness.runner.run("/again") }

        assertEquals(listOf("/again", "/again", "/again"), harness.executions)
        assertTrue(harness.dispatches.isEmpty())
        assertTrue(harness.prompts.isEmpty())
        assertEquals(listOf("Command alias loop detected: /again"), harness.notices)
    }

    @Test
    fun prefillDoesNotSubmitAndPreservesNoticeAndWarning() {
        val harness = Harness()
        harness.execution = Result.success(buildJsonObject {
            put("type", "prefill")
            put("message", "  draft\n")
            put("notice", "Review the draft")
            put("warning", "Needs a decision")
        })

        runImmediately { harness.runner.run("/draft") }

        assertEquals(listOf("  draft\n"), harness.prefills)
        assertEquals(listOf("Review the draft\n\nNeeds a decision"), harness.notices)
        assertTrue(harness.prompts.isEmpty())
    }

    @Test
    fun legacyUntypedOutputAndWarningRemainVisible() {
        val harness = Harness()
        harness.execution = Result.success(buildJsonObject { put("output", "Status"); put("warning", "Deferred") })

        runImmediately { harness.runner.run("/status") }

        assertEquals(listOf("Status\n\nDeferred"), harness.notices)
        assertTrue(harness.prompts.isEmpty())
    }

    @Test
    fun missingDirectiveBodyCannotProduceAnEmptyOrLiteralPrompt() {
        val harness = Harness()
        harness.execution = Result.success(buildJsonObject { put("type", "send"); put("notice", "Goal set") })

        runImmediately { harness.runner.run("/goal work") }

        assertTrue(harness.prompts.isEmpty())
        assertEquals(listOf("Goal set", "Command returned no prompt: /goal work"), harness.notices)
    }

    private fun runImmediately(block: suspend () -> Unit) {
        var completion: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { completion = result }
        })
        checkNotNull(completion) { "Fixture callbacks must complete synchronously" }.getOrThrow()
    }
}
