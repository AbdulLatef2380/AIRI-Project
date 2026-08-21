# Sources for AIRI Paired Remote Control

## OWASP Mobile Application Security Cheat Sheet

Source: https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html

The design uses OWASP guidance to apply least privilege, secure-by-default configuration, authenticated and authorized backend operations, revocable short-lived session material, secure token storage, encrypted transport, and explicit protection for sensitive operations. The AIRI command allowlist therefore excludes arbitrary operating-system control and requires a paired session before dispatch.

## Android Security Checklist

Source: https://developer.android.com/privacy-and-security/security-tips

The Android controller design follows the official guidance to use internal storage for private app data, treat external input as untrusted, minimize permissions, use authenticated encrypted networking, avoid exposed local listening interfaces, and avoid logging sensitive user data. The planned controller must store session material through platform security facilities rather than ordinary preferences.

## OWASP MASTG

Source: https://mas.owasp.org/MASTG/

The verification plan maps pairing and control risks to storage, cryptography, authentication, network communication, platform interaction, logging, and privacy test domains. Future runtime acceptance must test revoked sessions, replay prevention, secure storage, encrypted transport, and consent for sensitive operations.
