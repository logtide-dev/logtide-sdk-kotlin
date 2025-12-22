@file:OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)

package dev.logward.sdk.middleware

import dev.logward.sdk.LogWardClient
import dev.logward.sdk.TraceIdElement
import dev.logward.sdk.models.LogWardClientOptions
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.util.*

/**
 * AttributeKey to access the LogWard client instance
 *
 * Use this to manually log messages from your routes:
 * ```kotlin
 * val client = call.application.attributes[LogWardClientKey]
 * client.info("my-service", "Custom log message")
 * ```
 */
val LogWardClientKey = AttributeKey<LogWardClient>("LogWardClient")

/**
 * AttributeKey for trace ID propagation in coroutines
 */
private val TraceIdAttributeKey = AttributeKey<String>("LogWardTraceId")

/**
 * Ktor plugin for automatic HTTP request/response logging
 *
 * Example usage:
 * ```kotlin
 * fun Application.module() {
 *     install(LogWardPlugin) {
 *         apiUrl = "http://localhost:8080"
 *         apiKey = "lp_your_key"
 *         serviceName = "ktor-app"
 *     }
 *
 *     // Access the client manually in your routes
 *     routing {
 *         get("/api/custom") {
 *             val client = call.application.attributes[LogWardClientKey]
 *             client.info("my-service", "Custom log message")
 *             call.respondText("OK")
 *         }
 *     }
 * }
 * ```
 */
class LogWardPluginConfig {
    var apiUrl: String = ""
    var apiKey: String = ""
    var serviceName: String = "ktor-app"
    var logRequests: Boolean = true
    var logResponses: Boolean = true
    var logErrors: Boolean = true
    var skipHealthCheck: Boolean = true
    var skipPaths: Set<String> = emptySet()
    
    // Forward all LogWardClientOptions
    var batchSize: Int = 100
    var flushInterval: kotlin.time.Duration = kotlin.time.Duration.parse("5s")
    var maxBufferSize: Int = 10000
    var enableMetrics: Boolean = true
    var debug: Boolean = false
    var globalMetadata: Map<String, Any> = emptyMap()

    internal fun toClientOptions() = LogWardClientOptions(
        apiUrl = apiUrl,
        apiKey = apiKey,
        batchSize = batchSize,
        flushInterval = flushInterval,
        maxBufferSize = maxBufferSize,
        enableMetrics = enableMetrics,
        debug = debug,
        globalMetadata = globalMetadata
    )
}

val LogWardPlugin = createApplicationPlugin(
    name = "LogWard",
    createConfiguration = ::LogWardPluginConfig
) {
    val config = pluginConfig

    // Log plugin installation
    println("╭────────────────────────────────────────────╮")
    println("│  LogWard Plugin Initialized                │")
    println("╰────────────────────────────────────────────╯")
    println("  Service Name: ${config.serviceName}")
    println("  API URL: ${config.apiUrl}")
    println("  Batch Size: ${config.batchSize}")
    println("  Flush Interval: ${config.flushInterval}")
    println("  Log Requests: ${config.logRequests}")
    println("  Log Responses: ${config.logResponses}")
    println("  Log Errors: ${config.logErrors}")
    println("  Skip Health Check: ${config.skipHealthCheck}")
    if (config.skipPaths.isNotEmpty()) {
        println("  Skip Paths: ${config.skipPaths.joinToString(", ")}")
    }
    println()

    val client = LogWardClient(config.toClientOptions())
    println("✓ LogWard client created and ready")
    println("✓ Access client manually via: call.application.attributes[LogWardClientKey]")
    println()

    // Store client in application attributes for manual access
    application.attributes.put(LogWardClientKey, client)

    onCall { call ->
        val startTime = System.currentTimeMillis()
        val path = call.request.uri

        // Skip health checks and specified paths
        if (shouldSkip(path, config)) {
            return@onCall
        }

        // Extract or generate trace ID (always have one for coroutine propagation)
        val traceId = call.request.headers["X-Trace-ID"]
            ?: UUID.randomUUID().toString()

        // Store trace ID for coroutine propagation
        call.attributes.put(TraceIdAttributeKey, traceId)

        // Set ThreadLocal for backwards compatibility with non-suspend code
        client.setTraceId(traceId)

        // Log request
        if (config.logRequests) {
            client.info(
                config.serviceName,
                "Request received",
                mapOf(
                    "method" to call.request.httpMethod.value,
                    "path" to path,
                    "remoteHost" to call.request.local.remoteHost,
                    "traceId" to traceId
                )
            )
        }

        // Store start time for response logging
        call.attributes.put(StartTimeKey, startTime)
    }

    // Intercept the call pipeline to propagate trace ID in coroutine context
    // This ensures all coroutines in the request handler have access to the trace ID
    application.intercept(ApplicationCallPipeline.Call) {
        val traceId = call.attributes.getOrNull(TraceIdAttributeKey)
        if (traceId != null) {
            // Wrap the entire call execution with TraceIdElement
            // This propagates the trace ID to all child coroutines
            withContext(TraceIdElement(traceId)) {
                proceed()
            }
        } else {
            proceed()
        }
    }

    onCallRespond { call ->
        val path = call.request.uri

        if (shouldSkip(path, config)) {
            return@onCallRespond
        }

        val startTime = call.attributes.getOrNull(StartTimeKey)
        val duration = startTime?.let { System.currentTimeMillis() - it }
        val traceId = call.attributes.getOrNull(TraceIdAttributeKey)

        // Log response
        if (config.logResponses) {
            val statusValue = call.response.status()?.value
            val metadata = mutableMapOf<String, Any>(
                "method" to call.request.httpMethod.value,
                "path" to path,
                "status" to (statusValue ?: 0),
                "duration" to (duration ?: 0L)
            )
            if (traceId != null) {
                metadata["traceId"] = traceId
            }
            client.info(config.serviceName, "Response sent", metadata)
        }

        // Clear trace ID (ThreadLocal cleanup)
        client.setTraceId(null)
    }
}

private val StartTimeKey = AttributeKey<Long>("LogWardStartTime")

private fun shouldSkip(path: String, config: LogWardPluginConfig): Boolean {
    if (config.skipHealthCheck && path == "/health") {
        return true
    }
    return path in config.skipPaths
}
