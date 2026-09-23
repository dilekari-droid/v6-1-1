# V6.1.1 PRE-APK — 5 Critical Source Closures (R3)

APK üretiminden önce kapatılması gereken beş kaynak açığı bu turda ele alındı.

1. VİOP `volume/openInterest`: backend TradeWize VİOP quote ham verisindeki gerçek `volume` ve tam sayı `openInterest` değerlerini negatif/geçersiz değer üretmeden normalize eder. `/v1/viop/contracts` ve `/v1/viop/quote/{symbol}` bu değerleri taşır. Scanner OI bilgisini doğrulanmış VİOP quote snapshotından analiz metadata'sına bağlar. Veri yoksa `null` korunur.
2. Attestation key startup validation: attestation yapılandırılmışsa startup aktif keyId/generation eşleşmesini, Base64 DER parse'ını ve EC private-key tipini doğrular. Hatalı yapılandırma fail-closed startup hatasıdır.
3. `--require-production-startup` false-positive: `APP_ENV=production` değilken readiness artık PASS vermez; CLI non-zero döner.
4. Benchmark secret-field validator: alan adlarına ek olarak benign alanlara gömülü Authorization/Bearer, API key, access token, secret, password ve private-key desenleri de reddedilir.
5. Progress policy tutarlılığı: `ManualScanProgressPolicy` canonical gösterim politikasıdır. BIST UI, Opportunity UI ve foreground notification aynı policy'yi kullanır; tamamlanmamış durumda %100 gösterilmez. Source gate doğrudan `session.progress` bypass'ını reddeder.

## Verification

- Targeted tests: 24/24 PASS
- Full backend regression: 111/111 PASS
- UI/UX source contract: 33/33 PASS
- P0 Android CI source acceptance: PASS
- Release-readiness gate self-test: PASS
- Android Gradle unit-test attempt: blocked only by unavailable `services.gradle.org` DNS/network in the local execution environment; no source compile error was observed before dependency download.

## R3 source package

- File: `V6.1.1-KAYNAK-KOD-PREAPK-CONTROLLED-HARDENED-R3.zip`
- SHA-256: `3ff6f40663e5dda84725aa47350afb7cdbe024c884244fef2f09d3924a2969e2`
- Artifact type: source-only / pre-APK
- APK/AAB included: no
