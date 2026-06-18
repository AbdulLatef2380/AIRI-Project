---
name: Phase 4 Architecture Decisions
description: Phase 4 domain/UI/wiring for Zapier, IFTTT, Stripe, Marketplace, Community Skills, Audits.
---

## SecureStorage constraint
SecureStorage has NO generic getString/putString. All Phase 4 secrets use ConnectorAuthManager (storeToken/storeCredential), backed by EncryptedSharedPreferences.

## ServiceLocator skillRegistry
`skillRegistry` is NOT a direct ServiceLocator property. Access it via:
`com.airi.assistant.ai.skills.SkillExecutor(requireContext()).getRegistry()`
(same pattern used in skillRuntime lazy val at line ~244).

## AiriRoute pattern
New routes added as `const val` inside `object AiriRoute` (AiriApp.kt). Composables registered inside the NavHost block. 5 Phase 4 routes: ZAPIER_IFTTT, STRIPE_PAYMENT, BILLING_HISTORY, MARKETPLACE, COMMUNITY_SKILLS.

## SettingsScreen grouping
Settings groups use `SettingsGroup { }` blocks containing `SettingsNavItem(icon, iconTint, label, onClick)` separated by `SettingsDivider()`. Icons are `Icons.Outlined.*` — the wildcard import is already in the file.

## ConnectorBootstrap.installDefaults
Zapier + IFTTT registered at the top of the APP tab section. Both take `authManager: ConnectorAuthManager` as their only constructor arg.

## Phase 4 API surface (verified)
- StripeManager: paymentState: StateFlow<PaymentState>, purchaseCredits(CreditPackage), purchaseSubscription(annual: Boolean), resetState()
- MarketplaceRepository: catalog/installed/isLoading/lastError StateFlows, fetchFeatured(), search(), install(), uninstall(), update(), publish(), checkUpdates()
- MarketplaceResult sealed: Success, InstallSuccess, PublishSuccess, Error
- CommunitySkillHub: skills/isLoading StateFlows, importFromUrl(), importFromJson(), sandboxTest(), getTrustBreakdown(), remove(skillId)
- ImportResult sealed: Success(skill), Error(message), SecurityBlocked(reason)
- ZapierConnector: buildAuthUrl(), execute(ConnectorInput)
- IftttConnector: setKey(key), getWebhookKey(), execute(ConnectorInput)
- SkillPublisher: validateManifest(json), buildSubmission(...), TEMPLATE_JSON const
- SubscriptionManager.isPremium(): Boolean

## tabIndicatorOffset
Material3 already exports `TabRowDefaults.tabIndicatorOffset`. Do NOT define a custom extension with the same name — it causes a clash. Import it as: `import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset`.

## Audit reports
SecurityAuditReport.kt and GlobalAuditReport.kt are in `security/` package. They are documentation-as-code (kdoc + runtime Log output via printSummary()). No tests required.

## Remaining gaps (intentional)
1. AIRI backend server not deployed (Stripe + Zapier token exchange needs real server)
2. Zapier CLIENT_ID is placeholder — real OAuth app needed
3. Marketplace API is stubbed — needs real backend catalog endpoint
