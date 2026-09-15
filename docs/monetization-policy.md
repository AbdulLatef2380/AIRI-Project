# AIRI monetization policy

## Current release decision

AIRI remains **local-first and privacy-first**. The commercial surfaces and Google Play billing flow are intentionally **paused** until the product identifiers, Play Console configuration, purchase verification, account identity, renewal/expiry handling, and release evidence are ready. `PricingConfig.BILLING_ENABLED` therefore remains `false`; the application must never claim that a purchase succeeded while this flag is disabled.

The displayed target prices are **$7.99/month** and **$39.99/year**. They are product-planning values only until the corresponding store products and verified billing backend are configured. The Paywall UI may preview both choices, but the purchase path remains blocked and no local preference can be treated as authoritative proof of an entitlement.

## Advertising cancellation

Advertising is cancelled as a product direction. AIRI must not request, load, display, or reserve advertising surfaces for either Free or Pro. `AdPolicy.shouldShowLaunchAd` is permanently false and `PricingConfig.SHOW_LAUNCH_AD_ONCE` is false. No advertising SDK or ad placement should be added later without an explicit product decision that reopens this policy.

## Free and Pro boundaries

Free must remain a useful local product. Local models, local memory, local voice, and privacy/security screens must not be degraded by ads or artificial token sales. Pro is reserved for verified, sustainable expansion capabilities such as advanced automation or provider integrations once their operational cost, privacy, and entitlement contracts are ready. External provider charges remain separate from any future AIRI subscription.

## Safety rule

The existing `SubscriptionManager` contains legacy local usage state and is not, by itself, a secure billing authority. A future billing release must bind entitlement to the authenticated account, verify Google Play state or a trusted backend, handle expiry/grace/pending/cancellation, clear state on account switching, and provide restore behavior before `BILLING_ENABLED` is changed.
