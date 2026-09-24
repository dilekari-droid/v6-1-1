# V5.4.16 Physical-Device Acceptance

This protocol exists because emulator PASS is not sufficient for the FULL-HARDENING physical-device gate. A PASS record must identify the real device and preserve evidence. Do not convert NOT_RUN, skipped, or emulator-only results into PASS.

## Baseline

Connect exactly one authorized physical Android device and run:

```bash
PHYSICAL_EVIDENCE_DIR=/path/to/evidence \
  bash .github/scripts/run-v5416-physical-device-acceptance.sh
```

The harness refuses `ro.kernel.qemu=1` by default, records manufacturer/model/API/fingerprint/source commit, runs the existing lifecycle instrumentation suite, and initializes `manual-checklist.tsv`. Keep the evidence directory with the release provenance.

The supported physical-device matrix is Android 12 / API 31, Android 13 / API 33, Android 14 / API 34, and Android 15 / API 35. Record a separate evidence directory for every platform actually accepted. A missing required platform remains NOT_RUN.

## Required manual scenarios

For each device, update `manual-checklist.tsv` only after observing the result. Every PASS must include a short evidence note such as a log file, screenshot name, timestamp, or observed terminal state.

1. **Notification permission — granted/denied:** On Android 13+, test both permission states. Service start gating must be explicit and must not report a false COMPLETED state.
2. **Screen lock/unlock:** Start a scan, lock the device, wait, unlock, and confirm state/progress is not lost.
3. **Home/background/return:** Put the app in the background during an active scan and return. The service must remain authoritative for state.
4. **Activity recreate/rotate:** Recreate the activity during a scan. Confirm there is one scan/service instance and no duplicate start.
5. **Process kill recovery:** Kill the app process during RUNNING and relaunch. Recovery must be INTERRUPTED/appropriate recovery state, never false COMPLETED.
6. **Network off/on:** Disable network during a scan, observe an explicit unavailable/retry state, then restore network and verify controlled recovery without a retry storm.
7. **Notification STOP action:** Stop from the foreground-service notification. Terminal state must be STOPPED, not COMPLETED.
8. **State reconnect:** Reopen the UI while the service is active. Displayed progress/current symbol must reconnect to the existing session.
9. **Result persistence:** After a genuine completion, relaunch and verify the saved result is readable and corresponds to the completed scan.
10. **Battery Saver:** Enable Battery Saver during a scan. Record behavior; no false terminal state is acceptable.
11. **Doze:** Put the device into Doze using a controlled test procedure and observe network/service behavior. No false COMPLETED state is acceptable.
12. **Reboot recovery:** Reboot during an active scan and relaunch. The previous active session must not appear completed; recovery must be explicit.
13. **Force Stop:** Force-stop the application and relaunch manually. The UI must not promise automatic continuation and stale RUNNING must be cleared/recovered explicitly.

## Evidence collection helpers

Before/after a manual scenario, useful non-destructive captures include:

```bash
adb shell dumpsys activity services tr.borsatakip.v5 > services.txt
adb shell dumpsys power > power.txt
adb shell dumpsys battery > battery.txt
adb logcat -d -v threadtime > logcat.txt
```

For network, Doze, reboot, permission revocation, or force-stop tests, use the platform/OEM procedure appropriate to the test device. The harness deliberately does not perform these destructive/state-changing actions automatically.

## Acceptance rule

Physical-device acceptance is PASS only when the required Android platform devices have recorded identity, the instrumentation baseline passes, and every required lifecycle scenario in that device's `manual-checklist.tsv` is explicitly PASS with evidence. Any FAIL or NOT_RUN keeps the physical-device release gate open.
