# AIRI UX and Storage Implementation Notes

## Scope

This change unifies the two conversation-history entry points without removing the existing chat-side panel. The clock action now renders the same `HistorySessionItem` used by the bottom `Chat` destination, so selection loads the same Room-backed `ChatSessionSummary` and returns to the active chat. The full history destination retains long-press deletion, while the side panel remains a reversible selection surface.

The chat input is organized as two directional groups. Attachment and microphone actions stay on the start side. Connector, expand, and the primary live-chat/send action stay on the end side. The layout relies on Compose start/end positioning, so it mirrors correctly for Arabic and other right-to-left locales. While the user types or attaches content, live chat changes to send, but attachment, microphone, and connector actions remain available.

## Storage behavior

The Library screen no longer presents a static empty state. It scans the existing `MediaLibrary` app-specific directories, observes its `StateFlow`, lists indexed files, and deletes only files owned by the library. The storage summary uses the app's real `filesDir` file sizes and Android `StatFs` values for the data partition's total and available bytes. No broad `MANAGE_EXTERNAL_STORAGE` permission was added.

This follows Android's storage model: private app data belongs in app-specific storage, structured records belong in Room, shareable media belongs in shared storage or `MediaStore`, and documents should be selected through the Storage Access Framework. AIRI's existing media library is app-private, so the screen reports that actual managed area instead of inventing a device quota or using hard-coded values.

## References

[1]: https://developer.android.com/training/data-storage "Data and file storage overview"
[2]: https://developer.android.com/training/data-storage/app-specific "Access app-specific files"
[3]: https://developer.android.com/training/data-storage/shared/media "Access media files from shared storage"
[4]: https://developer.android.com/training/data-storage/shared/documents-files "Access documents and other files from shared storage"
[5]: https://m3.material.io/components/navigation-drawer "Material 3 navigation drawer"
