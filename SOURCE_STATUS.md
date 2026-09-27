# V6.1.1 Complete Source

This branch is the canonical self-contained V6.1.1 source tree.

Included:
- full Android source for package tr.borsatakip.v5, versionName 6.1.1, versionCode 611;
- independent VIOP preflight and realtime reconnect hardening;
- tested R3 VIOP policy, sidecar and production gateway;
- backend readiness, quota, contract parser and R3 runtime regression tests;
- fail-closed handling for missing/invalid provider prerequisites.

External production configuration is intentionally not embedded. Real TradeWize E2E still requires a valid production provider credential and authoritative OAuth/universe-metadata/quote/history endpoint contracts.
