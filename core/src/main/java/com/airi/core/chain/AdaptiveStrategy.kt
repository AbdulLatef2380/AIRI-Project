package com.airi.core.chain

sealed class AdaptiveStrategy {

    object DirectAction : AdaptiveStrategy()

    object ScrollAndRetry : AdaptiveStrategy()

    object WaitAndRecheck : AdaptiveStrategy()

    object FallbackPath : AdaptiveStrategy()
}
