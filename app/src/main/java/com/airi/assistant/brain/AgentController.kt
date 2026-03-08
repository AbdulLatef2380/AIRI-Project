package com.airi.assistant.brain

import android.util.Log
import com.airi.assistant.accessibility.AIRIAccessibilityService
import kotlinx.coroutines.*

object AgentController {

    private var running = false

    fun start() {

        if (running) return
        running = true

        CoroutineScope(Dispatchers.Default).launch {

            while (running) {

                try {

                    val service = AIRIAccessibilityService.instance ?: continue

                    val root = service.rootInActiveWindow ?: continue

                    // 1 observe
                    val screen = UIScanner.scan(root)

                    // 2 hash
                    val hash = ScreenHasher.hash(screen)

                    // 3 analyze
                    val intent = BrainManager.analyze(screen, hash)

                    // 4 plan
                    val plan = ActionPlanner.plan(intent)

                    // 5 execute
                    ActionExecutor.execute(plan)

                    // 6 learn
                    MemoryManager.learn(hash, plan)

                } catch (e: Exception) {

                    Log.e("AIRI", "Agent loop error", e)

                }

                delay(1500)
            }
        }
    }

    fun stop() {
        running = false
    }
}
