package com.airi.assistant.connector.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * DeviceAppsConnector — list and launch installed Android apps.
 *
 * Actions:
 *  - list_apps  → returns installed launchable app names + packages (max 50)
 *  - find_app   → searches by name (params["query"])
 *  - open_app   → launches by package name (params["package"])
 *  - open_url   → opens URL in default browser (params["url"])
 *
 * Category: LOCAL — no internet, no auth.
 * Integration: registered in [ConnectorBootstrap.installDefaults].
 */
class DeviceAppsConnector(private val appContext: Context) : Connector {

    override val id          = "device_apps"
    override val name        = "Device Apps"
    override val description = "List and launch apps installed on this device."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("apps", "launch", "open", "browser", "url")
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()
    override suspend fun connect()    = _state.value
    override suspend fun disconnect() {}

    override suspend fun execute(input: ConnectorInput): ConnectorOutput =
        withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            when (input.action) {

                "list_apps" -> {
                    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                        .map    { "${it.loadLabel(pm)} (${it.packageName})" }
                        .sorted()
                        .take(50)
                    ConnectorOutput.Success(
                        text = "Found ${apps.size} apps",
                        data = mapOf("count" to apps.size.toString(),
                                     "apps"  to apps.joinToString(", "))
                    )
                }

                "find_app" -> {
                    val query = input.params["query"]?.lowercase()
                        ?: return@withContext ConnectorOutput.Failure(
                            "missing_param", "Required param 'query' not provided")
                    val found = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter {
                            val label = it.loadLabel(pm).toString().lowercase()
                            label.contains(query) || it.packageName.lowercase().contains(query)
                        }
                        .take(10)
                        .map { "${it.loadLabel(pm)} (${it.packageName})" }
                    ConnectorOutput.Success(
                        text = if (found.isEmpty()) "No apps found for '$query'"
                               else "Found ${found.size}: ${found.first()}",
                        data = mapOf("count" to found.size.toString(),
                                     "results" to found.joinToString(", "))
                    )
                }

                "open_app" -> {
                    val pkg = input.params["package"]
                        ?: return@withContext ConnectorOutput.Failure(
                            "missing_param", "Required param 'package' not provided")
                    val intent = pm.getLaunchIntentForPackage(pkg)
                        ?: return@withContext ConnectorOutput.Failure(
                            "not_found", "App '$pkg' not installed or has no launcher")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                    ConnectorOutput.Success(text = "Launched $pkg",
                        data = mapOf("launched" to "true", "package" to pkg))
                }

                "open_url" -> {
                    val url = input.params["url"]
                        ?: return@withContext ConnectorOutput.Failure(
                            "missing_param", "Required param 'url' not provided")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                    ConnectorOutput.Success(text = "Opened $url",
                        data = mapOf("opened" to "true", "url" to url))
                }

                else -> ConnectorOutput.Failure("unknown_action",
                    "Unknown action '${input.action}'. Valid: list_apps, find_app, open_app, open_url")
            }
        }
}
