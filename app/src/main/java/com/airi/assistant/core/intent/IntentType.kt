package com.airi.assistant.core.intent

/**
 * IntentType - Types of intents that can be recognized and executed
 */
enum class IntentType {
    // System actions
    GENERAL,
    CONVERSATION,
    CODE_ANALYSIS,
    DEBUG_ERROR,
    SUMMARIZE,
    SYSTEM_COMMAND,
    APP_CONTROL,
    SCREEN_ANALYSIS,
    BATTERY_DIAGNOSIS,
    
    // Accessibility actions
    CLICK,
    CLICK_FIRST,
    CLICK_INDEX,
    TYPE,
    BACK,
    SCROLL,
    NAVIGATE,
    
    // Unknown
    UNKNOWN
}
