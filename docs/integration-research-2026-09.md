# AIRI Integration Research (2026-09)

## Executive decision
AIRI can safely expand its agent capabilities, but each cloud connector must use an official OAuth/API flow, least-privilege scopes, encrypted token storage, explicit approval for external writes, and real provider credentials. Do not add catalog cards that claim a live connection when the provider OAuth client, redirect URI, or backend is not configured.

## Priority integrations

| Service | Official capability | Android feasibility | Main constraint |
|---|---|---|---|
| Google Workspace | Gmail, Calendar, Drive REST APIs | High for foreground; backend recommended for sync | OAuth scopes, verification, refresh-token handling |
| Microsoft Graph | Outlook Calendar, OneDrive | High with MSAL delegated auth | App registration; backend for confidential flows |
| Slack | Conversations read/history, chat.postMessage | Medium-high | Workspace scopes, user-vs-bot tokens, rotation |
| Discord | Identify/guilds; messages/bot via API | Medium; backend for bot | Channel permissions, privileged intents, bot secret |
| Notion | Search/pages/blocks; create/update | Medium | Public OAuth client secret requires backend |
| GitHub App | Repositories/files/issues | Medium-high | Prefer GitHub App; short-lived tokens and repo scoping |
| Linear | GraphQL issues/comments | High with PKCE | Refresh-token rotation and webhook backend |
| Jira Cloud | REST issues/comments/worklogs | Medium | 3LO client secret and cloudId require backend |
| Make | Scenarios/hooks/connections | Medium | OAuth client approval, plan-limited endpoints |
| Zapier | Zaps/actions | Conditional | Partner/Workflow API approval; do not hardcode secrets |
| Home Assistant | REST + WebSocket services/events | High for direct instance access | Per-instance OAuth/network reachability; controls need confirmation |
| Browserless MCP | scraping/browser agent/function | Medium, backend preferable | Bearer secret, saved cookies, website actions and prompt injection |

## Security rules

1. Use Authorization Code + PKCE S256 and state for public Android clients where the provider officially supports it.
2. Never put client secrets, private keys, API tokens, refresh tokens, or credential JSON in the APK, repository, logs, prompts, or analytics.
3. Store tokens in Android Keystore/encrypted storage; use a backend for confidential clients, multi-user refresh, webhooks, and long-lived credentials.
4. Request scopes incrementally and show the user exactly what data will be read or changed.
5. Require a typed, task-owned confirmation before sending email, posting messages, creating/updating/deleting events or files, changing repositories, controlling smart-home devices, or submitting browser forms.
6. Treat external content as untrusted data. Prevent prompt injection from email, documents, chats, and web pages from invoking tools or leaking secrets.
7. Implement pagination, rate-limit backoff, 401/403/429 handling, token rotation, revocation, disconnect, audit events, and data deletion.
8. Keep provider connectors fail-closed. A UI card, preference, or dependency is not evidence that a live provider connection works.

## Official references

- Google Gmail API: https://developers.google.com/gmail/api/guides
- Google Calendar API: https://developers.google.com/calendar/api/guides/overview
- Google Drive API: https://developers.google.com/drive/api/guides/about-sdk
- Google OAuth native apps: https://developers.google.com/identity/protocols/oauth2/native-app
- Google scopes and verification: https://developers.google.com/identity/protocols/oauth2/scopes
- Microsoft OAuth: https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow
- Microsoft Graph permissions: https://learn.microsoft.com/en-us/graph/permissions-reference
- Microsoft Calendar: https://learn.microsoft.com/en-us/graph/api/resources/calendar-overview?view=graph-rest-1.0
- Microsoft OneDrive permissions: https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/permissions_reference?view=odsp-graph-online
- Slack OAuth: https://docs.slack.dev/authentication/installing-with-oauth
- Slack PKCE: https://docs.slack.dev/authentication/using-pkce
- Slack scopes: https://docs.slack.dev/reference/scopes
- Discord OAuth: https://docs.discord.com/developers/topics/oauth2
- Discord messages: https://docs.discord.com/developers/resources/message
- Notion authorization: https://developers.notion.com/guides/get-started/authorization
- Notion capabilities: https://developers.notion.com/reference/capabilities
- GitHub OAuth: https://docs.github.com/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps
- GitHub Apps user auth: https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-with-a-github-app-on-behalf-of-a-user
- GitHub contents API: https://docs.github.com/en/rest/repos/contents
- Linear OAuth: https://linear.app/developers/oauth-2-0-authentication
- Jira OAuth 3LO: https://developer.atlassian.com/cloud/oauth/getting-started/implementing-oauth-3lo/
- Make OAuth: https://developers.make.com/api-documentation/authentication/oauth-flow
- Zapier Powered by Zapier auth: https://docs.zapier.com/powered-by-zapier/authentication/getting-started
- Home Assistant auth: https://developers.home-assistant.io/docs/auth_api
- Home Assistant REST: https://developers.home-assistant.io/docs/api/rest/
- Home Assistant WebSocket: https://developers.home-assistant.io/docs/api/websocket
- Browserless MCP: https://docs.browserless.io/mcp/browserless-mcp-server/setup
- MCP authorization: https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization

## Community signals reviewed

- Google refresh-token persistence: https://stackoverflow.com/questions/73072108/google-calendar-api-v3-persistent-authorization-with-code
- Microsoft Graph Calendar permissions: https://stackoverflow.com/questions/61581923/oauth-2-0-implicit-grant-flow-read-calendar-api-returns-403
- Slack missing scopes/token type: https://community.n8n.io/t/trying-to-work-around-slack-oauth2-missing-scopes/227606?tl=en
- Discord OAuth implementation guidance: https://discordjs.guide/legacy/oauth2/oauth2
- Notion invalid_grant/redirect matching: https://stackoverflow.com/questions/67782515/notion-api-getting-invalid-grant-error-when-trying-to-generate-authorization-token
- Linear refresh rotation: https://nango.dev/blog/linear-oauth-refresh-token-invalid-grant/
- Make callback issues: https://community.make.com/t/struggling-with-oauth2/7893
- Home Assistant Android OAuth/network access: https://community.home-assistant.io/t/oauth2-with-android-companion-app/161019
- Browserless MCP OAuth client behavior: https://forum.cursor.com/t/mcp-headers-config-ignored-when-server-has-oauth-discovery/156054

## Current AIRI implementation boundary
AIRI currently has live connector classes for Google, GitHub, Telegram, Zapier, IFTTT, n8n, Notion MCP, local device capabilities, system information, and remote LLM providers. It has official skills for web search, website reading, research, translation, coding, planning, memory, documents, files, GitHub, Telegram, Gmail, Drive, and Calendar. New cloud services should be added behind the existing Connector contract and ConnectorBootstrap only after their OAuth/API configuration and real provider tests exist.

The current release must not add provider secrets, alter signing, certificates, certificate pinning, biometric boundaries, or claim live access to the user's Manus browser or email session. A Manus browser permission does not automatically grant the Android AIRI APK access to that browser session; a separate provider OAuth/API integration is required.

## Validation performed

The project localization contract passed 88/88 checks after adding the new skill/connector labels to English, Arabic, Spanish, and Chinese resources. The UI changes are on both `main` and `cp-foundation`; fresh CI must be rerun after the final localization fix.
