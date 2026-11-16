package com.airi.assistant.terminal;

interface ISandboxTerminalCallback {
    void onResult(String requestId, int status, String output, int exitCode, String reason);
    void onProbe(String requestId, int serviceUid, boolean uidSeparated, boolean appFilesDenied, boolean networkDenied, boolean limitsEnforced, String reason);
}
