package com.airi.assistant.agent.execution.context

import android.content.Context

object ContextProvider {
    fun getAppContext(context: Context): Context = context.applicationContext
}
