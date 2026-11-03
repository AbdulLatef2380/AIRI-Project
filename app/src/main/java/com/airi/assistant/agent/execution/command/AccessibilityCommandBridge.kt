package com.airi.assistant.agent.execution.command

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import com.airi.assistant.accessibility.service.ScreenContextHolder
import com.airi.assistant.accessibility.security.AccessibilityActionGateway
import com.airi.assistant.agent.execution.context.ContextProvider
import com.airi.assistant.agent.execution.node.NodeActionExecutor
import com.airi.assistant.agent.execution.node.NodeScanner
import com.airi.assistant.agent.execution.validation.TemporalValidator

object AccessibilityCommandBridge {

    /** Must be called before obtaining or traversing the active node tree. */
    private fun gate(action: String, target: String = "", userApproved: Boolean = false): CommandResult? {
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")
        val pkg = try { service.rootInActiveWindow?.packageName?.toString().orEmpty() }
        catch (_: Exception) { "" }
        return gatePackage(pkg, action, target, userApproved)
    }

    /**
     * [userApproved] is a ONE-SHOT approval for exactly this call: it lifts only the
     * "needs confirmation" decision. Denied packages / unknown packages stay refused.
     */
    private fun gatePackage(
        packageName: String,
        action: String,
        target: String = "",
        userApproved: Boolean = false
    ): CommandResult? =
        when (val decision = AccessibilityActionGateway.authorize(packageName, action, target)) {
            AccessibilityActionGateway.Decision.Allowed -> null
            is AccessibilityActionGateway.Decision.Denied -> CommandResult(false, decision.reason)
            is AccessibilityActionGateway.Decision.NeedsConfirmation ->
                if (userApproved) null
                else CommandResult(
                    success = false,
                    message = decision.reason,
                    needsConfirmation = true,
                    confirmationLabel = target.ifBlank { action }
                )
        }

    fun launchApp(appName: String): CommandResult {
        gate("launch_app", appName)?.let { return it }
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        return try {
            val context = ContextProvider.getAppContext(service)
            val packageManager = context.packageManager

            val apps = packageManager.getInstalledApplications(0)
            val targetApp = apps.find { app ->
                packageManager.getApplicationLabel(app).toString()
                    .lowercase().contains(appName.lowercase())
            }

            if (targetApp != null) {
                val launchIntent = packageManager.getLaunchIntentForPackage(targetApp.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    CommandResult(true, "Launched ${targetApp.packageName}")
                } else {
                    CommandResult(false, "No launch intent for $appName")
                }
            } else {
                CommandResult(false, "App not found: $appName")
            }
        } catch (e: Exception) {
            CommandResult(false, "Failed to launch app: ${e.message}")
        }
    }

    fun search(query: String): CommandResult {
        gate("search", query)?.let { return it }
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        return try {
            val context = ContextProvider.getAppContext(service)
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(searchIntent)
            CommandResult(true, "Search initiated: $query")
        } catch (e: Exception) {
            CommandResult(false, "Search failed: ${e.message}")
        }
    }

    /**
     * [userApproved]: set ONLY by a caller that has just obtained explicit user approval for
     * this exact click (see AccessibilityExecutionEngine.actionApprover). Default is fail-closed.
     */
    suspend fun click(target: String, userApproved: Boolean = false): CommandResult {
        gate("click", target, userApproved)?.let { return it }
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        val root = service.rootInActiveWindow
            ?: return CommandResult(false, "No active window")
        gatePackage(root.packageName?.toString().orEmpty(), "click", target, userApproved)?.let { return it }

        val nodes = NodeScanner.collectAllNodes(root)

        val targetNode = nodes.find { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            text.contains(target.lowercase()) || desc.contains(target.lowercase())
        }

        // V1: judge the element that will ACTUALLY be clicked (its own text / description /
        // view id), not only the planner's target string or the user's wording.
        if (targetNode != null && !userApproved) {
            val label = listOfNotNull(
                targetNode.text?.toString(),
                targetNode.contentDescription?.toString(),
                targetNode.viewIdResourceName
            ).joinToString(" ")
            if (AccessibilityActionGateway.labelRequiresConfirmation(label)) {
                return CommandResult(
                    success = false,
                    message = "Explicit, request-bound user approval is required for this action.",
                    needsConfirmation = true,
                    confirmationLabel = label.take(80)
                )
            }
        }

        return if (targetNode != null) {
            val success = NodeActionExecutor.click(targetNode)
            if (success) {
                val confirmed = TemporalValidator.validateAction(service)
                CommandResult(confirmed, if (confirmed) "Clicked $target" else "Click executed but no UI change")
            } else {
                CommandResult(false, "Failed to click $target")
            }
        } else {
            CommandResult(false, "Element not found: $target")
        }
    }

    suspend fun typeText(text: String): CommandResult {
        gate("type_text")?.let { return it }
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        val root = service.rootInActiveWindow
            ?: return CommandResult(false, "No active window")
        gatePackage(root.packageName?.toString().orEmpty(), "type_text")?.let { return it }

        val nodes = NodeScanner.collectAllNodes(root)
        val editable = nodes.find { it.isEditable }
            ?: return CommandResult(false, "No editable field found")

        val success = NodeActionExecutor.typeText(editable, text)
        return CommandResult(success, if (success) "Typed: $text" else "Failed to type text")
    }

    fun performBack(): CommandResult {
        gate("go_back")?.let { return it }
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        return try {
            val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            CommandResult(success)
        } catch (e: Exception) {
            CommandResult(false, e.message)
        }
    }

    fun performHome(): CommandResult {
        gate("go_home")?.let { return it }
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        return try {
            val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            CommandResult(success, "Navigated home")
        } catch (e: Exception) {
            CommandResult(false, e.message)
        }
    }

    fun performRecents(): CommandResult {
        gate("open_recents")?.let { return it }
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        return try {
            val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            CommandResult(success, "Opened recents")
        } catch (e: Exception) {
            CommandResult(false, e.message)
        }
    }

    fun scrollUp(): CommandResult = performScroll("up")
    fun scrollDown(): CommandResult = performScroll("down")
    fun scrollLeft(): CommandResult = performScroll("left")
    fun scrollRight(): CommandResult = performScroll("right")

    private fun performScroll(direction: String): CommandResult {
        gate("scroll_$direction")?.let { return it }
        val service = ScreenContextHolder.serviceInstance
            ?: return CommandResult(false, "Accessibility not connected")

        val root = service.rootInActiveWindow
            ?: return CommandResult(false, "No active window")
        gatePackage(root.packageName?.toString().orEmpty(), "scroll_$direction")?.let { return it }

        val nodes = NodeScanner.collectAllNodes(root)
        val scrollable = nodes.find { it.isScrollable }
            ?: return CommandResult(false, "No scrollable element found")

        val action = when (direction) {
            "up" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "left" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "right" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> return CommandResult(false, "Unknown scroll direction: $direction")
        }

        return try {
            val success = scrollable.performAction(action)
            CommandResult(success, if (success) "Scrolled $direction" else "Scroll failed")
        } catch (e: Exception) {
            CommandResult(false, "Scroll failed: ${e.message}")
        }
    }
}
