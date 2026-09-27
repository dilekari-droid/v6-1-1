#!/usr/bin/env bash
set -euo pipefail
: "${BORSA_ATTESTATION_PUBLIC_KEYS:?BORSA_ATTESTATION_PUBLIC_KEYS gerekli}"
if [[ "${BORSA_EXTERNAL_SIGNING:-0}" != "1" ]]; then
  : "${BORSA_KEYSTORE_PATH:?BORSA_KEYSTORE_PATH gerekli}"
  : "${BORSA_STORE_PASSWORD:?BORSA_STORE_PASSWORD gerekli}"
  : "${BORSA_KEY_ALIAS:?BORSA_KEY_ALIAS gerekli}"
  : "${BORSA_KEY_PASSWORD:?BORSA_KEY_PASSWORD gerekli}"
fi
gradle --no-daemon --stacktrace clean testReleaseUnitTest assembleRelease
