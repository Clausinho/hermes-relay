package com.hermesandroid.relay.viewmodel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Runs both Gateway command entry points through the same result contract. */
internal class GatewaySlashCommandRunner(
    private val slashExec: suspend (String) -> Result<JsonObject>,
    private val commandDispatch: suspend (String, String?) -> Result<JsonObject>,
    private val canDispatch: (Throwable?) -> Boolean,
    private val showNotice: (String) -> Unit,
    private val submitPrompt: (display: String, message: String) -> Unit,
    private val prefill: (String) -> Unit,
) {
    suspend fun run(commandLine: String, depth: Int = 0) {
        if (depth > 2) {
            showNotice("Command alias loop detected: $commandLine")
            return
        }
        val name = commandLine.removePrefix("/").substringBefore(' ')
        val execution = slashExec(commandLine)
        if (execution.isSuccess) {
            handle(execution.getOrThrow(), commandLine, depth)
            return
        }
        val failure = execution.exceptionOrNull()
        if (!canDispatch(failure)) {
            showNotice("/$name failed: ${failure?.message ?: "unknown error"}")
            return
        }
        val arg = commandLine.substringAfter(' ', "").trim().takeIf { it.isNotBlank() }
        commandDispatch(name, arg).fold(
            onSuccess = { handle(it, commandLine, depth) },
            onFailure = { showNotice("/$name failed: ${it.message ?: "unknown error"}") },
        )
    }

    private suspend fun handle(result: JsonObject, commandLine: String, depth: Int) {
        when (result.stringValue("type")) {
            "send", "skill" -> {
                notices(result)
                val message = result.messageValue()
                if (message == null) {
                    showNotice("Command returned no prompt: $commandLine")
                } else {
                    submitPrompt(safeGatewayCommandDisplay(result, commandLine), message)
                }
            }
            "alias" -> {
                notices(result)
                result.stringValue("target")?.let { target ->
                    run(if (target.startsWith("/")) target else "/$target", depth + 1)
                }
            }
            "prefill" -> {
                notices(result)
                result.messageValue()?.let(prefill)
            }
            else -> {
                val output = result.stringValue("output") ?: "(no output)"
                showNotice(listOfNotNull(output, result.stringValue("warning")).joinToString("\n\n"))
            }
        }
    }

    private fun notices(result: JsonObject) {
        listOfNotNull(result.stringValue("notice"), result.stringValue("warning"))
            .joinToString("\n\n").takeIf { it.isNotBlank() }?.let(showNotice)
    }
}

internal fun safeGatewayCommandDisplay(result: JsonObject, literalInvocation: String): String {
    val literal = literalInvocation.trim().ifBlank { "Command completed." }
    return result.stringValue("display")?.take(MAX_GATEWAY_COMMAND_DISPLAY_CHARS)
        ?: literal.take(MAX_GATEWAY_COMMAND_DISPLAY_CHARS)
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.messageValue(): String? =
    (this["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private const val MAX_GATEWAY_COMMAND_DISPLAY_CHARS = 2_000
