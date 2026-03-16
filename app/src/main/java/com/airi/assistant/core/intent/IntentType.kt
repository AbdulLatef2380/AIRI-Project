package com.airi.assistant.core.intent

enum class IntentType {
    GENERAL,
    CONVERSATION,
    CODE_ANALYSIS,
    DEBUG_ERROR,
    SUMMARIZE,
    SYSTEM_COMMAND,
    APP_CONTROL,
    SCREEN_ANALYSIS,
    BATTERY_DIAGNOSIS,
    
    // Accessibility Actions
    CLICK,
    CLICK_FIRST,
    CLICK_INDEX,
    TYPE,
    BACK,
    
    UNKNOWN
}
