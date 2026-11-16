package com.airi.assistant.terminal;

import com.airi.assistant.terminal.ISandboxTerminalCallback;

interface ISandboxTerminalService {
    void execute(String requestId, String sessionId, String messageId, String executionId, String actionId, String traceId, String command, long timeoutMs, long maxOutputBytes, boolean allowNetwork, ISandboxTerminalCallback callback);
    void probe(String requestId, String appMarkerPath, ISandboxTerminalCallback callback);
    void cancel(String requestId);
}
