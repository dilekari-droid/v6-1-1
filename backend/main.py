import asyncio
import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Any
from pathlib import Path

import httpx
from fastapi import FastAPI, Header, HTTPException, Query, Request, WebSocket
from fastapi.responses import JSONResponse

APP_NAME = "BorsaTakip Production Backend"
APP_VERSION = "1.4.0"
ENGINE_VERSION = "V5.4.16"
APP_ENV = os.getenv("APP_ENV", "development").strip().lower() or "development"
DEPLOYMENT_REVISION = (
    os.getenv("APP_REVISION", "").strip()
    or os.getenv("RAILWAY_GIT_COMMIT_SHA", "").strip()
    or os.getenv("RENDER_GIT_COMMIT", "").strip()
)[:128]
UPSTREAM = os.getenv("TRADEWIZE_BASE_URL", "https://api.tradewize.com.tr").rstrip("/")
APP_API_KEY = os.getenv("APP_API_KEY", "").strip()
UPSTREAM_ACCESS_TOKEN = os.getenv("TRADEWIZE_ACCESS_TOKEN", "").strip()
TRADEWIZE_API_KEY = os.getenv("TRADEWIZE_API_KEY", "").strip()
MAX_AGE_MS = int(os.getenv("MAX_DATA_AGE_MS", "5000"))
DEFAULT_SCAN_INTERVAL = os.getenv("SCANNER_INTERVAL", "5m").strip().lower() or "5m"
SCANNER_HISTORY_BARS = int(os.getenv("SCANNER_HISTORY_BARS", "120"))
SCANNER_MAX_SYMBOLS = int(os.getenv("SCANNER_MAX_SYMBOLS", "1000"))
SCANNER_BATCH_SIZE = int(os.getenv("SCANNER_BATCH_SIZE", "60"))
SCANNER_CONCURRENCY = max(1, int(os.getenv("SCANNER_CONCURRENCY", "2")))
SCANNER_CONFIGURED_PACING_MS = max(0, int(os.getenv("SCANNER_PACING_MS", "500")))
SCANNER_RATE_LIMIT_WEIGHT_PER_MINUTE = max(1, int(os.getenv("SCANNER_RATE_LIMIT_WEIGHT_PER_MINUTE", "120")))
SCANNER_REQUESTS_PER_HOUR = max(1, int(os.getenv("SCANNER_REQUESTS_PER_HOUR", "5000")))
# Weight remains configurable until the provider's bar-count formula is formally verified.
SCANNER_REQUEST_WEIGHT = max(1, int(os.getenv("SCANNER_REQUEST_WEIGHT", "1")))
UPSTREAM_QUOTA_SCOPE = os.getenv("UPSTREAM_QUOTA_SCOPE", "all").strip().lower() or "all"
REDIS_URL = os.getenv("REDIS_URL", "").strip()
UPSTREAM_DISTRIBUTED_QUOTA = os.getenv("UPSTREAM_DISTRIBUTED_QUOTA", "false").strip().lower() in {"1", "true", "yes", "on"}
UPSTREAM_DISTRIBUTED_QUOTA_KEY = os.getenv("UPSTREAM_DISTRIBUTED_QUOTA_KEY", "borsatakip:provider-quota:v1").strip() or "borsatakip:provider-quota:v1"
SNAPSHOT_BATCH_MAX_SYMBOLS = max(1, min(100, int(os.getenv("SNAPSHOT_BATCH_MAX_SYMBOLS", "20"))))
SNAPSHOT_BATCH_READ_TIMEOUT_MS = max(30_000, int(os.getenv("SNAPSHOT_BATCH_READ_TIMEOUT_MS", "175000")))
SNAPSHOT_BATCH_CALL_TIMEOUT_MS = max(SNAPSHOT_BATCH_READ_TIMEOUT_MS, int(os.getenv("SNAPSHOT_BATCH_CALL_TIMEOUT_MS", "180000")))
SNAPSHOT_BATCH_OUTER_TIMEOUT_MS = max(SNAPSHOT_BATCH_CALL_TIMEOUT_MS, int(os.getenv("SNAPSHOT_BATCH_OUTER_TIMEOUT_MS", "185000")))
SCANNER_MINUTE_PACING_MS = (60_000 * SCANNER_REQUEST_WEIGHT + SCANNER_RATE_LIMIT_WEIGHT_PER_MINUTE - 1) // SCANNER_RATE_LIMIT_WEIGHT_PER_MINUTE
SCANNER_HOURLY_PACING_MS = (3_600_000 + SCANNER_REQUESTS_PER_HOUR - 1) // SCANNER_REQUESTS_PER_HOUR
SCANNER_MIN_PACING_MS = max(SCANNER_MINUTE_PACING_MS, SCANNER_HOURLY_PACING_MS)
SCANNER_PACING_MS = max(SCANNER_CONFIGURED_PACING_MS, SCANNER_MIN_PACING_MS)
# Conservative source-side budget estimate for one snapshot batch. It intentionally assumes the
# full upstream 15s request timeout per concurrency wave plus global pacing between starts.
SNAPSHOT_BATCH_ESTIMATED_WORST_CASE_MS = (
    ((SNAPSHOT_BATCH_MAX_SYMBOLS + SCANNER_CONCURRENCY - 1) // SCANNER_CONCURRENCY) * 15_000
    + max(0, SNAPSHOT_BATCH_MAX_SYMBOLS - 1) * SCANNER_PACING_MS
)
SCANNER_RETRY_COUNT = max(0, int(os.getenv("SCANNER_RETRY_COUNT", "2")))
SCANNER_RETRY_BASE_MS = max(100, int(os.getenv("SCANNER_RETRY_BASE_MS", "500")))
CAPABILITY_CACHE_SECONDS = int(os.getenv("CAPABILITY_CACHE_SECONDS", "10"))
SCANNER_SYMBOLS = os.getenv("SCANNER_SYMBOLS", "")
BAR_CACHE_TTL_SECONDS = max(1, int(os.getenv("BAR_CACHE_TTL_SECONDS", "30")))
BAR_CACHE_MAX_BARS = max(200, int(os.getenv("BAR_CACHE_MAX_BARS", "2500")))
BAR_CACHE_IDLE_TTL_SECONDS = max(BAR_CACHE_TTL_SECONDS, int(os.getenv("BAR_CACHE_IDLE_TTL_SECONDS", "900")))
BAR_CACHE_MAX_KEYS = max(100, int(os.getenv("BAR_CACHE_MAX_KEYS", "1600")))
BAR_CACHE_MAX_TOTAL_BARS = max(BAR_CACHE_MAX_BARS, int(os.getenv("BAR_CACHE_MAX_TOTAL_BARS", "250000")))
VIOP_CONTRACT_METADATA_PATH = os.getenv("TRADEWIZE_VIOP_CONTRACT_METADATA_PATH", "").strip()
VIOP_METADATA_MAX_AGE_SECONDS = max(60, int(os.getenv("VIOP_METADATA_MAX_AGE_SECONDS", "86400")))
VIOP_METADATA_REQUIRE_UNIVERSE_AS_OF = os.getenv("VIOP_METADATA_REQUIRE_UNIVERSE_AS_OF", "true").strip().lower() in {"1", "true", "yes", "on"}
VIOP_METADATA_REQUIRE_COMPLETE = os.getenv("VIOP_METADATA_REQUIRE_COMPLETE", "true").strip().lower() in {"1", "true", "yes", "on"}
VIOP_NEAR_EXPIRY_DAYS = max(0, int(os.getenv("VIOP_NEAR_EXPIRY_DAYS", "3")))
ALL_TIME_HISTORY_VERIFIED = os.getenv("ALL_TIME_HISTORY_VERIFIED", "false").strip().lower() in {"1", "true", "yes", "on"}
ALL_TIME_HISTORY_START_MS = max(1, int(os.getenv("ALL_TIME_HISTORY_START_MS", "1")))
INGRESS_RATE_LIMIT_PER_MINUTE = max(30, int(os.getenv("INGRESS_RATE_LIMIT_PER_MINUTE", "600")))
INGRESS_RATE_LIMIT_BURST = max(10, int(os.getenv("INGRESS_RATE_LIMIT_BURST", "120")))
INGRESS_BUCKET_TTL_SECONDS = max(60, int(os.getenv("INGRESS_BUCKET_TTL_SECONDS", "900")))
INGRESS_BUCKET_MAX_ENTRIES = max(128, int(os.getenv("INGRESS_BUCKET_MAX_ENTRIES", "4096")))
SESSION_TOKEN_SECRET = os.getenv("SESSION_TOKEN_SECRET", "").strip()
SESSION_TOKEN_TTL_SECONDS = max(60, min(3600, int(os.getenv("SESSION_TOKEN_TTL_SECONDS", "900"))))
SESSION_TOKEN_MIN_SECRET_LENGTH = max(32, int(os.getenv("SESSION_TOKEN_MIN_SECRET_LENGTH", "32")))
SESSION_TOKEN_KEY_ID = os.getenv("SESSION_TOKEN_KEY_ID", "v1").strip() or "v1"
SESSION_TOKEN_NOT_BEFORE = max(0, int(os.getenv("SESSION_TOKEN_NOT_BEFORE", "0")))
SESSION_TOKEN_PREVIOUS_KEYS_JSON = os.getenv("SESSION_TOKEN_PREVIOUS_KEYS_JSON", "").strip()
SESSION_REVOKE_STORE_PATH = os.getenv("SESSION_REVOKE_STORE_PATH", "").strip()
SESSION_REVOKE_MAX_ENTRIES = max(128, int(os.getenv("SESSION_REVOKE_MAX_ENTRIES", "10000")))
SESSION_REVOKE_DISTRIBUTED = os.getenv("SESSION_REVOKE_DISTRIBUTED", "false").strip().lower() in {"1", "true", "yes", "on"}
SESSION_REVOKE_REDIS_KEY = os.getenv("SESSION_REVOKE_REDIS_KEY", "borsatakip:session-revoke:v1").strip() or "borsatakip:session-revoke:v1"
BAR_CACHE_PERSIST_PATH = os.getenv("BAR_CACHE_PERSIST_PATH", "").strip()
BAR_CACHE_PERSIST_INTERVAL_SECONDS = max(5, int(os.getenv("BAR_CACHE_PERSIST_INTERVAL_SECONDS", "30")))
BAR_CACHE_PREWARM_SYMBOLS = tuple(
    item.strip().upper() for item in os.getenv("BAR_CACHE_PREWARM_SYMBOLS", "").split(",") if item.strip()
)
BAR_CACHE_PREWARM_INTERVALS = tuple(
    item.strip().lower() for item in os.getenv("BAR_CACHE_PREWARM_INTERVALS", "5m,1d").split(",") if item.strip()
)
BAR_CACHE_PREWARM_BIST_UNIVERSE = os.getenv("BAR_CACHE_PREWARM_BIST_UNIVERSE", "false").strip().lower() in {"1", "true", "yes", "on"}
BAR_CACHE_PREWARM_MAX_SYMBOLS = max(1, min(2000, int(os.getenv("BAR_CACHE_PREWARM_MAX_SYMBOLS", "1000"))))
SNAPSHOT_BATCH_IDEMPOTENCY_TTL_SECONDS = max(5, int(os.getenv("SNAPSHOT_BATCH_IDEMPOTENCY_TTL_SECONDS", "120")))
SNAPSHOT_BATCH_IDEMPOTENCY_MAX_ENTRIES = max(64, int(os.getenv("SNAPSHOT_BATCH_IDEMPOTENCY_MAX_ENTRIES", "2048")))
SNAPSHOT_BATCH_DISTRIBUTED_IDEMPOTENCY = os.getenv("SNAPSHOT_BATCH_DISTRIBUTED_IDEMPOTENCY", "false").strip().lower() in {"1", "true", "yes", "on"}
SNAPSHOT_BATCH_REDIS_PREFIX = os.getenv("SNAPSHOT_BATCH_REDIS_PREFIX", "borsatakip:snapshot-idempotency:v1").strip() or "borsatakip:snapshot-idempotency:v1"
TRADINGVIEW_WEBHOOK_SECRET = os.getenv("TRADINGVIEW_WEBHOOK_SECRET", "").strip()
TRADINGVIEW_DB_PATH = os.getenv("TRADINGVIEW_DB_PATH", "").strip()
TRADINGVIEW_RETENTION_DAYS = max(1, int(os.getenv("TRADINGVIEW_RETENTION_DAYS", "30")))
TRADINGVIEW_SIGNAL_FRESHNESS_MS = max(60_000, int(os.getenv("TRADINGVIEW_SIGNAL_FRESHNESS_MS", "900000")))
TRADINGVIEW_DEDUPE_WINDOW_MS = max(1_000, int(os.getenv("TRADINGVIEW_DEDUPE_WINDOW_MS", "60000")))
ATTESTATION_PRIVATE_KEYS_JSON = os.getenv("ATTESTATION_PRIVATE_KEYS_JSON", "").strip()
ATTESTATION_ACTIVE_KEY_ID = os.getenv("ATTESTATION_ACTIVE_KEY_ID", "").strip()
ATTESTATION_ACTIVE_KEY_GENERATION = max(0, int(os.getenv("ATTESTATION_ACTIVE_KEY_GENERATION", "0")))
ATTESTATION_PROVIDER_ID = os.getenv("ATTESTATION_PROVIDER_ID", "tradewize").strip() or "tradewize"
ATTESTATION_TTL_MS = max(5_000, min(120_000, int(os.getenv("ATTESTATION_TTL_MS", "60000"))))

# Canonical Android V5.4.16 dynamic-scanner wire contract. Public values stay lowercase.
SUPPORTED_SCAN_INTERVALS = {"1m", "3m", "5m", "10m", "15m", "30m", "60m", "1d"}

FEATURE_CAPABILITIES = {
    "bistSnapshotBatch": True,
    "dynamicScanner": True,
    # Fail closed until the backend implements the Android V538 attestation contract.
    "realtimeScannerRest": False,
    "liveMarketWebSocket": False,
    "realtimeScannerWebSocket": False,
    "attestationReady": False,
    "attestationConfigured": bool(ATTESTATION_PRIVATE_KEYS_JSON and ATTESTATION_ACTIVE_KEY_ID and ATTESTATION_ACTIVE_KEY_GENERATION > 0),
    # Configuration path alone is not readiness. provider_capabilities() promotes this only
    # after metadata + real liquidity fields are observed from the provider.
    "viopContractsReady": False,
    # Reserved until a real persistent webhook/research backend is wired.
    "tradingViewSignals": bool(TRADINGVIEW_WEBHOOK_SECRET and TRADINGVIEW_DB_PATH),
    "researchFoundation": False,
    # `range=max` is implemented, but production capability stays false until an explicit
    # provider E2E proves that its from/to bars endpoint returns the complete listing history.
    "allTimeHistory": ALL_TIME_HISTORY_VERIFIED,
    "barHistoryCache": True,
    "ingressRateLimit": True,
    "shortLivedSessionAuth": bool(SESSION_TOKEN_SECRET and APP_API_KEY),
    "sessionKeyRotation": True,
    "sessionRevocation": True,
    "barCachePersistence": bool(BAR_CACHE_PERSIST_PATH),
    "providerWeightFormulaVerified": False,
    "distributedProviderQuotaConfigured": bool(UPSTREAM_DISTRIBUTED_QUOTA and REDIS_URL),
    "distributedSessionRevocationConfigured": bool(SESSION_REVOKE_DISTRIBUTED and REDIS_URL),
    "distributedSnapshotIdempotencyConfigured": bool(SNAPSHOT_BATCH_DISTRIBUTED_IDEMPOTENCY and REDIS_URL),
    # With the verified 5k/hour upstream floor, a cold 640-symbol refresh cannot truthfully promise 5 minutes.
    "fullBistFiveMinuteSla": False,
}

def _secret_strength_ok(value: str) -> bool:
    if len(value) < SESSION_TOKEN_MIN_SECRET_LENGTH:
        return False
    classes = sum([
        any(ch.islower() for ch in value),
        any(ch.isupper() for ch in value),
        any(ch.isdigit() for ch in value),
        any(not ch.isalnum() for ch in value),
    ])
    return classes >= 3 and len(set(value)) >= 12


def _validate_startup_configuration() -> None:
    if UPSTREAM_QUOTA_SCOPE not in {"all", "bars_only"}:
        raise RuntimeError("UPSTREAM_QUOTA_SCOPE must be all or bars_only")
    if UPSTREAM_DISTRIBUTED_QUOTA and not REDIS_URL:
        raise RuntimeError("REDIS_URL is required when UPSTREAM_DISTRIBUTED_QUOTA=true")
    if SESSION_REVOKE_DISTRIBUTED and not REDIS_URL:
        raise RuntimeError("REDIS_URL is required when SESSION_REVOKE_DISTRIBUTED=true")
    if SNAPSHOT_BATCH_DISTRIBUTED_IDEMPOTENCY and not REDIS_URL:
        raise RuntimeError("REDIS_URL is required when SNAPSHOT_BATCH_DISTRIBUTED_IDEMPOTENCY=true")
    if bool(TRADINGVIEW_WEBHOOK_SECRET) != bool(TRADINGVIEW_DB_PATH):
        raise RuntimeError("TRADINGVIEW_WEBHOOK_SECRET and TRADINGVIEW_DB_PATH must be configured together")
    attestation_parts = [bool(ATTESTATION_PRIVATE_KEYS_JSON), bool(ATTESTATION_ACTIVE_KEY_ID), ATTESTATION_ACTIVE_KEY_GENERATION > 0]
    if any(attestation_parts) and not all(attestation_parts):
        raise RuntimeError("ATTESTATION_PRIVATE_KEYS_JSON, ATTESTATION_ACTIVE_KEY_ID and ATTESTATION_ACTIVE_KEY_GENERATION must be configured together")
    if all(attestation_parts):
        try:
            _load_attestation_private_key(ATTESTATION_ACTIVE_KEY_ID, ATTESTATION_ACTIVE_KEY_GENERATION)
        except (ValueError, RuntimeError) as exc:
            raise RuntimeError(f"Attestation signing configuration is invalid: {exc}") from exc
    if APP_ENV != "production":
        return
    if not APP_API_KEY:
        raise RuntimeError("APP_API_KEY is required when APP_ENV=production")
    if not _secret_strength_ok(SESSION_TOKEN_SECRET):
        raise RuntimeError("SESSION_TOKEN_SECRET is missing or too weak for production")
    try:
        previous_keys = _parse_previous_session_keys()
    except ValueError as exc:
        raise RuntimeError(str(exc)) from exc
    for kid, secret in previous_keys.items():
        if not kid or not _secret_strength_ok(secret):
            raise RuntimeError("SESSION_TOKEN_PREVIOUS_KEYS_JSON contains an invalid/weak key")
    if not (TRADEWIZE_API_KEY or UPSTREAM_ACCESS_TOKEN):
        raise RuntimeError("TradeWize credentials are required when APP_ENV=production")
    if TRADINGVIEW_WEBHOOK_SECRET and len(TRADINGVIEW_WEBHOOK_SECRET) < 32:
        raise RuntimeError("TRADINGVIEW_WEBHOOK_SECRET is too weak for production")


@asynccontextmanager
async def _lifespan(_: FastAPI):
    _validate_startup_configuration()
    _load_session_revocations_from_disk()
    _load_bar_cache_from_disk()
    persist_task: asyncio.Task | None = None
    prewarm_task: asyncio.Task | None = None
    if BAR_CACHE_PERSIST_PATH:
        persist_task = asyncio.create_task(_bar_cache_persist_loop(), name="bar-cache-persist")
    if BAR_CACHE_PREWARM_SYMBOLS or BAR_CACHE_PREWARM_BIST_UNIVERSE:
        prewarm_task = asyncio.create_task(_prewarm_bar_cache_once(), name="bar-cache-prewarm")
    try:
        yield
    finally:
        for task in (prewarm_task, persist_task):
            if task is not None and not task.done():
                task.cancel()
        for task in (prewarm_task, persist_task):
            if task is not None:
                try:
                    await task
                except asyncio.CancelledError:
                    pass
        if BAR_CACHE_PERSIST_PATH:
            _persist_bar_cache_to_disk()
        if SESSION_REVOKE_STORE_PATH:
            _persist_session_revocations_to_disk()


app = FastAPI(title=APP_NAME, version=APP_VERSION, lifespan=_lifespan)

_ingress_lock = asyncio.Lock()
_ingress_buckets: dict[str, dict[str, float]] = {}
_ingress_metrics: dict[str, int] = {"accepted": 0, "rejected": 0, "rateLimited429": 0, "weightedUnits": 0}


def _ingress_identities(request: Request) -> list[str]:
    """Return layered ingress quota identities.

    A caller-controlled installation id alone must never be enough to evade ingress quotas,
    so every request is also charged to the remote-IP bucket. Auth/install identifiers are
    hashed; raw credentials are never retained.
    """
    identities: list[str] = []
    installation = request.headers.get("x-install-id", "").strip()
    authorization = request.headers.get("authorization", "").strip()
    if installation and len(installation) <= 128:
        identities.append("install:" + hashlib.sha256(installation.encode("utf-8")).hexdigest()[:24])
    # Installation id is caller-controlled telemetry, never an authentication identity.
    # Charge the authorization subject independently so rotating/spoofing installation ids
    # cannot discard the auth-level quota dimension. Raw credentials are never retained.
    if authorization:
        identities.append("auth:" + hashlib.sha256(authorization.encode("utf-8")).hexdigest()[:24])
    host = request.client.host if request.client else "unknown"
    identities.append("ip:" + hashlib.sha256(host.encode("utf-8")).hexdigest()[:24])
    return list(dict.fromkeys(identities))


def _ingress_weight(path: str) -> int:
    if path.startswith("/v1/scanner/opportunities"):
        return 5
    if path.startswith("/v1/bist/snapshot-batch"):
        return 5
    if "/history" in path:
        return 2
    return 1


def _prune_ingress_buckets(now: float, protected: set[str]) -> None:
    """Bound ingress identity state by idle TTL and LRU count.

    This prevents caller-controlled installation ids from growing process memory without bound.
    The remote-IP identity is still charged independently, so eviction does not weaken the
    layered request quota for the active caller.
    """
    idle_cutoff = now - INGRESS_BUCKET_TTL_SECONDS
    for identity, bucket in list(_ingress_buckets.items()):
        if identity in protected:
            continue
        last_seen = float(bucket.get("lastSeen", bucket.get("at", 0.0)))
        if last_seen < idle_cutoff:
            _ingress_buckets.pop(identity, None)

    needed = sum(1 for identity in protected if identity not in _ingress_buckets)
    target_existing = max(0, INGRESS_BUCKET_MAX_ENTRIES - needed)
    if len(_ingress_buckets) <= target_existing:
        return
    candidates = sorted(
        (
            (float(bucket.get("lastSeen", bucket.get("at", 0.0))), identity)
            for identity, bucket in _ingress_buckets.items()
            if identity not in protected
        ),
        key=lambda item: item[0],
    )
    for _, identity in candidates:
        if len(_ingress_buckets) <= target_existing:
            break
        _ingress_buckets.pop(identity, None)


async def _consume_ingress_token(request: Request) -> tuple[bool, int, int]:
    now = time.monotonic()
    identities = _ingress_identities(request)
    weight = _ingress_weight(request.url.path)
    refill_per_second = INGRESS_RATE_LIMIT_PER_MINUTE / 60.0
    async with _ingress_lock:
        protected = set(identities)
        _prune_ingress_buckets(now, protected)
        snapshots: list[tuple[str, dict[str, float], float]] = []
        retry_after = 0
        minimum_remaining = INGRESS_RATE_LIMIT_BURST
        for identity in identities:
            bucket = _ingress_buckets.get(identity)
            if bucket is None:
                bucket = {"tokens": float(INGRESS_RATE_LIMIT_BURST), "at": now, "lastSeen": now}
                _ingress_buckets[identity] = bucket
            elapsed = max(0.0, now - float(bucket["at"]))
            available = min(float(INGRESS_RATE_LIMIT_BURST), float(bucket["tokens"]) + elapsed * refill_per_second)
            bucket["lastSeen"] = now
            snapshots.append((identity, bucket, available))
            minimum_remaining = min(minimum_remaining, int(available))
            if available < weight:
                missing = weight - available
                retry_after = max(retry_after, max(1, int((missing / refill_per_second) + 0.999)))
        if retry_after > 0:
            for _, bucket, available in snapshots:
                bucket["tokens"] = available
                bucket["at"] = now
                bucket["lastSeen"] = now
            _ingress_metrics["rejected"] = int(_ingress_metrics.get("rejected", 0)) + 1
            _ingress_metrics["rateLimited429"] = int(_ingress_metrics.get("rateLimited429", 0)) + 1
            return False, retry_after, minimum_remaining
        remaining_after = INGRESS_RATE_LIMIT_BURST
        for _, bucket, available in snapshots:
            available -= weight
            bucket["tokens"] = available
            bucket["at"] = now
            bucket["lastSeen"] = now
            remaining_after = min(remaining_after, int(available))
        _ingress_metrics["accepted"] = int(_ingress_metrics.get("accepted", 0)) + 1
        _ingress_metrics["weightedUnits"] = int(_ingress_metrics.get("weightedUnits", 0)) + weight
        return True, 0, remaining_after


@app.middleware("http")
async def ingress_rate_limit_middleware(request: Request, call_next):
    if request.url.path.startswith(("/docs", "/redoc", "/openapi.json")):
        return await call_next(request)
    authorization = request.headers.get("authorization", "").strip()
    if authorization.startswith("BorsaSession "):
        token = authorization.removeprefix("BorsaSession ").strip()
        install_id = request.headers.get("x-install-id", "").strip()
        if not await _verify_session_token_shared(token, expected_installation_id=install_id):
            return JSONResponse(status_code=401, content={"detail": "BACKEND_SESSION_AUTH_INVALID"})
    allowed, retry_after, remaining = await _consume_ingress_token(request)
    if not allowed:
        return JSONResponse(
            status_code=429,
            content={"detail": "BACKEND_INGRESS_RATE_LIMIT"},
            headers={"Retry-After": str(retry_after), "X-RateLimit-Remaining": str(remaining)},
        )
    response = await call_next(request)
    response.headers["X-RateLimit-Remaining"] = str(remaining)
    return response


def _b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _b64url_decode(raw: str) -> bytes:
    padding = "=" * ((4 - len(raw) % 4) % 4)
    return base64.urlsafe_b64decode(raw + padding)


def _parse_previous_session_keys() -> dict[str, str]:
    if not SESSION_TOKEN_PREVIOUS_KEYS_JSON:
        return {}
    try:
        raw = json.loads(SESSION_TOKEN_PREVIOUS_KEYS_JSON)
    except json.JSONDecodeError as exc:
        raise ValueError("SESSION_TOKEN_PREVIOUS_KEYS_JSON must be a JSON object") from exc
    if not isinstance(raw, dict):
        raise ValueError("SESSION_TOKEN_PREVIOUS_KEYS_JSON must be a JSON object")
    out: dict[str, str] = {}
    for kid, secret in raw.items():
        kid_s = str(kid).strip()
        secret_s = str(secret).strip()
        if not kid_s or not secret_s or len(kid_s) > 64:
            raise ValueError("SESSION_TOKEN_PREVIOUS_KEYS_JSON contains an invalid key id/secret")
        out[kid_s] = secret_s
    return out


def _session_keyring() -> dict[str, str]:
    ring = _parse_previous_session_keys()
    if SESSION_TOKEN_SECRET:
        ring[SESSION_TOKEN_KEY_ID] = SESSION_TOKEN_SECRET
    return ring


def _session_subject(installation_id: str) -> str:
    normalized = installation_id.strip()
    if not normalized:
        return ""
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


_session_subject_epochs: dict[str, int] = {}
_session_revoke_lock = asyncio.Lock()


def _prune_session_subject_epochs() -> None:
    if len(_session_subject_epochs) <= SESSION_REVOKE_MAX_ENTRIES:
        return
    # Epochs are monotonically increasing only per subject; bounded insertion order is enough
    # for this defensive in-process store. Durable/shared stores can be configured externally.
    overflow = len(_session_subject_epochs) - SESSION_REVOKE_MAX_ENTRIES
    for key in list(_session_subject_epochs)[:overflow]:
        _session_subject_epochs.pop(key, None)


def _load_session_revocations_from_disk() -> int:
    if not SESSION_REVOKE_STORE_PATH:
        return 0
    path = Path(SESSION_REVOKE_STORE_PATH)
    if not path.exists():
        return 0
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        rows = payload.get("subjects", {}) if isinstance(payload, dict) else {}
        if not isinstance(rows, dict):
            return 0
        loaded = 0
        for subject, epoch in rows.items():
            subject_s = str(subject).strip()
            try:
                epoch_i = int(epoch)
            except (TypeError, ValueError):
                continue
            if len(subject_s) == 64 and epoch_i >= 0:
                _session_subject_epochs[subject_s] = epoch_i
                loaded += 1
        _prune_session_subject_epochs()
        return loaded
    except (OSError, json.JSONDecodeError):
        return 0


def _persist_session_revocations_to_disk() -> bool:
    if not SESSION_REVOKE_STORE_PATH:
        return False
    path = Path(SESSION_REVOKE_STORE_PATH)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(
            json.dumps({"version": 1, "subjects": _session_subject_epochs}, separators=(",", ":"), sort_keys=True),
            encoding="utf-8",
        )
        os.replace(tmp, path)
        return True
    except OSError:
        return False


def _issue_session_token(installation_id: str, *, subject_epoch: int | None = None) -> tuple[str, int]:
    if not SESSION_TOKEN_SECRET:
        raise HTTPException(status_code=503, detail="SHORT_LIVED_SESSION_AUTH_DISABLED")
    installation_id = installation_id.strip()
    subject = _session_subject(installation_id)
    if not subject:
        raise HTTPException(status_code=400, detail="INSTALLATION_ID_REQUIRED")
    now = int(time.time())
    exp = now + SESSION_TOKEN_TTL_SECONDS
    payload = {
        "installId": installation_id,
        "sub": subject,
        "subjectEpoch": int(_session_subject_epochs.get(subject, 0) if subject_epoch is None else subject_epoch),
        "iat": now,
        "exp": exp,
        "nonce": secrets.token_urlsafe(12),
        "kid": SESSION_TOKEN_KEY_ID,
        "ver": 2,
    }
    body = _b64url_encode(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    signature = hmac.new(SESSION_TOKEN_SECRET.encode("utf-8"), body.encode("ascii"), hashlib.sha256).digest()
    return f"{body}.{_b64url_encode(signature)}", exp


def _verify_session_token(token: str, expected_installation_id: str | None = None, *, check_epoch: bool = True) -> bool:
    if "." not in token:
        return False
    body, signature = token.split(".", 1)
    try:
        actual = _b64url_decode(signature)
        payload = json.loads(_b64url_decode(body).decode("utf-8"))
        exp = int(payload.get("exp", 0))
        issued_at = int(payload.get("iat", 0))
        installation = str(payload.get("installId", "")).strip()
        key_id = str(payload.get("kid", "")).strip()
        version = int(payload.get("ver", 0))
        subject = str(payload.get("sub", "")).strip()
        subject_epoch = int(payload.get("subjectEpoch", -1))
    except (ValueError, TypeError, json.JSONDecodeError):
        return False
    keyring = _session_keyring()
    secret = keyring.get(key_id)
    if not secret:
        return False
    expected = hmac.new(secret.encode("utf-8"), body.encode("ascii"), hashlib.sha256).digest()
    if not hmac.compare_digest(expected, actual):
        return False
    now = int(time.time())
    if not installation or issued_at <= 0 or issued_at > now + 60 or exp < now or exp <= issued_at:
        return False
    if version != 2:
        return False
    if SESSION_TOKEN_NOT_BEFORE and issued_at < SESSION_TOKEN_NOT_BEFORE:
        return False
    expected_subject = _session_subject(installation)
    if not subject or not hmac.compare_digest(subject, expected_subject):
        return False
    if check_epoch and subject_epoch != int(_session_subject_epochs.get(subject, 0)):
        return False
    if expected_installation_id is not None:
        expected_installation_id = expected_installation_id.strip()
        if not expected_installation_id or not hmac.compare_digest(installation, expected_installation_id):
            return False
    return True


async def _shared_session_subject_epoch(subject: str) -> int:
    if not SESSION_REVOKE_DISTRIBUTED:
        return int(_session_subject_epochs.get(subject, 0))
    try:
        client = await _get_redis_quota_client()
        raw = await client.hget(SESSION_REVOKE_REDIS_KEY, subject)
        epoch = int(raw or 0)
    except Exception as exc:
        raise HTTPException(status_code=503, detail="SESSION_SHARED_STATE_UNAVAILABLE") from exc
    _session_subject_epochs[subject] = epoch
    _prune_session_subject_epochs()
    return epoch


async def _issue_session_token_shared(installation_id: str) -> tuple[str, int]:
    subject = _session_subject(installation_id)
    if not subject:
        raise HTTPException(status_code=400, detail="INSTALLATION_ID_REQUIRED")
    epoch = await _shared_session_subject_epoch(subject)
    return _issue_session_token(installation_id, subject_epoch=epoch)


async def _verify_session_token_shared(token: str, expected_installation_id: str | None = None) -> bool:
    if not _verify_session_token(token, expected_installation_id=expected_installation_id, check_epoch=False):
        return False
    try:
        body, _ = token.split(".", 1)
        payload = json.loads(_b64url_decode(body).decode("utf-8"))
        subject = str(payload.get("sub", "")).strip()
        token_epoch = int(payload.get("subjectEpoch", -1))
    except (ValueError, TypeError, json.JSONDecodeError):
        return False
    try:
        current_epoch = await _shared_session_subject_epoch(subject)
    except HTTPException:
        return False
    return token_epoch == current_epoch


async def _revoke_installation_sessions(installation_id: str) -> int:
    subject = _session_subject(installation_id)
    if not subject:
        raise HTTPException(status_code=400, detail="INSTALLATION_ID_REQUIRED")
    async with _session_revoke_lock:
        if SESSION_REVOKE_DISTRIBUTED:
            try:
                client = await _get_redis_quota_client()
                next_epoch = int(await client.hincrby(SESSION_REVOKE_REDIS_KEY, subject, 1))
                await client.expire(SESSION_REVOKE_REDIS_KEY, max(SESSION_TOKEN_TTL_SECONDS * 4, 3600))
            except Exception as exc:
                raise HTTPException(status_code=503, detail="SESSION_SHARED_STATE_UNAVAILABLE") from exc
        else:
            next_epoch = int(_session_subject_epochs.get(subject, 0)) + 1
        _session_subject_epochs[subject] = next_epoch
        _prune_session_subject_epochs()
        _persist_session_revocations_to_disk()
        return next_epoch


async def require_app_auth(authorization: str | None) -> None:
    if not APP_API_KEY:
        return
    if authorization == f"Bearer {APP_API_KEY}":
        return
    if authorization and authorization.startswith("BorsaSession "):
        token = authorization.removeprefix("BorsaSession ").strip()
        if await _verify_session_token_shared(token):
            return
    raise HTTPException(status_code=401, detail="BACKEND_AUTH_INVALID")


_token_lock = asyncio.Lock()
_cached_access_token = UPSTREAM_ACCESS_TOKEN
_cached_access_token_expires_at = (time.time() + 300) if UPSTREAM_ACCESS_TOKEN else 0.0
_bist_cache: dict[str, Any] = {"at": 0.0, "items": []}
_viop_cache: dict[str, Any] = {"at": 0.0, "items": []}
_capability_cache: dict[str, Any] = {"at": 0.0, "value": None}
_scanner_fetch_semaphore = asyncio.Semaphore(SCANNER_CONCURRENCY)
_scanner_rate_lock = asyncio.Lock()
_scanner_next_allowed_at = 0.0
_provider_quota_metrics: dict[str, Any] = {
    "accepted": 0, "delayed": 0, "rateLimited": 0, "retries": 0, "cooldownExtensions": 0,
    "waitMs": 0, "cacheHit": 0, "cacheMiss": 0,
    "distributedWaitMs": 0, "distributedGatePass": 0, "distributedCooldownExtensions": 0,
    "byClass": {"BARS": 0, "TICK": 0, "QUOTE": 0, "METADATA": 0},
}
_redis_quota_client: Any | None = None
_bar_series_cache: dict[tuple[str, str, str], dict[str, Any]] = {}
_bar_series_locks: dict[tuple[str, str, str], asyncio.Lock] = {}
_viop_contract_cache: dict[str, Any] = {"at": 0.0, "items": [], "universeAsOf": 0}
_snapshot_batch_lock = asyncio.Lock()
_snapshot_batch_tasks: dict[str, asyncio.Task] = {}
_snapshot_batch_completed: dict[str, tuple[float, dict[str, Any]]] = {}
_snapshot_batch_metrics: dict[str, int] = {"started": 0, "joined": 0, "completedHit": 0}
_bar_cache_persist_metrics: dict[str, int] = {"loads": 0, "writes": 0, "errors": 0, "prewarmSuccess": 0, "prewarmFailure": 0}
_attestation_sequence = 0
_attestation_lock = asyncio.Lock()
_attestation_server_epoch = secrets.token_urlsafe(24)
_attestation_server_epoch_created_at = int(time.time() * 1000)
_attestation_recent_nonces: dict[str, int] = {}


def _parse_attestation_keys() -> dict[tuple[str, int], str]:
    if not ATTESTATION_PRIVATE_KEYS_JSON:
        return {}
    try:
        raw = json.loads(ATTESTATION_PRIVATE_KEYS_JSON)
    except json.JSONDecodeError as exc:
        raise ValueError("ATTESTATION_PRIVATE_KEYS_JSON must be valid JSON") from exc
    if not isinstance(raw, dict):
        raise ValueError("ATTESTATION_PRIVATE_KEYS_JSON must be an object")
    out: dict[tuple[str, int], str] = {}
    for key_id, value in raw.items():
        kid = str(key_id).strip()
        if not kid or len(kid) > 64 or not isinstance(value, dict):
            raise ValueError("ATTESTATION_PRIVATE_KEYS_JSON entry invalid")
        try:
            generation = int(value.get("generation", 0))
        except (TypeError, ValueError):
            generation = 0
        private_key_b64 = str(value.get("privateKeyBase64", "")).strip()
        if generation <= 0 or not private_key_b64:
            raise ValueError("ATTESTATION_PRIVATE_KEYS_JSON entry missing generation/privateKeyBase64")
        out[(kid, generation)] = private_key_b64
    return out


def _load_attestation_private_key(key_id: str, generation: int):
    keys = _parse_attestation_keys()
    encoded = keys.get((key_id, generation))
    if not encoded:
        raise RuntimeError("Requested attestation key is not configured")
    try:
        der = base64.b64decode(encoded, validate=True)
        from cryptography.hazmat.primitives.serialization import load_der_private_key
        private_key = load_der_private_key(der, password=None)
        from cryptography.hazmat.primitives.asymmetric import ec
        if not isinstance(private_key, ec.EllipticCurvePrivateKey):
            raise ValueError("not an EC private key")
        return private_key
    except Exception as exc:
        raise RuntimeError("Attestation private key is invalid") from exc


def _v538_canonical(envelope: dict[str, Any]) -> str:
    return "\n".join([
        "schema=V538_ATTESTATION_V1",
        f"snapshotHash={str(envelope['snapshotHash']).strip().lower()}",
        f"requestId={str(envelope['requestId']).strip()}",
        f"snapshotId={str(envelope['snapshotId']).strip()}",
        f"providerId={str(envelope['providerId']).strip()}",
        f"attestationKeyId={str(envelope['attestationKeyId']).strip()}",
        f"attestationKeyGeneration={int(envelope['attestationKeyGeneration'])}",
        f"serverEpoch={str(envelope['serverEpoch']).strip()}",
        f"serverEpochCreatedAt={int(envelope['serverEpochCreatedAt'])}",
        f"serverTime={int(envelope['serverTime'])}",
        f"generatedAt={int(envelope['generatedAt'])}",
        f"serverSequence={int(envelope['serverSequence'])}",
        f"nonce={str(envelope['nonce']).strip()}",
        f"engine={str(envelope['calculationEngineVersion']).strip()}",
        f"issuedAt={int(envelope['issuedAt'])}",
        f"expiresAt={int(envelope['expiresAt'])}",
    ])


def _prune_attestation_nonces(now_ms: int) -> None:
    for nonce, expires_at in list(_attestation_recent_nonces.items()):
        if expires_at <= now_ms:
            _attestation_recent_nonces.pop(nonce, None)


async def _sign_v538_attestation(
    *,
    snapshot_hash: str,
    request_id: str,
    snapshot_id: str,
    calculation_engine_version: str,
    generated_at: int,
    server_time: int | None = None,
) -> dict[str, Any]:
    """Create a V5.3.8-compatible ECDSA envelope without enabling realtime capability.

    This helper is production key-rotation/replay infrastructure only. `attestationReady` remains
    false until a real realtime snapshot engine and end-to-end Android verification pass.
    """
    if not (ATTESTATION_PRIVATE_KEYS_JSON and ATTESTATION_ACTIVE_KEY_ID and ATTESTATION_ACTIVE_KEY_GENERATION > 0):
        raise HTTPException(status_code=503, detail="ATTESTATION_NOT_CONFIGURED")
    if not snapshot_hash or len(snapshot_hash.strip()) < 32 or not request_id.strip() or not snapshot_id.strip():
        raise HTTPException(status_code=400, detail="ATTESTATION_ENVELOPE_INVALID")
    global _attestation_sequence
    async with _attestation_lock:
        now_ms = int(server_time or time.time() * 1000)
        _prune_attestation_nonces(now_ms)
        _attestation_sequence += 1
        nonce = secrets.token_urlsafe(24)
        while nonce in _attestation_recent_nonces:
            nonce = secrets.token_urlsafe(24)
        expires_at = now_ms + ATTESTATION_TTL_MS
        _attestation_recent_nonces[nonce] = expires_at
        envelope = {
            "snapshotHash": snapshot_hash.strip().lower(),
            "requestId": request_id.strip(),
            "snapshotId": snapshot_id.strip(),
            "providerId": ATTESTATION_PROVIDER_ID,
            "attestationKeyId": ATTESTATION_ACTIVE_KEY_ID,
            "attestationKeyGeneration": ATTESTATION_ACTIVE_KEY_GENERATION,
            "serverEpoch": _attestation_server_epoch,
            "serverEpochCreatedAt": _attestation_server_epoch_created_at,
            "serverTime": now_ms,
            "generatedAt": int(generated_at),
            "serverSequence": _attestation_sequence,
            "nonce": nonce,
            "calculationEngineVersion": calculation_engine_version.strip(),
            "issuedAt": now_ms,
            "expiresAt": expires_at,
        }
        private_key = _load_attestation_private_key(ATTESTATION_ACTIVE_KEY_ID, ATTESTATION_ACTIVE_KEY_GENERATION)
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec
        signature = private_key.sign(_v538_canonical(envelope).encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        envelope.update({
            "attestationAlgorithm": "SHA256withECDSA",
            "attestationSignature": base64.b64encode(signature).decode("ascii"),
        })
        return envelope


async def get_upstream_access_token(force_refresh: bool = False) -> str:
    global _cached_access_token, _cached_access_token_expires_at
    if not force_refresh and _cached_access_token and time.time() < _cached_access_token_expires_at - 60:
        return _cached_access_token
    if not TRADEWIZE_API_KEY:
        if UPSTREAM_ACCESS_TOKEN:
            return UPSTREAM_ACCESS_TOKEN
        raise HTTPException(status_code=503, detail="UPSTREAM_NOT_CONFIGURED")

    async with _token_lock:
        if not force_refresh and _cached_access_token and time.time() < _cached_access_token_expires_at - 60:
            return _cached_access_token
        timeout = httpx.Timeout(15.0, connect=8.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(
                f"{UPSTREAM}/oauth/token",
                headers={"X-API-Key": TRADEWIZE_API_KEY, "Accept": "application/json", "Content-Type": "application/json"},
                json={"grant_type": "api_key"},
            )
        if response.status_code >= 400:
            raise HTTPException(status_code=502, detail=f"UPSTREAM_AUTH_HTTP_{response.status_code}: {response.text[:500]}")
        payload = response.json()
        token = payload.get("access_token") if isinstance(payload, dict) else None
        if not token:
            raise HTTPException(status_code=502, detail="UPSTREAM_AUTH_RESPONSE_INVALID")
        try:
            expires_in = max(300, int(payload.get("expires_in", 86400)))
        except (TypeError, ValueError):
            expires_in = 86400
        _cached_access_token = str(token)
        _cached_access_token_expires_at = time.time() + expires_in
        return _cached_access_token


async def _raw_upstream_get(path: str, params: dict[str, Any] | None = None) -> httpx.Response:
    token = await get_upstream_access_token()
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json", "User-Agent": "BorsaTakip-Production-Backend/1.1"}
    timeout = httpx.Timeout(15.0, connect=8.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.get(f"{UPSTREAM}{path}", params=params, headers=headers)
        if response.status_code == 401 and TRADEWIZE_API_KEY:
            token = await get_upstream_access_token(force_refresh=True)
            headers["Authorization"] = f"Bearer {token}"
            response = await client.get(f"{UPSTREAM}{path}", params=params, headers=headers)
    if response.status_code == 429:
        retry_after = response.headers.get("Retry-After", "").strip()
        headers = {"Retry-After": retry_after} if retry_after else None
        raise HTTPException(
            status_code=429,
            detail=f"UPSTREAM_RATE_LIMIT{(': retry-after=' + retry_after) if retry_after else ''}",
            headers=headers,
        )
    if response.status_code >= 400:
        raise HTTPException(status_code=502, detail=f"UPSTREAM_HTTP_{response.status_code}: {response.text[:500]}")
    return response


def _retry_after_seconds(exc: HTTPException, now: datetime | None = None) -> float | None:
    raw = (exc.headers or {}).get("Retry-After", "").strip() if exc.headers else ""
    if not raw:
        return None
    try:
        return max(0.0, float(raw))
    except ValueError:
        try:
            target = parsedate_to_datetime(raw)
            if target.tzinfo is None:
                target = target.replace(tzinfo=timezone.utc)
            current = now or datetime.now(timezone.utc)
            return max(0.0, (target - current).total_seconds())
        except (TypeError, ValueError, OverflowError):
            return None


_DISTRIBUTED_QUOTA_LUA = r"""
local key = KEYS[1]
local weight = tonumber(ARGV[1])
local minute_limit = tonumber(ARGV[2])
local hour_limit = tonumber(ARGV[3])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local cooldown = tonumber(redis.call('HGET', key, 'cooldown') or '0')
if cooldown > now then
  return {0, cooldown - now}
end
local minute_bucket = math.floor(now / 60000)
local hour_bucket = math.floor(now / 3600000)
local stored_minute = tonumber(redis.call('HGET', key, 'minute_bucket') or '-1')
if stored_minute ~= minute_bucket then
  redis.call('HSET', key, 'minute_bucket', minute_bucket, 'minute_weight', 0)
end
local stored_hour = tonumber(redis.call('HGET', key, 'hour_bucket') or '-1')
if stored_hour ~= hour_bucket then
  redis.call('HSET', key, 'hour_bucket', hour_bucket, 'hour_requests', 0)
end
local minute_weight = tonumber(redis.call('HINCRBY', key, 'minute_weight', weight))
local hour_requests = tonumber(redis.call('HINCRBY', key, 'hour_requests', 1))
redis.call('PEXPIRE', key, 7200000)
if minute_weight > minute_limit then
  local wait = 60000 - (now % 60000)
  return {0, wait}
end
if hour_requests > hour_limit then
  local wait = 3600000 - (now % 3600000)
  return {0, wait}
end
return {1, 0}
"""

_DISTRIBUTED_COOLDOWN_LUA = r"""
local key = KEYS[1]
local seconds = tonumber(ARGV[1])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local target = now + math.floor(seconds * 1000)
local current = tonumber(redis.call('HGET', key, 'cooldown') or '0')
if target > current then
  redis.call('HSET', key, 'cooldown', target)
end
redis.call('PEXPIRE', key, 7200000)
return math.max(current, target)
"""


async def _get_redis_quota_client():
    global _redis_quota_client
    if _redis_quota_client is not None:
        return _redis_quota_client
    if not REDIS_URL:
        raise RuntimeError("REDIS_URL is not configured")
    try:
        import redis.asyncio as redis_async
    except ImportError as exc:
        raise RuntimeError("redis package is required for distributed quota mode") from exc
    _redis_quota_client = redis_async.from_url(REDIS_URL, encoding="utf-8", decode_responses=True)
    return _redis_quota_client


async def _distributed_quota_wait(weight: int) -> None:
    if not UPSTREAM_DISTRIBUTED_QUOTA:
        return
    client = await _get_redis_quota_client()
    while True:
        result = await client.eval(
            _DISTRIBUTED_QUOTA_LUA,
            1,
            UPSTREAM_DISTRIBUTED_QUOTA_KEY,
            max(1, int(weight)),
            SCANNER_RATE_LIMIT_WEIGHT_PER_MINUTE,
            SCANNER_REQUESTS_PER_HOUR,
        )
        allowed = bool(int(result[0]))
        wait_ms = max(0, int(result[1]))
        if allowed:
            _provider_quota_metrics["distributedGatePass"] = int(_provider_quota_metrics.get("distributedGatePass", 0)) + 1
            return
        _provider_quota_metrics["distributedWaitMs"] = int(_provider_quota_metrics.get("distributedWaitMs", 0)) + wait_ms
        await asyncio.sleep(max(0.001, wait_ms / 1000.0))


async def _extend_distributed_cooldown(seconds: float) -> None:
    if not UPSTREAM_DISTRIBUTED_QUOTA or seconds <= 0:
        return
    client = await _get_redis_quota_client()
    await client.eval(_DISTRIBUTED_COOLDOWN_LUA, 1, UPSTREAM_DISTRIBUTED_QUOTA_KEY, float(seconds))
    _provider_quota_metrics["distributedCooldownExtensions"] = int(_provider_quota_metrics.get("distributedCooldownExtensions", 0)) + 1


async def _extend_upstream_cooldown(seconds: float) -> None:
    """Push the shared market-data gate forward for every caller after an upstream 429."""
    global _scanner_next_allowed_at
    if seconds <= 0:
        return
    async with _scanner_rate_lock:
        _scanner_next_allowed_at = max(_scanner_next_allowed_at, time.monotonic() + seconds)
        _provider_quota_metrics["cooldownExtensions"] = int(_provider_quota_metrics.get("cooldownExtensions", 0)) + 1
    await _extend_distributed_cooldown(seconds)


async def scanner_upstream_get(path: str, params: dict[str, Any]) -> httpx.Response:
    """Shared market-data pacing + concurrency + bounded 429 retry.

    Retry-After is promoted to a process-wide cooldown instead of sleeping only the coroutine
    that observed the 429. This keeps concurrent bar/recent-tick callers from immediately
    hitting the provider again during its requested backoff window.
    """
    global _scanner_next_allowed_at
    last_error: HTTPException | None = None
    for attempt in range(SCANNER_RETRY_COUNT + 1):
        await _distributed_quota_wait(SCANNER_REQUEST_WEIGHT)
        async with _scanner_fetch_semaphore:
            async with _scanner_rate_lock:
                now = time.monotonic()
                wait_seconds = max(0.0, _scanner_next_allowed_at - now)
                if wait_seconds > 0:
                    _provider_quota_metrics["delayed"] = int(_provider_quota_metrics.get("delayed", 0)) + 1
                    _provider_quota_metrics["waitMs"] = int(_provider_quota_metrics.get("waitMs", 0)) + int(wait_seconds * 1000)
                    await asyncio.sleep(wait_seconds)
                _scanner_next_allowed_at = time.monotonic() + (SCANNER_PACING_MS / 1000.0)
                _provider_quota_metrics["accepted"] = int(_provider_quota_metrics.get("accepted", 0)) + 1
                request_class = _provider_request_class(path)
                by_class = _provider_quota_metrics.setdefault("byClass", {})
                by_class[request_class] = int(by_class.get(request_class, 0)) + 1
            try:
                return await _raw_upstream_get(path, params)
            except HTTPException as exc:
                last_error = exc
                if exc.status_code != 429:
                    raise
                _provider_quota_metrics["rateLimited"] = int(_provider_quota_metrics.get("rateLimited", 0)) + 1
                if attempt < SCANNER_RETRY_COUNT:
                    _provider_quota_metrics["retries"] = int(_provider_quota_metrics.get("retries", 0)) + 1
                retry_after_seconds = _retry_after_seconds(exc) or 0.0
                exponential_seconds = (SCANNER_RETRY_BASE_MS * (2**attempt)) / 1000.0
                await _extend_upstream_cooldown(max(exponential_seconds, retry_after_seconds))
                if attempt >= SCANNER_RETRY_COUNT:
                    raise
        # Do not sleep locally: the next attempt and all peer callers wait on the same
        # shared gate set above. This avoids a race where only the failed coroutine backs off.
    raise last_error or HTTPException(status_code=502, detail="SCANNER_UPSTREAM_UNKNOWN")


async def market_data_upstream_get(path: str, params: dict[str, Any]) -> httpx.Response:
    """Quota-gated TradeWize market-data request used for bars and recent-tick recovery."""
    return await scanner_upstream_get(path, params)


async def bar_upstream_get(path: str, params: dict[str, Any]) -> httpx.Response:
    """All market-data bar requests share one global quota/pacing/retry gate."""
    return await market_data_upstream_get(path, params)


def _provider_request_class(path: str) -> str:
    lowered = path.lower()
    if "/bars" in lowered:
        return "BARS"
    if "recent-ticks" in lowered:
        return "TICK"
    if "last-price" in lowered:
        return "QUOTE"
    return "METADATA"


def _quota_gate_allows_path(path: str) -> bool:
    request_class = _provider_request_class(path)
    if request_class in {"BARS", "TICK"}:
        return True
    return UPSTREAM_QUOTA_SCOPE == "all"


async def upstream_get(path: str, params: dict[str, Any] | None = None) -> httpx.Response:
    """Canonical TradeWize GET surface.

    Production defaults to a conservative `all` quota scope until the provider publishes a
    narrower contract. `bars_only` may be selected explicitly after provider verification.
    OAuth token exchange is intentionally outside this market-data gate.
    """
    safe_params = params or {}
    if _quota_gate_allows_path(path):
        return await scanner_upstream_get(path, safe_params)
    return await _raw_upstream_get(path, safe_params)


def _quota_metrics_snapshot() -> dict[str, Any]:
    by_class = dict(_provider_quota_metrics.get("byClass", {}))
    return {
        "scope": UPSTREAM_QUOTA_SCOPE,
        "accepted": int(_provider_quota_metrics.get("accepted", 0)),
        "delayed": int(_provider_quota_metrics.get("delayed", 0)),
        "rateLimited": int(_provider_quota_metrics.get("rateLimited", 0)),
        "retries": int(_provider_quota_metrics.get("retries", 0)),
        "cooldownExtensions": int(_provider_quota_metrics.get("cooldownExtensions", 0)),
        "waitMs": int(_provider_quota_metrics.get("waitMs", 0)),
        "cacheHit": int(_provider_quota_metrics.get("cacheHit", 0)),
        "cacheMiss": int(_provider_quota_metrics.get("cacheMiss", 0)),
        "distributedWaitMs": int(_provider_quota_metrics.get("distributedWaitMs", 0)),
        "distributedGatePass": int(_provider_quota_metrics.get("distributedGatePass", 0)),
        "distributedCooldownExtensions": int(_provider_quota_metrics.get("distributedCooldownExtensions", 0)),
        "distributedQuotaEnabled": bool(UPSTREAM_DISTRIBUTED_QUOTA),
        "requestWeight": SCANNER_REQUEST_WEIGHT,
        "providerWeightFormulaVerified": False,
    "distributedProviderQuotaConfigured": bool(UPSTREAM_DISTRIBUTED_QUOTA and REDIS_URL),
        "byClass": by_class,
        "ingress": {
            "accepted": int(_ingress_metrics.get("accepted", 0)),
            "rejected": int(_ingress_metrics.get("rejected", 0)),
            "rateLimited429": int(_ingress_metrics.get("rateLimited429", 0)),
            "weightedUnits": int(_ingress_metrics.get("weightedUnits", 0)),
            "bucketCount": len(_ingress_buckets),
        },
        "barCachePersistence": dict(_bar_cache_persist_metrics),
        "snapshotBatchIdempotency": dict(_snapshot_batch_metrics),
    }


def _interval_ms(interval: str) -> int:
    raw = interval.strip().lower()
    if raw.endswith("d"):
        return 86_400_000 * max(1, int(raw[:-1] or "1"))
    if raw.endswith("m"):
        return 60_000 * max(1, int(raw[:-1] or "1"))
    return 60_000


def _cache_freshness_policy(market: str, interval: str, *, session_state: str = "UNKNOWN") -> dict[str, Any]:
    """Single freshness policy for every history path.

    Market/interval/session are explicit inputs even while the production-safe default keeps
    one conservative TTL. This prevents endpoint-specific stale rules from diverging and gives
    one place to tune session-aware TTLs once provider/session semantics are verified.
    """
    normalized_market = (market or "").strip().upper() or "UNKNOWN"
    normalized_interval = (interval or "").strip().lower() or "UNKNOWN"
    normalized_session = (session_state or "UNKNOWN").strip().upper() or "UNKNOWN"
    return {
        "market": normalized_market,
        "interval": normalized_interval,
        "sessionState": normalized_session,
        "ttlSeconds": BAR_CACHE_TTL_SECONDS,
        "policyVersion": "V5416-CACHE-FRESHNESS-1",
    }


def _merge_bars(
    existing: list[dict[str, Any]],
    new_items: list[dict[str, Any]],
    *,
    max_bars: int | None = BAR_CACHE_MAX_BARS,
) -> list[dict[str, Any]]:
    merged: dict[int, dict[str, Any]] = {}
    for row in existing + new_items:
        ts = int(row.get("timestamp", 0) or 0)
        if ts > 0:
            merged[ts] = row
    ordered = [merged[key] for key in sorted(merged)]
    if max_bars is None:
        return ordered
    return ordered[-max(1, max_bars):]


def _prune_bar_cache(now: float, protected_key: tuple[str, str, str] | None = None) -> None:
    """Bound rolling history cache by idle age, key count and total candle count."""
    idle_cutoff = now - BAR_CACHE_IDLE_TTL_SECONDS

    def evict(key: tuple[str, str, str]) -> None:
        _bar_series_cache.pop(key, None)
        lock = _bar_series_locks.get(key)
        if lock is not None and not lock.locked():
            _bar_series_locks.pop(key, None)

    for key, entry in list(_bar_series_cache.items()):
        if key == protected_key:
            continue
        last_access = float(entry.get("lastAccess", entry.get("at", 0.0)))
        if last_access < idle_cutoff:
            evict(key)

    def lru_keys() -> list[tuple[str, str, str]]:
        return [
            key
            for _, key in sorted(
                (
                    (float(entry.get("lastAccess", entry.get("at", 0.0))), key)
                    for key, entry in _bar_series_cache.items()
                    if key != protected_key
                ),
                key=lambda item: item[0],
            )
        ]

    candidates = lru_keys()
    while len(_bar_series_cache) > BAR_CACHE_MAX_KEYS and candidates:
        evict(candidates.pop(0))

    total_bars = sum(len(entry.get("candles") or []) for entry in _bar_series_cache.values())
    if total_bars > BAR_CACHE_MAX_TOTAL_BARS:
        candidates = lru_keys()
        while total_bars > BAR_CACHE_MAX_TOTAL_BARS and candidates:
            key = candidates.pop(0)
            entry = _bar_series_cache.get(key)
            if entry is None:
                continue
            total_bars -= len(entry.get("candles") or [])
            evict(key)

    # Locks whose cache entry was already removed can also be released once idle/unlocked.
    for key, lock in list(_bar_series_locks.items()):
        if key == protected_key or key in _bar_series_cache or lock.locked():
            continue
        _bar_series_locks.pop(key, None)


def _store_bar_cache(
    key: tuple[str, str, str],
    now: float,
    candles: list[dict[str, Any]],
    *,
    request_count_back: int | None = None,
) -> None:
    previous = _bar_series_cache.get(key) or {}
    _bar_series_cache[key] = {
        "at": now,
        "lastAccess": now,
        "candles": candles[-BAR_CACHE_MAX_BARS:],
        "requestCountBack": request_count_back if request_count_back is not None else int(previous.get("requestCountBack", 0) or 0),
    }
    _prune_bar_cache(now, protected_key=key)


def _bar_cache_snapshot_payload() -> dict[str, Any]:
    entries: list[dict[str, Any]] = []
    for (market, symbol, interval), entry in list(_bar_series_cache.items()):
        candles = list(entry.get("candles") or [])[-BAR_CACHE_MAX_BARS:]
        if not candles:
            continue
        entries.append({
            "market": market,
            "symbol": symbol,
            "interval": interval,
            "at": float(entry.get("at", 0.0)),
            "lastAccess": float(entry.get("lastAccess", entry.get("at", 0.0))),
            "requestCountBack": int(entry.get("requestCountBack", 0) or 0),
            "candles": candles,
        })
    return {"version": 1, "savedAt": time.time(), "entries": entries}


def _persist_bar_cache_to_disk() -> bool:
    if not BAR_CACHE_PERSIST_PATH:
        return False
    path = Path(BAR_CACHE_PERSIST_PATH)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(json.dumps(_bar_cache_snapshot_payload(), separators=(",", ":")), encoding="utf-8")
        os.replace(tmp, path)
        _bar_cache_persist_metrics["writes"] = int(_bar_cache_persist_metrics.get("writes", 0)) + 1
        return True
    except OSError:
        _bar_cache_persist_metrics["errors"] = int(_bar_cache_persist_metrics.get("errors", 0)) + 1
        return False


def _load_bar_cache_from_disk() -> int:
    if not BAR_CACHE_PERSIST_PATH:
        return 0
    path = Path(BAR_CACHE_PERSIST_PATH)
    if not path.exists():
        return 0
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        _bar_cache_persist_metrics["errors"] = int(_bar_cache_persist_metrics.get("errors", 0)) + 1
        return 0
    rows = payload.get("entries", []) if isinstance(payload, dict) else []
    if not isinstance(rows, list):
        return 0
    now = time.time()
    loaded = 0
    for row in rows:
        if not isinstance(row, dict):
            continue
        market = str(row.get("market", "")).strip().upper()
        symbol = str(row.get("symbol", "")).strip().upper()
        interval = str(row.get("interval", "")).strip().lower()
        if market not in {"BIST", "VIOP"} or not symbol or interval not in SUPPORTED_SCAN_INTERVALS:
            continue
        try:
            saved_at = float(row.get("at", 0.0))
        except (TypeError, ValueError):
            continue
        if saved_at <= 0 or now - saved_at > BAR_CACHE_IDLE_TTL_SECONDS:
            continue
        candles = _normalize_bars({"bars": row.get("candles", [])})
        if not candles:
            continue
        key = (market, symbol, interval)
        _bar_series_cache[key] = {
            "at": saved_at,
            "lastAccess": now,
            "candles": candles[-BAR_CACHE_MAX_BARS:],
            "requestCountBack": int(row.get("requestCountBack", 0) or 0),
        }
        loaded += 1
    _prune_bar_cache(now)
    if loaded:
        _bar_cache_persist_metrics["loads"] = int(_bar_cache_persist_metrics.get("loads", 0)) + loaded
    return loaded


async def _bar_cache_persist_loop() -> None:
    while True:
        await asyncio.sleep(BAR_CACHE_PERSIST_INTERVAL_SECONDS)
        _persist_bar_cache_to_disk()


async def _prewarm_bar_cache_once() -> None:
    symbols: list[str] = list(BAR_CACHE_PREWARM_SYMBOLS)
    if BAR_CACHE_PREWARM_BIST_UNIVERSE:
        try:
            discovered = await discover_bist_quotes(force=True)
            symbols.extend(str(item.get("symbol", "")).strip().upper() for item in discovered if isinstance(item, dict))
        except Exception:
            _bar_cache_persist_metrics["prewarmFailure"] = int(_bar_cache_persist_metrics.get("prewarmFailure", 0)) + 1
    deduped: list[str] = []
    seen: set[str] = set()
    for symbol in symbols:
        if not symbol or symbol in seen:
            continue
        seen.add(symbol)
        deduped.append(symbol)
        if len(deduped) >= BAR_CACHE_PREWARM_MAX_SYMBOLS:
            break
    for symbol in deduped:
        if not symbol.replace("_", "").isalnum() or len(symbol) > 32:
            _bar_cache_persist_metrics["prewarmFailure"] = int(_bar_cache_persist_metrics.get("prewarmFailure", 0)) + 1
            continue
        for interval_raw in BAR_CACHE_PREWARM_INTERVALS:
            try:
                interval = normalize_interval(interval_raw)
                await _fetch_bist_history(symbol, interval, "1d")
                _bar_cache_persist_metrics["prewarmSuccess"] = int(_bar_cache_persist_metrics.get("prewarmSuccess", 0)) + 1
            except Exception:
                _bar_cache_persist_metrics["prewarmFailure"] = int(_bar_cache_persist_metrics.get("prewarmFailure", 0)) + 1
    if BAR_CACHE_PERSIST_PATH and deduped:
        _persist_bar_cache_to_disk()


async def _cached_market_bars(
    market: str,
    symbol: str,
    canonical: str,
    upstream_interval: str,
    *,
    range_value: str = "1y",
    from_ms: int | None = None,
    to_ms: int | None = None,
) -> list[dict[str, Any]]:
    """Rolling per-symbol/interval cache with incremental window refresh.

    The cache never fabricates candles. Missing coverage triggers an upstream request through
    the same global bar quota manager used by scanner calls.
    """
    key = (market.upper(), symbol.upper(), canonical.lower())
    lock = _bar_series_locks.setdefault(key, asyncio.Lock())
    async with lock:
        now = time.time()
        _prune_bar_cache(now, protected_key=key)
        entry = _bar_series_cache.get(key) or {"at": 0.0, "lastAccess": 0.0, "candles": []}
        cached = list(entry.get("candles") or [])
        if key in _bar_series_cache:
            _bar_series_cache[key]["lastAccess"] = now
        freshness_policy = _cache_freshness_policy(market, canonical)
        fresh = bool(cached) and now - float(entry.get("at", 0.0)) <= int(freshness_policy["ttlSeconds"])

        # range=max is a real range request. The response is intentionally not clipped to
        # BAR_CACHE_MAX_BARS; only the rolling in-memory cache is bounded.
        full_range_request = range_value == "max" and from_ms is None and to_ms is None
        if full_range_request:
            from_ms = ALL_TIME_HISTORY_START_MS  # default ~= Unix epoch; cache remains bounded separately
            to_ms = int(time.time() * 1000)

        if from_ms is not None and to_ms is not None:
            if from_ms <= 0 or to_ms <= from_ms:
                raise HTTPException(status_code=400, detail="HISTORY_ERROR: Geçersiz zaman aralığı.")
            covered_from = int(cached[0]["timestamp"]) if cached else 0
            covered_to = int(cached[-1]["timestamp"]) if cached else 0
            if fresh and covered_from <= from_ms and covered_to >= to_ms - _interval_ms(canonical):
                # A bounded cache cannot prove full historical coverage for range=max.
                if not full_range_request:
                    _provider_quota_metrics["cacheHit"] = int(_provider_quota_metrics.get("cacheHit", 0)) + 1
                    return [row for row in cached if from_ms <= int(row["timestamp"]) <= to_ms]

            # Incremental append when the existing series overlaps the requested window.
            fetch_from = from_ms
            if cached and covered_from <= from_ms <= covered_to and covered_to < to_ms:
                fetch_from = max(from_ms, covered_to + 1)
            params: dict[str, Any] = {
                "symbol": symbol.upper(),
                "interval": upstream_interval,
                "includeOpenBar": "false",
                "fromUtc": datetime.fromtimestamp(fetch_from / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z"),
                "toUtc": datetime.fromtimestamp(to_ms / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z"),
            }
            _provider_quota_metrics["cacheMiss"] = int(_provider_quota_metrics.get("cacheMiss", 0)) + 1
            response = await bar_upstream_get("/api/v1/market-data/bars", params)
            fetched = _normalize_bars(response.json())
            combined_full = _merge_bars(cached if fetch_from > from_ms else [], fetched, max_bars=None)
            _store_bar_cache(key, now, combined_full)
            return [row for row in combined_full if from_ms <= int(row["timestamp"]) <= to_ms]

        try:
            units, unit = int(range_value[:-1]), range_value[-1]
        except (TypeError, ValueError, IndexError):
            units, unit = 1, "y"
        if canonical == "1d":
            count_back = min(BAR_CACHE_MAX_BARS, max(50, units * (365 if unit == "y" else 30 if unit == "m" else 1)))
        else:
            base = units * (240 if unit == "d" else 30 if unit == "m" else 5)
            count_back = min(BAR_CACHE_MAX_BARS, max(50, base * (2 if canonical == "10m" else 1)))
        cached_request_count = int(entry.get("requestCountBack", 0) or 0)
        if fresh and (len(cached) >= min(count_back, BAR_CACHE_MAX_BARS) or cached_request_count >= count_back):
            if key in _bar_series_cache:
                _bar_series_cache[key]["lastAccess"] = now
            _provider_quota_metrics["cacheHit"] = int(_provider_quota_metrics.get("cacheHit", 0)) + 1
            return cached[-count_back:]
        _provider_quota_metrics["cacheMiss"] = int(_provider_quota_metrics.get("cacheMiss", 0)) + 1
        response = await bar_upstream_get(
            "/api/v1/market-data/bars",
            {"symbol": symbol.upper(), "interval": upstream_interval, "countBack": count_back, "includeOpenBar": "false"},
        )
        fetched = _normalize_bars(response.json())
        combined = _merge_bars(cached, fetched, max_bars=None)
        _store_bar_cache(key, now, combined, request_count_back=count_back)
        return combined[-count_back:]


def unwrap_json(payload: Any) -> Any:
    if isinstance(payload, dict) and isinstance(payload.get("data"), (dict, list)):
        return payload["data"]
    return payload


def _unwrap_symbol_map(payload: Any) -> dict[str, Any]:
    value = unwrap_json(payload)
    return value if isinstance(value, dict) else {}


def normalize_timestamp(value: Any) -> int:
    if value is None:
        return 0
    try:
        number = int(value)
        return number * 1000 if number < 10_000_000_000 else number
    except (TypeError, ValueError):
        return 0


def _bar_timestamp_ms(bar: dict[str, Any]) -> int:
    raw = bar.get("timestamp", bar.get("timestampMs", bar.get("time", bar.get("openTimeUnix", 0))))
    ts = normalize_timestamp(raw)
    if ts > 0:
        return ts
    for key in ("timeUtc", "openTimeUtc", "closeTimeUtc"):
        value = bar.get(key)
        if isinstance(value, str) and value.strip():
            try:
                return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)
            except ValueError:
                continue
    return 0


def freshness(timestamp_ms: int) -> tuple[bool, int | None]:
    if timestamp_ms <= 0:
        return False, None
    age_ms = max(0, int(time.time() * 1000) - timestamp_ms)
    return age_ms <= MAX_AGE_MS, int(age_ms / 1000)


def extract_price_record(symbol: str, raw: Any) -> dict[str, Any]:
    item = raw if isinstance(raw, dict) else {}
    try:
        price = float(item.get("price", item.get("last", item.get("lastPrice", 0))))
    except (TypeError, ValueError):
        price = 0.0
    ts = normalize_timestamp(item.get("timestamp", item.get("exchangeTimestamp", item.get("timestampMs"))))
    realtime, delay = freshness(ts)
    volume = _positive_float(item.get("volume"))
    open_interest = _positive_int(item.get("openInterest"))
    return {
        "symbol": symbol.upper(),
        "price": price,
        "timestamp": ts,
        "realtime": realtime,
        "delaySeconds": delay,
        "currentSessionIncluded": realtime,
        "source": "TradeWize",
        # Only canonical upstream field names are accepted here. No aliases or synthetic values.
        "volume": volume,
        "openInterest": open_interest,
    }


def normalize_interval(raw: str | None) -> str:
    value = (raw or DEFAULT_SCAN_INTERVAL).strip().lower()
    if value == "1day":
        value = "1d"
    if value not in SUPPORTED_SCAN_INTERVALS:
        raise HTTPException(status_code=400, detail=f"SCANNER_ERROR: Desteklenmeyen timeframe: {raw}")
    return value


def upstream_interval_for(canonical: str) -> tuple[str, bool]:
    if canonical == "1d":
        return "1D", False
    if canonical == "10m":
        return "5m", True
    return canonical, False


def _normalize_bars(payload: Any) -> list[dict[str, Any]]:
    value = unwrap_json(payload)
    if isinstance(value, dict):
        bars = value.get("bars", value.get("items", []))
    elif isinstance(value, list):
        bars = value
    else:
        bars = []
    normalized: list[dict[str, Any]] = []
    for bar in bars:
        if not isinstance(bar, dict):
            continue
        try:
            o, h, low, c = float(bar.get("open")), float(bar.get("high")), float(bar.get("low")), float(bar.get("close"))
            volume = float(bar.get("volume", 0))
        except (TypeError, ValueError):
            continue
        ts = _bar_timestamp_ms(bar)
        if ts <= 0 or min(o, h, low, c) <= 0 or volume < 0:
            continue
        normalized.append({"timestamp": ts, "open": o, "high": h, "low": low, "close": c, "volume": volume})
    normalized.sort(key=lambda item: item["timestamp"])
    return normalized


def _resample_5m_to_10m(bars: list[dict[str, Any]]) -> list[dict[str, Any]]:
    bucket_ms = 10 * 60 * 1000
    five_ms = 5 * 60 * 1000
    groups: dict[int, list[dict[str, Any]]] = {}
    for bar in bars:
        bucket = int(bar["timestamp"]) // bucket_ms
        groups.setdefault(bucket, []).append(bar)
    result: list[dict[str, Any]] = []
    for _, items in sorted(groups.items()):
        items.sort(key=lambda item: item["timestamp"])
        if len(items) != 2 or int(items[1]["timestamp"]) - int(items[0]["timestamp"]) != five_ms:
            continue
        result.append({
            "timestamp": int(items[0]["timestamp"]),
            "open": float(items[0]["open"]),
            "high": max(float(item["high"]) for item in items),
            "low": min(float(item["low"]) for item in items),
            "close": float(items[-1]["close"]),
            "volume": sum(float(item.get("volume", 0)) for item in items),
        })
    return result


async def discover_bist_quotes(force: bool = False) -> list[dict[str, Any]]:
    now = time.time()
    if not force and _bist_cache["items"] and now - float(_bist_cache["at"]) < 1.0:
        return list(_bist_cache["items"])
    response = await upstream_get("/api/v1/market-data/last-price/details", {"all": "true"})
    records = _unwrap_symbol_map(response.json())
    items: list[dict[str, Any]] = []
    for symbol, raw in records.items():
        if not isinstance(symbol, str):
            continue
        normalized = extract_price_record(symbol, raw)
        items.append({**normalized, "market": "BIST", "assetType": "STOCK", "name": symbol.upper()})
    items.sort(key=lambda item: item["symbol"])
    _bist_cache.update({"at": now, "items": items})
    return list(items)


async def discover_viop_quotes(force: bool = False) -> list[dict[str, Any]]:
    now = time.time()
    if not force and _viop_cache["items"] and now - float(_viop_cache["at"]) < 1.0:
        return list(_viop_cache["items"])
    response = await upstream_get("/api/v1/market-data/viop/last-price/details", {"all": "true"})
    records = _unwrap_symbol_map(response.json())
    items: list[dict[str, Any]] = []
    for symbol, raw in records.items():
        if not isinstance(symbol, str):
            continue
        normalized = extract_price_record(symbol, raw)
        if normalized["price"] <= 0:
            continue
        items.append({**normalized, "market": "VIOP", "assetType": "FUTURE", "name": symbol.upper(), "underlying": symbol.upper()})
    items.sort(key=lambda item: item["symbol"])
    _viop_cache.update({"at": now, "items": items})
    return list(items)


def _positive_float(value: Any) -> float | None:
    try:
        number = float(value)
        return number if number > 0 else None
    except (TypeError, ValueError):
        return None


def _positive_int(value: Any) -> int | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number <= 0 or not number.is_integer():
        return None
    return int(number)


def _metadata_timestamp(raw: dict[str, Any], *keys: str) -> int | None:
    for key in keys:
        if key in raw:
            ts = normalize_timestamp(raw.get(key))
            if ts > 0:
                return ts
    return None



def _normalize_expiry(value: Any) -> str | None:
    raw = str(value or "").strip()
    if not raw:
        return None
    candidates = [raw]
    if len(raw) >= 7 and raw[4] == "-":
        candidates.insert(0, raw[:7])
    if len(raw) == 6 and raw.isdigit():
        candidates.insert(0, f"{raw[:4]}-{raw[4:]}")
    if len(raw) == 7 and raw[2] in {"/", "."}:
        candidates.insert(0, f"{raw[3:]}-{raw[:2]}")
    for candidate in candidates:
        if len(candidate) != 7 or candidate[4] != "-":
            continue
        year, month = candidate.split("-", 1)
        if year.isdigit() and month.isdigit() and 2000 <= int(year) <= 2200 and 1 <= int(month) <= 12:
            return f"{int(year):04d}-{int(month):02d}"
    return None

def _normalize_viop_contract_metadata(symbol: str, raw: dict[str, Any]) -> dict[str, Any] | None:
    safe = symbol.strip().upper()
    underlying = str(raw.get("underlying") or raw.get("underlyingSymbol") or raw.get("baseSymbol") or "").strip().upper()
    expiry = _normalize_expiry(raw.get("expiry") or raw.get("expiryMonth") or raw.get("maturity"))
    tick_size = _positive_float(raw.get("tickSize") or raw.get("tick_size") or raw.get("minPriceIncrement"))
    multiplier = _positive_float(raw.get("multiplier") or raw.get("contractMultiplier") or raw.get("contractSize"))
    last_trading_at = _metadata_timestamp(raw, "lastTradingAt", "lastTradingTime", "lastTradeDate", "lastTradingTimestamp")
    expiry_at = _metadata_timestamp(raw, "expiryAt", "expiryTimestamp", "maturityAt")
    contract_type = str(raw.get("contractType") or raw.get("type") or "").strip().upper()
    if contract_type not in {"FUTURE", "FUTURES", "FUT"}:
        return None
    if not safe or not underlying or expiry is None or tick_size is None or multiplier is None or last_trading_at is None:
        return None
    return {
        "symbol": safe,
        "underlying": underlying,
        "expiry": expiry,
        "contractType": "FUTURE",
        "tickSize": tick_size,
        "multiplier": multiplier,
        "lastTradingAt": last_trading_at,
        "expiryAt": expiry_at,
        "exchangeTimezone": str(raw.get("exchangeTimezone") or "Europe/Istanbul"),
        "settlementType": str(raw.get("settlementType") or "").strip() or None,
        "currency": str(raw.get("currency") or "TRY").strip() or "TRY",
        # Liquidity data is never fabricated. Only canonical provider fields are propagated.
        "volume": _positive_float(raw.get("volume")),
        "openInterest": _positive_int(raw.get("openInterest")),
    }


async def discover_viop_contract_metadata(force: bool = False) -> tuple[list[dict[str, Any]], int]:
    if not VIOP_CONTRACT_METADATA_PATH:
        raise HTTPException(
            status_code=503,
            detail={"code": "VIOP_CONTRACT_METADATA_NOT_CONFIGURED", "message": "Doğrulanmış VİOP kontrat metadata endpointi yapılandırılmadı."},
        )
    now = time.time()
    if not force and _viop_contract_cache["items"] and now - float(_viop_contract_cache["at"]) < CAPABILITY_CACHE_SECONDS:
        return list(_viop_contract_cache["items"]), int(_viop_contract_cache["universeAsOf"])
    response = await upstream_get(VIOP_CONTRACT_METADATA_PATH, {"all": "true"})
    payload = unwrap_json(response.json())
    universe_as_of = 0
    declared_total: int | None = None
    declared_has_more: bool | None = None
    raw_items: list[tuple[str, dict[str, Any]]] = []
    if isinstance(payload, dict):
        universe_as_of = normalize_timestamp(payload.get("universeAsOf") or payload.get("asOf") or payload.get("timestamp"))
        try:
            if payload.get("totalCount") is not None:
                declared_total = int(payload.get("totalCount"))
        except (TypeError, ValueError):
            declared_total = None
        if payload.get("hasMore") is not None:
            declared_has_more = bool(payload.get("hasMore"))
        candidate = payload.get("items") or payload.get("contracts") or payload.get("instruments")
        if isinstance(candidate, list):
            for row in candidate:
                if isinstance(row, dict):
                    symbol = str(row.get("symbol") or row.get("contractCode") or row.get("code") or "").strip().upper()
                    if symbol:
                        raw_items.append((symbol, row))
        else:
            for key, row in payload.items():
                if isinstance(row, dict) and key not in {"data", "meta"}:
                    raw_items.append((str(key).strip().upper(), row))
    elif isinstance(payload, list):
        for row in payload:
            if isinstance(row, dict):
                symbol = str(row.get("symbol") or row.get("contractCode") or row.get("code") or "").strip().upper()
                if symbol:
                    raw_items.append((symbol, row))

    now_ms = int(time.time() * 1000)
    if VIOP_METADATA_REQUIRE_UNIVERSE_AS_OF and universe_as_of <= 0:
        raise HTTPException(status_code=503, detail={"code": "VIOP_CONTRACT_METADATA_TIMESTAMP_MISSING", "message": "VİOP metadata kaynağı universeAsOf/asOf sağlamadı; güncellik uydurulmaz."})
    if universe_as_of > 0:
        age_seconds = max(0, (now_ms - universe_as_of) // 1000)
        if age_seconds > VIOP_METADATA_MAX_AGE_SECONDS:
            raise HTTPException(status_code=503, detail={"code": "VIOP_CONTRACT_METADATA_STALE", "ageSeconds": age_seconds})
    if VIOP_METADATA_REQUIRE_COMPLETE:
        if declared_has_more is True:
            raise HTTPException(status_code=503, detail={"code": "VIOP_CONTRACT_METADATA_PARTIAL", "message": "Provider metadata cevabı ek sayfa olduğunu bildiriyor."})
        if declared_total is not None and declared_total != len(raw_items):
            raise HTTPException(status_code=503, detail={"code": "VIOP_CONTRACT_METADATA_PARTIAL", "declaredTotal": declared_total, "received": len(raw_items)})

    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for symbol, row in raw_items:
        item = _normalize_viop_contract_metadata(symbol, row)
        if item is None:
            continue
        if item["symbol"] in seen:
            raise HTTPException(status_code=503, detail={"code": "VIOP_CONTRACT_METADATA_DUPLICATE", "symbol": item["symbol"]})
        seen.add(item["symbol"])
        last_trading_at = int(item["lastTradingAt"])
        if last_trading_at < now_ms:
            continue
        days_to_last_trading = max(0, (last_trading_at - now_ms) // 86_400_000)
        item["daysToLastTrading"] = int(days_to_last_trading)
        item["nearExpiry"] = bool(days_to_last_trading <= VIOP_NEAR_EXPIRY_DAYS)
        item["dataQualityWarning"] = "NEAR_EXPIRY" if item["nearExpiry"] else None
        normalized.append(item)
    if not normalized:
        raise HTTPException(status_code=503, detail={"code": "VIOP_CONTRACT_METADATA_INVALID", "message": "Upstream VİOP metadata zorunlu FUTURE alanlarını sağlamadı veya aktif kontrat kalmadı."})
    normalized.sort(key=lambda item: (item["underlying"], item["lastTradingAt"], item["symbol"]))
    _viop_contract_cache.update({"at": now, "items": normalized, "universeAsOf": universe_as_of})
    return list(normalized), universe_as_of


async def _require_viop_contract(symbol: str) -> dict[str, Any]:
    """Return verified VİOP contract metadata or fail closed.

    Quote/history/scanner paths must never promote an arbitrary upstream symbol to a VİOP
    contract. A symbol becomes eligible only after it exists in the verified metadata universe.
    """
    safe = symbol.strip().upper()
    if not safe or len(safe) > 64:
        raise HTTPException(status_code=400, detail="VIOP_CONTRACT_SYMBOL_INVALID")
    metadata, _ = await discover_viop_contract_metadata()
    for item in metadata:
        if str(item.get("symbol") or "").strip().upper() == safe:
            return item
    raise HTTPException(
        status_code=404,
        detail={"code": "VIOP_CONTRACT_NOT_FOUND", "symbol": safe, "message": "Sembol doğrulanmış VİOP metadata evreninde değil."},
    )


async def _recent_tick(symbol: str) -> dict[str, Any] | None:
    response = await market_data_upstream_get("/api/v1/market-data/recent-ticks", {"symbols": symbol, "seconds": 5})
    payload = unwrap_json(response.json())
    ticks = payload.get("ticks", []) if isinstance(payload, dict) else []
    candidates: list[tuple[int, float]] = []
    for tick in ticks:
        if not isinstance(tick, dict) or str(tick.get("symbol", "")).strip().upper() != symbol:
            continue
        try:
            price = float(tick.get("price", 0))
        except (TypeError, ValueError):
            price = 0.0
        ts = normalize_timestamp(tick.get("timestampMs", tick.get("timestamp")))
        if price > 0 and ts > 0:
            candidates.append((ts, price))
    if not candidates:
        return None
    ts, price = max(candidates, key=lambda item: item[0])
    realtime, delay = freshness(ts)
    return {
        "symbol": symbol,
        "price": price,
        "timestamp": ts,
        "realtime": realtime,
        "delaySeconds": delay,
        "currentSessionIncluded": realtime,
        "source": "TradeWize/recent-ticks",
    }


async def resolve_bist_quote(symbol: str, candidate: dict[str, Any] | None = None) -> dict[str, Any]:
    """Single BIST quote resolver used by preflight, single quote and batch scans."""
    safe = symbol.strip().upper()
    if not safe or len(safe) > 64:
        raise HTTPException(status_code=400, detail="BIST_QUOTE_ERROR: Geçersiz sembol.")
    if candidate is None:
        response = await upstream_get("/api/v1/market-data/last-price/details", {"symbols": safe})
        records = _unwrap_symbol_map(response.json())
        if safe not in records:
            raise HTTPException(status_code=404, detail="BIST_QUOTE_ERROR: Sembol bulunamadı.")
        normalized = extract_price_record(safe, records[safe])
    else:
        normalized = dict(candidate)
        normalized["symbol"] = safe
    if float(normalized.get("price", 0) or 0) <= 0 or int(normalized.get("timestamp", 0) or 0) <= 0:
        raise HTTPException(status_code=503, detail="BIST_QUOTE_ERROR: Kullanılabilir fiyat/zaman damgası yok.")
    if bool(normalized.get("realtime", False)):
        return normalized
    tick = await _recent_tick(safe)
    return tick if tick and tick["realtime"] else normalized


async def _fetch_bist_history(
    symbol: str,
    interval: str = "1d",
    range_value: str = "1y",
    from_ms: int | None = None,
    to_ms: int | None = None,
    scanner: bool = False,
) -> list[dict[str, Any]]:
    # scanner is retained for wire/backward compatibility; every bar path now uses the same limiter.
    del scanner
    canonical = normalize_interval(interval)
    safe = symbol.strip().upper()
    if not safe:
        raise HTTPException(status_code=400, detail="HISTORY_ERROR: Geçersiz sembol.")
    upstream_interval, resample_10m = upstream_interval_for(canonical)
    bars = await _cached_market_bars(
        "BIST", safe, canonical, upstream_interval, range_value=range_value, from_ms=from_ms, to_ms=to_ms
    )
    if resample_10m:
        bars = _resample_5m_to_10m(bars)
    if not bars:
        raise HTTPException(status_code=503, detail="INSUFFICIENT_HISTORY: Backend kapanmış BIST mum döndürmedi.")
    return bars


@app.post("/v1/auth/session")
async def create_backend_session(
    authorization: str | None = Header(default=None),
    x_install_id: str | None = Header(default=None, alias="X-Install-ID"),
):
    # Bootstrap deliberately requires the long-lived key; session tokens cannot mint new sessions.
    if not APP_API_KEY or authorization != f"Bearer {APP_API_KEY}":
        raise HTTPException(status_code=401, detail="BACKEND_AUTH_INVALID")
    installation = (x_install_id or "").strip()
    if not installation or len(installation) > 128:
        raise HTTPException(status_code=400, detail="INSTALLATION_ID_REQUIRED")
    token, exp = await _issue_session_token_shared(installation)
    return {
        "tokenType": "BorsaSession",
        "accessToken": token,
        "expiresAt": exp * 1000,
        "expiresIn": SESSION_TOKEN_TTL_SECONDS,
        "installationIdHash": hashlib.sha256(installation.encode("utf-8")).hexdigest()[:16],
    }


@app.post("/v1/auth/revoke-installation")
async def revoke_backend_installation_sessions(
    authorization: str | None = Header(default=None),
    x_install_id: str | None = Header(default=None, alias="X-Install-ID"),
):
    # Revocation is an operator/bootstrap action. A session token cannot revoke another subject.
    if not APP_API_KEY or authorization != f"Bearer {APP_API_KEY}":
        raise HTTPException(status_code=401, detail="BACKEND_BOOTSTRAP_AUTH_INVALID")
    installation_id = (x_install_id or "").strip()
    if not installation_id or len(installation_id) > 128:
        raise HTTPException(status_code=400, detail="INSTALLATION_ID_REQUIRED")
    epoch = await _revoke_installation_sessions(installation_id)
    return {
        "ok": True,
        "subject": _session_subject(installation_id)[:24],
        "subjectEpoch": epoch,
        "revokedAt": int(time.time() * 1000),
    }


def _process_resource_metrics() -> dict[str, float | int | None]:
    """Return secret-free process metrics suitable for benchmark evidence.

    CPU time is monotonic process CPU seconds. RSS is best-effort and Linux-friendly;
    unsupported platforms return ``None`` rather than inventing a value.
    """
    rss_bytes: int | None = None
    try:
        statm = Path("/proc/self/statm").read_text(encoding="utf-8").split()
        if len(statm) >= 2:
            rss_bytes = int(statm[1]) * int(os.sysconf("SC_PAGE_SIZE"))
    except (OSError, ValueError, IndexError, AttributeError):
        rss_bytes = None
    return {
        "cpuProcessSeconds": round(time.process_time(), 6),
        "rssBytes": rss_bytes,
    }


@app.get("/health")
@app.get("/v1/health")
async def health(authorization: str | None = Header(default=None)):
    await require_app_auth(authorization)
    return {
        "ok": True,
        "service": APP_NAME,
        "version": APP_VERSION,
        "engineVersion": ENGINE_VERSION,
        "revision": DEPLOYMENT_REVISION or None,
        "provider": "TradeWize",
        "upstreamConfigured": bool(TRADEWIZE_API_KEY or UPSTREAM_ACCESS_TOKEN),
        "quota": _quota_metrics_snapshot(),
        "processMetrics": _process_resource_metrics(),
        "timestamp": int(time.time() * 1000),
    }


@app.get("/v1/bist/symbols")
async def bist_symbols(authorization: str | None = Header(default=None)):
    await require_app_auth(authorization)
    items = await discover_bist_quotes()
    symbols = [item["symbol"] for item in items if item.get("symbol")]
    usable = [item for item in items if float(item.get("price", 0) or 0) > 0]
    return {
        "items": symbols,
        "count": len(symbols),
        "symbolCount": len(symbols),
        "usableCount": len(usable),
        "detailedItems": [
            {
                "symbol": item["symbol"], "name": item["name"], "market": "BIST", "assetType": "STOCK",
                "price": item["price"], "dataTimestamp": item["timestamp"], "realtime": item["realtime"], "delaySeconds": item["delaySeconds"],
            }
            for item in items
        ],
        "source": "TradeWize",
        "receivedAt": int(time.time() * 1000),
    }


@app.get("/v1/bist/quote/{symbol}")
async def bist_quote(symbol: str, authorization: str | None = Header(default=None)):
    await require_app_auth(authorization)
    quote = await resolve_bist_quote(symbol)
    if not quote["realtime"]:
        raise HTTPException(status_code=503, detail=f"BIST_QUOTE_ERROR: Upstream quote is not live/current-session (age={quote.get('delaySeconds')}s); recent tick bulunamadı.")
    return {
        "symbol": quote["symbol"], "price": quote["price"], "exchangeTimestamp": quote["timestamp"], "receivedAt": int(time.time() * 1000),
        "source": quote["source"], "realtime": True, "delaySeconds": quote["delaySeconds"], "currentSessionIncluded": True, "providerReady": True,
    }


@app.get("/v1/bist/history/{symbol}")
async def bist_history(
    symbol: str,
    range: str = Query("1y", pattern=r"^(?:max|[0-9]+[dmy])$"),
    interval: str = Query("1d"),
    authorization: str | None = Header(default=None),
):
    await require_app_auth(authorization)
    canonical = normalize_interval(interval)
    candles = await _fetch_bist_history(symbol, canonical, range)
    safe = symbol.strip().upper()
    return {
        "symbol": safe, "name": safe, "market": "BIST", "interval": canonical, "exchangeTimezone": "Europe/Istanbul", "sessionId": None,
        "currentSessionIncluded": False, "lastBarClosed": True, "lastBarTime": candles[-1]["timestamp"], "lastBarTimestamp": candles[-1]["timestamp"],
        "previousClose": candles[-2]["close"] if len(candles) >= 2 else None, "candles": candles,
    }


@app.get("/v1/bist/history-window/{symbol}")
async def bist_history_window(
    symbol: str,
    from_time: int = Query(..., alias="from"),
    to_time: int = Query(..., alias="to"),
    interval: str = Query("5m"),
    authorization: str | None = Header(default=None),
):
    await require_app_auth(authorization)
    if from_time <= 0 or to_time <= from_time:
        raise HTTPException(status_code=400, detail="HISTORY_ERROR: Geçersiz zaman aralığı.")
    canonical = normalize_interval(interval)
    candles = await _fetch_bist_history(symbol, canonical, from_ms=from_time, to_ms=to_time)
    filtered = [item for item in candles if from_time <= item["timestamp"] <= to_time]
    if not filtered:
        raise HTTPException(status_code=503, detail="INSUFFICIENT_HISTORY: İstenen zaman aralığında kapanmış mum yok.")
    return {"symbol": symbol.strip().upper(), "market": "BIST", "interval": canonical, "exchangeTimezone": "Europe/Istanbul", "lastBarClosed": True, "lastBarTime": filtered[-1]["timestamp"], "candles": filtered}


def _batch_quote_payload(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "symbol": str(item.get("symbol", "")).strip().upper(),
        "market": "BIST",
        "price": float(item.get("price", 0) or 0),
        "exchangeTimestamp": int(item.get("timestamp", 0) or 0),
        "receivedAt": int(time.time() * 1000),
        "source": str(item.get("source") or "TradeWize"),
        "realtime": bool(item.get("realtime", False)),
        "delaySeconds": item.get("delaySeconds"),
        "currentSessionIncluded": bool(item.get("currentSessionIncluded", False)),
        "currency": "TRY",
    }


def _batch_history_payload(symbol: str, candles: list[dict[str, Any]], interval: str) -> dict[str, Any]:
    return {
        "symbol": symbol,
        "name": symbol,
        "market": "BIST",
        "interval": interval,
        "exchangeTimezone": "Europe/Istanbul",
        "sessionId": None,
        "currentSessionIncluded": False,
        "lastBarClosed": True,
        "lastBarTime": candles[-1]["timestamp"],
        "lastBarTimestamp": candles[-1]["timestamp"],
        "previousClose": candles[-2]["close"] if len(candles) >= 2 else None,
        "candles": candles,
    }


def _batch_error(exc: Exception) -> dict[str, str]:
    if isinstance(exc, HTTPException):
        detail = str(exc.detail)
        upper = detail.upper()
        if exc.status_code in {401, 403}:
            code = "AUTH_ERROR"
        elif exc.status_code == 429:
            code = "RATE_LIMIT"
        elif "INSUFFICIENT_HISTORY" in upper:
            code = "INSUFFICIENT_HISTORY"
        elif "STALE" in upper:
            code = "STALE_DATA"
        elif "QUOTE" in upper:
            code = "QUOTE_ERROR"
        else:
            code = "HISTORY_ERROR" if exc.status_code >= 500 else "EMPTY_DATA"
        return {"code": code, "message": detail}
    return {"code": "SERVER_ERROR", "message": f"{type(exc).__name__}: {exc}"}


async def _build_bist_snapshot_batch(
    symbols: str,
    authorization: str | None,
    interval: str,
    range_value: str,
    from_time: int | None,
    to_time: int | None,
) -> dict[str, Any]:
    await require_app_auth(authorization)
    raw = [part.strip().upper() for part in symbols.split(",") if part.strip()]
    if not raw:
        raise HTTPException(status_code=400, detail="BATCH_ERROR: En az bir sembol gerekli.")
    if len(raw) > SNAPSHOT_BATCH_MAX_SYMBOLS:
        raise HTTPException(status_code=400, detail=f"BATCH_ERROR: En fazla {SNAPSHOT_BATCH_MAX_SYMBOLS} sembol istenebilir.")
    if len(set(raw)) != len(raw):
        raise HTTPException(status_code=400, detail="BATCH_ERROR: Tekrarlanan sembol kabul edilmez.")
    if any(not symbol.replace("_", "").isalnum() or len(symbol) > 12 for symbol in raw):
        raise HTTPException(status_code=400, detail="BATCH_ERROR: Geçersiz sembol biçimi.")
    canonical = normalize_interval(interval)
    if (from_time is None) != (to_time is None):
        raise HTTPException(status_code=400, detail="BATCH_ERROR: from/to birlikte verilmelidir.")
    if from_time is not None and (from_time <= 0 or to_time is None or to_time <= from_time):
        raise HTTPException(status_code=400, detail="BATCH_ERROR: Geçersiz history zaman aralığı.")

    discovered = await discover_bist_quotes()
    quote_by_symbol = {str(item.get("symbol", "")).strip().upper(): item for item in discovered if isinstance(item, dict)}

    async def build_row(symbol: str) -> dict[str, Any]:
        try:
            quote = await resolve_bist_quote(symbol, quote_by_symbol.get(symbol))
            if not quote.get("realtime"):
                raise HTTPException(
                    status_code=503,
                    detail=f"STALE_DATA: {symbol} quote güncel değil (age={quote.get('delaySeconds')}s); recent tick bulunamadı.",
                )
            candles = await _fetch_bist_history(
                symbol, canonical, range_value, from_ms=from_time, to_ms=to_time, scanner=True
            )
            return {
                "symbol": symbol,
                "quote": _batch_quote_payload(quote),
                "history": _batch_history_payload(symbol, candles, canonical),
            }
        except Exception as exc:
            return {"symbol": symbol, "error": _batch_error(exc)}

    items = await asyncio.gather(*(build_row(symbol) for symbol in raw))
    if len(items) != len(raw) or [item.get("symbol") for item in items] != raw:
        raise HTTPException(status_code=500, detail="BATCH_CONTRACT_ERROR: Yanıt sembol sırası/sayısı bozuldu.")
    return {
        "items": items,
        "requestedCount": len(raw),
        "returnedCount": len(items),
        "interval": canonical,
        "receivedAt": int(time.time() * 1000),
        "source": "TradeWize",
    }


def _snapshot_batch_request_key(
    request_id: str, symbols: str, interval: str, range_value: str, from_time: int | None, to_time: int | None
) -> str:
    canonical = json.dumps(
        {
            "requestId": request_id.strip(),
            "symbols": symbols,
            "interval": interval,
            "range": range_value,
            "from": from_time,
            "to": to_time,
        },
        separators=(",", ":"),
        sort_keys=True,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _prune_snapshot_batch_idempotency(now: float) -> None:
    cutoff = now - SNAPSHOT_BATCH_IDEMPOTENCY_TTL_SECONDS
    for key, (created_at, _) in list(_snapshot_batch_completed.items()):
        if created_at < cutoff:
            _snapshot_batch_completed.pop(key, None)
    if len(_snapshot_batch_completed) > SNAPSHOT_BATCH_IDEMPOTENCY_MAX_ENTRIES:
        ordered = sorted(_snapshot_batch_completed.items(), key=lambda item: item[1][0])
        for key, _ in ordered[: len(_snapshot_batch_completed) - SNAPSHOT_BATCH_IDEMPOTENCY_MAX_ENTRIES]:
            _snapshot_batch_completed.pop(key, None)
    for key, task in list(_snapshot_batch_tasks.items()):
        if task.done():
            _snapshot_batch_tasks.pop(key, None)


_SNAPSHOT_LOCK_RELEASE_LUA = r"""
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
"""


async def _idempotent_snapshot_batch_distributed(request_key: str, factory) -> dict[str, Any]:
    try:
        client = await _get_redis_quota_client()
    except Exception as exc:
        raise HTTPException(status_code=503, detail="SNAPSHOT_SHARED_STATE_UNAVAILABLE") from exc
    result_key = f"{SNAPSHOT_BATCH_REDIS_PREFIX}:result:{request_key}"
    lock_key = f"{SNAPSHOT_BATCH_REDIS_PREFIX}:lock:{request_key}"
    owner = secrets.token_urlsafe(18)
    lock_ttl_ms = max(SNAPSHOT_BATCH_OUTER_TIMEOUT_MS + 5_000, SNAPSHOT_BATCH_IDEMPOTENCY_TTL_SECONDS * 1000)
    deadline = time.monotonic() + (SNAPSHOT_BATCH_OUTER_TIMEOUT_MS / 1000.0)
    joined_recorded = False
    while time.monotonic() < deadline:
        try:
            cached_raw = await client.get(result_key)
            if cached_raw:
                payload = json.loads(cached_raw)
                if isinstance(payload, dict):
                    _snapshot_batch_metrics["completedHit"] = int(_snapshot_batch_metrics.get("completedHit", 0)) + 1
                    return json.loads(json.dumps(payload))
            acquired = await client.set(lock_key, owner, nx=True, px=lock_ttl_ms)
        except Exception as exc:
            raise HTTPException(status_code=503, detail="SNAPSHOT_SHARED_STATE_UNAVAILABLE") from exc
        if acquired:
            _snapshot_batch_metrics["started"] = int(_snapshot_batch_metrics.get("started", 0)) + 1
            try:
                result = await factory()
                encoded = json.dumps(result, separators=(",", ":"), sort_keys=True)
                await client.set(result_key, encoded, ex=SNAPSHOT_BATCH_IDEMPOTENCY_TTL_SECONDS)
                return json.loads(encoded)
            finally:
                try:
                    await client.eval(_SNAPSHOT_LOCK_RELEASE_LUA, 1, lock_key, owner)
                except Exception:
                    pass
        if not joined_recorded:
            _snapshot_batch_metrics["joined"] = int(_snapshot_batch_metrics.get("joined", 0)) + 1
            joined_recorded = True
        await asyncio.sleep(0.05)
    raise HTTPException(status_code=504, detail="SNAPSHOT_IDEMPOTENCY_WAIT_TIMEOUT")


async def _idempotent_snapshot_batch(
    request_key: str,
    factory,
) -> dict[str, Any]:
    if SNAPSHOT_BATCH_DISTRIBUTED_IDEMPOTENCY:
        return await _idempotent_snapshot_batch_distributed(request_key, factory)
    now = time.monotonic()
    async with _snapshot_batch_lock:
        _prune_snapshot_batch_idempotency(now)
        cached = _snapshot_batch_completed.get(request_key)
        if cached is not None:
            _snapshot_batch_metrics["completedHit"] = int(_snapshot_batch_metrics.get("completedHit", 0)) + 1
            return json.loads(json.dumps(cached[1]))
        task = _snapshot_batch_tasks.get(request_key)
        if task is None:
            task = asyncio.create_task(factory())
            _snapshot_batch_tasks[request_key] = task
            _snapshot_batch_metrics["started"] = int(_snapshot_batch_metrics.get("started", 0)) + 1
        else:
            _snapshot_batch_metrics["joined"] = int(_snapshot_batch_metrics.get("joined", 0)) + 1
    try:
        result = await asyncio.shield(task)
    finally:
        if task.done():
            async with _snapshot_batch_lock:
                _snapshot_batch_tasks.pop(request_key, None)
                if not task.cancelled() and task.exception() is None:
                    _snapshot_batch_completed[request_key] = (time.monotonic(), task.result())
                    _prune_snapshot_batch_idempotency(time.monotonic())
    return json.loads(json.dumps(result))


@app.get("/v1/bist/snapshot-batch")
async def bist_snapshot_batch(
    symbols: str = Query(..., min_length=1),
    authorization: str | None = Header(default=None),
    interval: str = Query("1d"),
    range: str = Query("1y", pattern=r"^(?:max|[0-9]+[dmy])$"),
    from_time: int | None = Query(default=None, alias="from"),
    to_time: int | None = Query(default=None, alias="to"),
    x_request_id: str | None = Header(default=None, alias="X-Request-ID"),
):
    """Canonical Android batch snapshot with retry-safe request-id single-flight."""
    request_id = x_request_id.strip() if isinstance(x_request_id, str) else ""
    if not request_id:
        return await _build_bist_snapshot_batch(symbols, authorization, interval, range, from_time, to_time)
    if len(request_id) > 128:
        raise HTTPException(status_code=400, detail="BATCH_ERROR: X-Request-ID çok uzun.")
    request_key = _snapshot_batch_request_key(request_id, symbols, interval, range, from_time, to_time)
    return await _idempotent_snapshot_batch(
        request_key,
        lambda: _build_bist_snapshot_batch(symbols, authorization, interval, range, from_time, to_time),
    )


@app.get("/v1/preflight")
async def bist_preflight(authorization: str | None = Header(default=None)):
    await require_app_auth(authorization)
    started = int(time.time() * 1000)
    auth_configured = bool(TRADEWIZE_API_KEY or UPSTREAM_ACCESS_TOKEN)
    authentication = {"ok": auth_configured, "message": "TradeWize kimlik doğrulama yapılandırıldı." if auth_configured else "TradeWize erişim anahtarı yapılandırılmamış."}
    symbols = {"ok": False, "message": "BIST sembol evreni doğrulanmadı."}
    history = {"ok": False, "message": "BIST history doğrulanmadı."}
    quote = {"ok": False, "code": "UNAVAILABLE", "message": "BIST quote doğrulanmadı."}
    symbol_count = 0
    sample_symbol: str | None = None
    discovered: list[dict[str, Any]] = []

    try:
        discovered = await discover_bist_quotes(force=True)
        usable = [item for item in discovered if item.get("symbol") and float(item.get("price", 0) or 0) > 0]
        symbol_count = len(discovered)
        sample_symbol = usable[0]["symbol"] if usable else (discovered[0]["symbol"] if discovered else None)
        symbols = {"ok": symbol_count > 0, "message": f"{symbol_count} BIST sembolü bulundu." if symbol_count > 0 else "BIST sembol evreni boş."}
    except Exception as exc:
        symbols["message"] = str(exc.detail) if isinstance(exc, HTTPException) else str(exc)

    if sample_symbol:
        try:
            candles = await _fetch_bist_history(sample_symbol, "1d", "1y")
            history = {"ok": len(candles) >= 50, "message": f"{len(candles)} kapanmış günlük mum doğrulandı.", "lastBarTimestamp": candles[-1]["timestamp"]}
        except Exception as exc:
            history["message"] = str(exc.detail) if isinstance(exc, HTTPException) else str(exc)
        try:
            resolved = await resolve_bist_quote(sample_symbol)
            if resolved["realtime"]:
                quote = {"ok": True, "code": "NONE", "message": f"BIST quote {resolved['source']} üzerinden güncel.", "exchangeTimestamp": resolved["timestamp"], "delaySeconds": resolved["delaySeconds"]}
            else:
                quote = {"ok": False, "code": "STALE_DATA", "message": f"Upstream quote güncel değil (age={resolved.get('delaySeconds')}s); güncel recent tick bulunamadı.", "exchangeTimestamp": resolved.get("timestamp"), "delaySeconds": resolved.get("delaySeconds")}
        except Exception as exc:
            quote = {"ok": False, "code": "QUOTE_ERROR", "message": str(exc.detail) if isinstance(exc, HTTPException) else str(exc)}

    analysis_mode = "REALTIME" if quote["ok"] and history["ok"] else "DELAYED_ANALYSIS_AVAILABLE" if history["ok"] and quote.get("code") == "STALE_DATA" else "UNAVAILABLE"
    ok = bool(authentication["ok"] and symbols["ok"] and history["ok"] and (quote["ok"] or analysis_mode == "DELAYED_ANALYSIS_AVAILABLE"))
    return {"ok": ok, "provider": "TradeWize", "authentication": authentication, "symbols": symbols, "quote": quote, "history": history, "symbolCount": symbol_count, "sampleSymbol": sample_symbol, "analysisMode": analysis_mode, "serverTime": started, "elapsedMs": int(time.time() * 1000) - started}


async def _market_live_probe(market: str, items: list[dict[str, Any]]) -> tuple[bool, str | None]:
    if any(item.get("realtime") for item in items):
        return True, None
    if market == "BIST":
        sample = next((item["symbol"] for item in items if item.get("symbol") and float(item.get("price", 0) or 0) > 0), None)
        if sample:
            try:
                resolved = await resolve_bist_quote(sample)
                return (True, None) if resolved["realtime"] else (False, f"STALE_DATA age={resolved.get('delaySeconds')}s")
            except HTTPException as exc:
                return False, str(exc.detail)
    return False, "NO_LIVE_PRICE"


async def provider_capabilities(force: bool = False) -> dict[str, Any]:
    now = time.time()
    if not force and _capability_cache["value"] is not None and now - float(_capability_cache["at"]) < CAPABILITY_CACHE_SECONDS:
        return dict(_capability_cache["value"])

    configured = bool(TRADEWIZE_API_KEY or UPSTREAM_ACCESS_TOKEN)
    result: dict[str, Any] = {
        "ok": True, "version": APP_VERSION, "provider": "TradeWize", "primaryConfiguredProvider": "TradeWize", "providerConfigured": configured,
        "providerReady": False, "globalProviderReady": False, "multiMarketReady": False, "bistReady": False, "checkedAt": int(time.time() * 1000),
        "tradeWize": {"state": "CONFIGURED" if configured else "NOT_CONFIGURED", "adapterVerified": True},
        "features": {**FEATURE_CAPABILITIES, "tradingViewSignals": bool(TRADINGVIEW_WEBHOOK_SECRET and TRADINGVIEW_DB_PATH)},
        "scanPolicy": {
            "snapshotBatchMaxSymbols": SNAPSHOT_BATCH_MAX_SYMBOLS,
            "snapshotBatchReadTimeoutMs": SNAPSHOT_BATCH_READ_TIMEOUT_MS,
            "snapshotBatchCallTimeoutMs": SNAPSHOT_BATCH_CALL_TIMEOUT_MS,
            "snapshotBatchOuterTimeoutMs": SNAPSHOT_BATCH_OUTER_TIMEOUT_MS,
            "snapshotBatchEstimatedWorstCaseMs": SNAPSHOT_BATCH_ESTIMATED_WORST_CASE_MS,
            "barConcurrency": SCANNER_CONCURRENCY,
            "pacingMs": SCANNER_PACING_MS,
            "quotaScope": UPSTREAM_QUOTA_SCOPE,
            "requestWeight": SCANNER_REQUEST_WEIGHT,
            "providerWeightFormulaVerified": False,
            "distributedProviderQuotaConfigured": bool(UPSTREAM_DISTRIBUTED_QUOTA and REDIS_URL),
        },
        "quotaMetrics": _quota_metrics_snapshot(),
        "markets": {}, "errors": [],
    }

    try:
        bist = await discover_bist_quotes(force=force)
        usable = [item for item in bist if float(item.get("price", 0) or 0) > 0]
        live_ready, live_reason = await _market_live_probe("BIST", usable)
        result["markets"]["BIST"] = {
            "supported": True, "discovery": True, "marketData": True, "historicalData": True, "symbolCount": len(bist), "usableCount": len(usable),
            "liveCount": sum(1 for item in usable if item.get("realtime")), "realtime": live_ready, "realtimeReady": live_ready, "analysisReady": bool(usable),
            "ready": live_ready, "provider": "TradeWize", "reasonCode": None if live_ready else "STALE_DATA",
            "message": "BIST canlı fiyat doğrulandı." if live_ready else f"BIST discovery hazır; canlı quote doğrulanmadı ({live_reason}).",
        }
        result["bistReady"] = live_ready
    except Exception as exc:
        reason = str(exc.detail) if isinstance(exc, HTTPException) else f"{type(exc).__name__}: {exc}"
        result["markets"]["BIST"] = {"supported": True, "discovery": True, "marketData": True, "historicalData": True, "realtimeReady": False, "analysisReady": False, "ready": False, "reasonCode": "BIST_PROVIDER_ERROR", "message": reason, "provider": "TradeWize"}
        result["errors"].append({"market": "BIST", "reason": reason})

    try:
        viop = await discover_viop_quotes(force=force)
        live_ready, live_reason = await _market_live_probe("VIOP", viop)
        metadata_ready = False
        metadata_reason: str | None = None
        metadata: list[dict[str, Any]] = []
        try:
            metadata, _ = await discover_viop_contract_metadata(force=force)
            metadata_ready = bool(metadata)
        except Exception as meta_exc:
            metadata_reason = str(meta_exc.detail) if isinstance(meta_exc, HTTPException) else str(meta_exc)
        quote_map = {str(item.get("symbol") or "").strip().upper(): item for item in viop}
        liquidity_ready = any(
            (_positive_float((quote_map.get(str(meta.get("symbol") or "").strip().upper()) or {}).get("volume")) or _positive_float(meta.get("volume"))) is not None
            and (_positive_int((quote_map.get(str(meta.get("symbol") or "").strip().upper()) or {}).get("openInterest")) or _positive_int(meta.get("openInterest"))) is not None
            for meta in metadata
        )
        contracts_ready = metadata_ready and liquidity_ready
        result["features"]["viopContractsReady"] = contracts_ready
        market_ready = live_ready and contracts_ready
        result["markets"]["VIOP"] = {
            "supported": True, "discovery": metadata_ready, "marketData": True, "historicalData": True, "symbolCount": len(viop), "usableCount": len(viop),
            "liveCount": sum(1 for item in viop if item.get("realtime")), "realtime": live_ready, "realtimeReady": live_ready, "analysisReady": bool(viop) and liquidity_ready,
            "contractsReady": contracts_ready, "metadataVerified": metadata_ready, "liquidityReady": liquidity_ready, "ready": market_ready, "provider": "TradeWize",
            "reasonCode": None if market_ready else (
                "VIOP_CONTRACT_METADATA_UNAVAILABLE" if not metadata_ready
                else "VIOP_LIQUIDITY_DATA_UNAVAILABLE" if not liquidity_ready
                else "STALE_DATA"
            ),
            "message": "VİOP canlı fiyat, kontrat metadata ve likidite alanları doğrulandı." if market_ready else (
                metadata_reason or (
                    "VİOP volume/openInterest alanları doğrulanmadı; tarama fail-closed." if metadata_ready and not liquidity_ready
                    else f"VİOP discovery hazır; canlı quote doğrulanmadı ({live_reason})."
                )
            ),
        }
    except Exception as exc:
        reason = str(exc.detail) if isinstance(exc, HTTPException) else f"{type(exc).__name__}: {exc}"
        result["features"]["viopContractsReady"] = False
        result["markets"]["VIOP"] = {"supported": True, "discovery": False, "marketData": True, "historicalData": True, "realtimeReady": False, "analysisReady": False, "contractsReady": False, "ready": False, "reasonCode": "VIOP_PROVIDER_ERROR", "message": reason, "provider": "TradeWize"}
        result["errors"].append({"market": "VIOP", "reason": reason})

    for market in ("COMMODITY", "GOLD", "SILVER", "FX", "INDEX"):
        result["markets"][market] = {"supported": False, "discovery": False, "marketData": False, "historicalData": False, "realtime": False, "realtimeReady": False, "analysisReady": False, "symbolCount": 0, "ready": False, "provider": None, "reasonCode": "UPSTREAM_ENDPOINT_NOT_DOCUMENTED", "message": "Doğrulanmış upstream endpoint yok; sahte veri üretilmez."}

    core_markets = ("BIST", "VIOP")
    all_ui_markets = ("BIST", "VIOP", "GOLD", "SILVER", "COMMODITY", "FX", "INDEX")
    result["coreMarketsReady"] = all(bool(result["markets"].get(name, {}).get("ready")) for name in core_markets)
    result["multiMarketReady"] = all(bool(result["markets"].get(name, {}).get("ready")) for name in all_ui_markets)
    result["globalProviderReady"] = result["multiMarketReady"]
    result["providerReady"] = result["multiMarketReady"]
    result["requiredMarketsForAll"] = list(all_ui_markets)
    _capability_cache.update({"at": now, "value": result})
    return result


@app.get("/v1/provider/capabilities")
async def provider_capabilities_endpoint(force: bool = Query(False), authorization: str | None = Header(default=None)):
    await require_app_auth(authorization)
    return await provider_capabilities(force=force)


@app.get("/v1/viop/contracts")
async def viop_contracts(
    limit: int = Query(200, ge=1, le=200),
    cursor: str | None = Query(default=None),
    authorization: str | None = Header(default=None),
):
    await require_app_auth(authorization)
    metadata, universe_as_of = await discover_viop_contract_metadata()
    try:
        offset = int(cursor or "0")
    except ValueError:
        raise HTTPException(status_code=400, detail="VIOP_CONTRACT_CURSOR_INVALID")
    if offset < 0 or offset > len(metadata):
        raise HTTPException(status_code=400, detail="VIOP_CONTRACT_CURSOR_INVALID")
    quotes = await discover_viop_quotes()
    quote_map = {item["symbol"]: item for item in quotes}
    liquidity_evidence = any(
        (_positive_float((quote_map.get(meta["symbol"]) or {}).get("volume")) or _positive_float(meta.get("volume"))) is not None
        and (_positive_int((quote_map.get(meta["symbol"]) or {}).get("openInterest")) or _positive_int(meta.get("openInterest"))) is not None
        for meta in metadata
    )
    if not liquidity_evidence:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "VIOP_LIQUIDITY_DATA_UNAVAILABLE",
                "message": "Doğrulanmış volume/openInterest verisi yok; VİOP taraması fail-closed.",
            },
        )
    page_meta = metadata[offset: offset + limit]
    items: list[dict[str, Any]] = []
    for meta in page_meta:
        quote = quote_map.get(meta["symbol"]) or {}
        items.append({
            **meta,
            "lastPrice": quote.get("price"), "bid": None, "ask": None, "dailyChangePct": None,
            "openInterest": _positive_int(quote.get("openInterest")) or _positive_int(meta.get("openInterest")),
            "volume": _positive_float(quote.get("volume")) or _positive_float(meta.get("volume")),
            "liquidity": None, "rollover": None,
            "source": quote.get("source") or "TradeWize/contract-metadata",
            "dataTimestamp": int(quote.get("timestamp", 0) or 0),
            "realtime": bool(quote.get("realtime", False)), "delaySeconds": quote.get("delaySeconds"),
            "currentSessionIncluded": bool(quote.get("currentSessionIncluded", False)),
        })
    next_offset = offset + len(page_meta)
    has_more = next_offset < len(metadata)
    return {
        "items": items, "totalCount": len(metadata), "hasMore": has_more,
        "nextCursor": str(next_offset) if has_more else None, "universeAsOf": universe_as_of,
        "receivedAt": int(time.time() * 1000),
    }


@app.get("/v1/viop/quote/{symbol}")
async def viop_quote(symbol: str, authorization: str | None = Header(default=None)):
    await require_app_auth(authorization)
    safe = symbol.strip().upper()
    if not safe or len(safe) > 64:
        raise HTTPException(status_code=400, detail="QUOTE_ERROR: Geçersiz sembol.")
    await _require_viop_contract(safe)
    response = await upstream_get("/api/v1/market-data/viop/last-price/details", {"symbols": safe})
    payload = _unwrap_symbol_map(response.json())
    if safe not in payload:
        raise HTTPException(status_code=404, detail="QUOTE_ERROR: Sembol bulunamadı.")
    normalized = extract_price_record(safe, payload[safe])
    if normalized["price"] <= 0 or normalized["timestamp"] <= 0:
        raise HTTPException(status_code=503, detail="STALE_DATA: Kullanılabilir fiyat zamanı yok.")
    return {"symbol": safe, "price": normalized["price"], "bid": None, "ask": None, "dailyChangePct": None, "volume": normalized.get("volume"), "openInterest": normalized.get("openInterest"), "exchangeTimestamp": normalized["timestamp"], "receivedAt": int(time.time() * 1000), "source": normalized["source"], "realtime": normalized["realtime"], "delaySeconds": normalized["delaySeconds"], "currentSessionIncluded": normalized["currentSessionIncluded"]}


@app.get("/v1/viop/history/{symbol}")
async def viop_history(
    symbol: str,
    range: str = Query("1y", pattern=r"^(?:max|[0-9]+[dmy])$"),
    interval: str = Query("1d"),
    authorization: str | None = Header(default=None),
):
    await require_app_auth(authorization)
    canonical = normalize_interval(interval)
    upstream_interval, resample_10m = upstream_interval_for(canonical)
    safe = symbol.strip().upper()
    if not safe:
        raise HTTPException(status_code=400, detail="HISTORY_ERROR: Geçersiz sembol.")
    await _require_viop_contract(safe)
    bars = await _cached_market_bars("VIOP", safe, canonical, upstream_interval, range_value=range)
    if resample_10m:
        bars = _resample_5m_to_10m(bars)
    if not bars:
        raise HTTPException(status_code=503, detail="INSUFFICIENT_HISTORY: Backend kapanmış mum döndürmedi.")
    return {"symbol": safe, "interval": canonical, "lastBarClosed": True, "lastBarTimestamp": bars[-1]["timestamp"], "candles": bars}


def scanner_symbols(
    raw: str | None,
    market: str = "BIST",
    asset_type: str = "ALL",
    offset: int = 0,
    requested_limit: int | None = None,
) -> tuple[list[str], int, int, int]:
    selected_market = market.strip().upper()
    selected_asset = asset_type.strip().upper()
    if selected_market == "BIST" and selected_asset == "FUTURE":
        raise HTTPException(status_code=400, detail="SCANNER_ERROR: BIST market FUTURE assetType ile kullanılamaz.")
    if selected_market == "VIOP" and selected_asset == "STOCK":
        raise HTTPException(status_code=400, detail="SCANNER_ERROR: VIOP market STOCK assetType ile kullanılamaz.")

    bist_candidates = [item["symbol"] for item in _bist_cache.get("items", []) if isinstance(item, dict) and item.get("symbol")]
    # VİOP scanner universe is metadata-backed, never quote-discovery-backed.
    viop_candidates = [item["symbol"] for item in _viop_contract_cache.get("items", []) if isinstance(item, dict) and item.get("symbol")]
    if selected_market == "BIST":
        allowed_candidates = bist_candidates
    elif selected_market == "VIOP":
        allowed_candidates = viop_candidates
    elif selected_market == "ALL" and selected_asset == "STOCK":
        allowed_candidates = bist_candidates
    elif selected_market == "ALL" and selected_asset == "FUTURE":
        allowed_candidates = viop_candidates
    elif selected_market == "ALL":
        allowed_candidates = bist_candidates + viop_candidates
    else:
        allowed_candidates = []

    allowed: list[str] = []
    allowed_seen: set[str] = set()
    for item in allowed_candidates:
        safe = str(item).strip().upper()
        if safe and len(safe) <= 64 and safe not in allowed_seen:
            allowed_seen.add(safe)
            allowed.append(safe)

    requested = (raw or "").strip()
    if requested:
        raw_symbols: list[str] = []
        seen_requested: set[str] = set()
        for item in requested.split(","):
            safe = item.strip().upper()
            if safe and len(safe) <= 64 and safe not in seen_requested:
                seen_requested.add(safe)
                raw_symbols.append(safe)
        incompatible = [symbol for symbol in raw_symbols if symbol not in allowed_seen]
        if incompatible:
            sample = ",".join(incompatible[:5])
            raise HTTPException(status_code=400, detail=f"SCANNER_ERROR: İstenen semboller market/assetType evreniyle uyumsuz: {sample}")
        universe = raw_symbols
    else:
        universe = allowed

    total = min(len(universe), SCANNER_MAX_SYMBOLS)
    universe = universe[:total]
    start = min(max(0, offset), total)
    page_size = max(1, min(requested_limit or SCANNER_BATCH_SIZE, SCANNER_BATCH_SIZE, SCANNER_MAX_SYMBOLS))
    selected = universe[start:start + page_size]
    next_offset = start + len(selected)
    remaining = max(0, total - next_offset)
    return selected, remaining, total, next_offset


def _ema(values: list[float], period: int) -> float:
    if not values:
        return 0.0
    alpha = 2.0 / (period + 1.0)
    result = values[0]
    for value in values[1:]:
        result = alpha * value + (1.0 - alpha) * result
    return result


def _rsi(values: list[float], period: int) -> float:
    if len(values) <= period:
        return 50.0
    gains, losses = [], []
    for prev, curr in zip(values[-period - 1:-1], values[-period:]):
        diff = curr - prev
        gains.append(max(0.0, diff))
        losses.append(max(0.0, -diff))
    avg_gain, avg_loss = sum(gains) / period, sum(losses) / period
    if avg_loss == 0:
        return 100.0 if avg_gain > 0 else 50.0
    rs = avg_gain / avg_loss
    return 100.0 - (100.0 / (1.0 + rs))


def _atr_percent(bars: list[dict[str, Any]], period: int) -> float:
    if len(bars) <= period:
        return 0.0
    trs: list[float] = []
    for prev, curr in zip(bars[-period - 1:-1], bars[-period:]):
        trs.append(max(float(curr["high"]) - float(curr["low"]), abs(float(curr["high"]) - float(prev["close"])), abs(float(curr["low"]) - float(prev["close"]))))
    close = float(bars[-1]["close"])
    return (sum(trs) / len(trs)) / close * 100.0 if close > 0 else 0.0


def _scan_from_bars(symbol: str, bars: list[dict[str, Any]], interval: str, metadata: dict[str, Any] | None = None) -> dict[str, Any] | None:
    if len(bars) < 30:
        return None
    closes = [float(item["close"]) for item in bars]
    volumes = [float(item.get("volume", 0.0)) for item in bars]
    price = closes[-1]
    change = ((price / closes[-2]) - 1.0) * 100.0 if closes[-2] else 0.0
    ema20, ema50, rsi14, atr_pct = _ema(closes, 20), _ema(closes, 50), _rsi(closes, 14), _atr_percent(bars, 14)
    recent_volumes = volumes[-21:-1]
    avg_volume = sum(recent_volumes) / len(recent_volumes) if recent_volumes else 0.0
    volume_available = avg_volume > 0 and volumes[-1] >= 0
    volume_ratio = volumes[-1] / avg_volume if avg_volume > 0 else None
    raw_score = max(-100.0, min(100.0, (35.0 if ema20 > ema50 else -35.0 if ema20 < ema50 else 0.0) + max(-30.0, min(30.0, change * 6.0)) + max(-20.0, min(20.0, (rsi14 - 50.0) * 0.8)) + (15.0 if price > ema20 else -15.0)))
    latest_ts = int(bars[-1]["timestamp"])
    technical_direction = "LONG" if raw_score >= 45.0 else "SHORT" if raw_score <= -45.0 else "WATCH"
    metadata = metadata or {}
    return {
        "symbol": symbol, "name": metadata.get("name", symbol), "market": metadata.get("market", "BIST"), "assetType": metadata.get("assetType", "STOCK"), "underlying": metadata.get("underlying", symbol),
        "decision": "WATCH", "signal": "WATCH", "technicalSignal": technical_direction, "verificationStatus": "OBSERVATION" if technical_direction != "WATCH" else "WATCH", "publicationMode": "OBSERVATION_ONLY",
        "score": round(raw_score, 2), "signalScore": round(raw_score, 2), "dataConfidence": 75 if len(bars) >= 60 else 65, "dataConfidenceBand": "DEGRADED", "riskCoveragePercent": 75 if volume_available else 65,
        "penalty": 0.0, "currentPrice": price, "price": price, "dailyChangePct": round(change, 4), "volume": volumes[-1], "openInterest": metadata.get("openInterest"),
        "ema20": round(ema20, 6), "ema50": round(ema50, 6), "rsi14": round(rsi14, 4), "atrPct": round(atr_pct, 4), "volumeRatio": round(volume_ratio, 4) if volume_ratio is not None else None,
        "dataTimestamp": latest_ts, "exchangeTimestamp": latest_ts, "delaySeconds": freshness(latest_ts)[1], "realtime": False, "currentSessionIncluded": False, "lastBarClosed": True,
        "timeframe": interval, "analysisTimeframe": interval, "source": "TradeWize/bars", "engineVersion": ENGINE_VERSION, "mode": "REMOTE", "calculationVersion": ENGINE_VERSION,
    }


async def _scan_symbol(symbol: str, interval: str, market: str) -> tuple[dict[str, Any] | None, str | None]:
    try:
        contract_metadata: dict[str, Any] | None = None
        if market != "BIST":
            contract_metadata = await _require_viop_contract(symbol)
            upstream_interval, resample_10m = upstream_interval_for(interval)
            params = {"symbol": symbol, "interval": upstream_interval, "countBack": SCANNER_HISTORY_BARS * (2 if resample_10m else 1), "includeOpenBar": "false"}
            response = await bar_upstream_get("/api/v1/market-data/bars", params)
            bars = _normalize_bars(response.json())
            if resample_10m:
                bars = _resample_5m_to_10m(bars)
        else:
            bars = await _fetch_bist_history(symbol, interval, range_value="1y", scanner=True)
            bars = bars[-SCANNER_HISTORY_BARS:]
        metadata = {"market": market, "assetType": "STOCK" if market == "BIST" else "FUTURE"}
        if contract_metadata is not None:
            metadata.update({"underlying": contract_metadata.get("underlying"), "name": contract_metadata.get("symbol", symbol)})
        item = _scan_from_bars(symbol, bars, interval, metadata)
        return (item, None) if item is not None else (None, "INSUFFICIENT_HISTORY")
    except HTTPException as exc:
        return None, str(exc.detail)
    except Exception as exc:
        return None, f"SCANNER_SYMBOL_ERROR: {type(exc).__name__}"


@app.get("/v1/scanner/opportunities")
async def scanner_opportunities(
    symbols: str | None = Query(default=None),
    market: str = Query("BIST"),
    assetType: str = Query("STOCK"),
    timeframe: str = Query(DEFAULT_SCAN_INTERVAL),
    interval: str | None = Query(default=None),
    limit: int = Query(60, ge=1, le=1000),
    minScore: float = Query(0.0, ge=0.0, le=100.0),
    includeWatch: bool = Query(True),
    offset: int = Query(0, ge=0),
    authorization: str | None = Header(default=None),
):
    await require_app_auth(authorization)
    selected_interval = normalize_interval(interval or timeframe)
    selected_market = market.strip().upper()
    selected_asset_type = assetType.strip().upper()
    if selected_market not in {"BIST", "VIOP", "ALL"}:
        raise HTTPException(status_code=400, detail="SCANNER_ERROR: Geçersiz market.")
    if selected_asset_type not in {"STOCK", "FUTURE", "ALL"}:
        raise HTTPException(status_code=400, detail="SCANNER_ERROR: Geçersiz assetType.")
    if selected_market == "BIST" and selected_asset_type == "FUTURE":
        raise HTTPException(status_code=400, detail="SCANNER_ERROR: BIST yalnız STOCK/ALL assetType ile taranabilir.")
    if selected_market == "VIOP" and selected_asset_type == "STOCK":
        raise HTTPException(status_code=400, detail="SCANNER_ERROR: VIOP yalnız FUTURE/ALL assetType ile taranabilir.")

    needs_bist = selected_market == "BIST" or (selected_market == "ALL" and selected_asset_type in {"ALL", "STOCK"})
    needs_viop = selected_market == "VIOP" or (selected_market == "ALL" and selected_asset_type in {"ALL", "FUTURE"})
    if needs_bist:
        await discover_bist_quotes()
    if needs_viop:
        metadata, _ = await discover_viop_contract_metadata()
        if not metadata:
            raise HTTPException(status_code=503, detail={"code": "VIOP_CONTRACT_METADATA_EMPTY", "message": "Doğrulanmış aktif VİOP kontrat metadata evreni boş."})
        await discover_viop_quotes()

    requested, remaining, universe_count, next_offset = scanner_symbols(
        symbols, selected_market, selected_asset_type, offset, limit
    )
    opportunities: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    bist_symbols = {item.get("symbol") for item in _bist_cache.get("items", []) if isinstance(item, dict)}

    async def run_one(symbol: str):
        symbol_market = "BIST" if selected_market in {"BIST", "ALL"} and symbol in bist_symbols else "VIOP"
        return symbol, symbol_market, await _scan_symbol(symbol, selected_interval, symbol_market)

    if requested:
        results = await asyncio.gather(*(run_one(symbol) for symbol in requested))
        for symbol, item_market, (item, error) in results:
            if error:
                failures.append({"symbol": symbol, "market": item_market, "code": "SCANNER_SYMBOL_ERROR", "message": error})
            elif item is not None:
                technical = str(item.get("technicalSignal", "WATCH"))
                if (includeWatch or technical in {"LONG", "SHORT"}) and abs(float(item["score"])) >= minScore:
                    opportunities.append(item)

    opportunities.sort(key=lambda item: abs(float(item["score"])), reverse=True)
    capability = await provider_capabilities()
    if selected_market == "ALL" and selected_asset_type == "STOCK":
        market_ready = bool(capability.get("markets", {}).get("BIST", {}).get("ready", False))
    elif selected_market == "ALL" and selected_asset_type == "FUTURE":
        market_ready = bool(capability.get("markets", {}).get("VIOP", {}).get("ready", False))
    elif selected_market == "ALL":
        market_ready = bool(capability.get("multiMarketReady", False))
    else:
        market_ready = bool(capability.get("markets", {}).get(selected_market, {}).get("ready", False))
    analysis_ready = bool(requested) and len(requested) > len(failures)
    analysis_mode = "REALTIME" if market_ready else "DELAYED_ANALYSIS" if analysis_ready else "UNAVAILABLE"
    coverage_complete = remaining == 0
    return {
        "success": True, "items": opportunities, "opportunities": opportunities, "engineVersion": ENGINE_VERSION, "mode": "REMOTE", "source": "TradeWize", "receivedAt": int(time.time() * 1000),
        "scannedSymbols": len(requested), "successfulCount": max(0, len(requested) - len(failures)), "universeCount": universe_count, "returnedCount": len(opportunities),
        "remainingSymbols": remaining, "nextOffset": next_offset, "coverageComplete": coverage_complete, "partial": not coverage_complete, "timeframe": selected_interval, "requestedTimeframe": selected_interval, "requestedAssetType": selected_asset_type,
        "analysisReady": analysis_ready, "analysisMode": analysis_mode, "realtimeReady": market_ready, "providerReady": market_ready, "globalProviderReady": bool(capability.get("globalProviderReady", False)),
        "multiMarketReady": bool(capability.get("multiMarketReady", False)), "failures": failures, "failedCount": len(failures),
        "scanPolicy": {
            "batchSize": SCANNER_BATCH_SIZE,
            "concurrency": SCANNER_CONCURRENCY,
            "pacingMs": SCANNER_PACING_MS,
            "configuredPacingMs": SCANNER_CONFIGURED_PACING_MS,
            "minimumPacingMs": SCANNER_MIN_PACING_MS,
            "rateLimitWeightPerMinute": SCANNER_RATE_LIMIT_WEIGHT_PER_MINUTE,
            "requestWeight": SCANNER_REQUEST_WEIGHT,
            "quotaScope": UPSTREAM_QUOTA_SCOPE,
            "providerWeightFormulaVerified": False,
            "distributedProviderQuotaConfigured": bool(UPSTREAM_DISTRIBUTED_QUOTA and REDIS_URL),
            "snapshotBatchMaxSymbols": SNAPSHOT_BATCH_MAX_SYMBOLS,
            "cacheTtlMs": BAR_CACHE_TTL_SECONDS * 1000,
            "serverBatchEnforced": True,
            "proactiveRateLimitEnforced": True,
        },
    }


def _tradingview_ready() -> bool:
    return bool(TRADINGVIEW_WEBHOOK_SECRET and TRADINGVIEW_DB_PATH)


def _tradingview_connect() -> sqlite3.Connection:
    if not _tradingview_ready():
        raise RuntimeError("TradingView store is not configured")
    path = Path(TRADINGVIEW_DB_PATH)
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=5.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS tradingview_signals (
            id TEXT PRIMARY KEY,
            semantic_key TEXT NOT NULL UNIQUE,
            symbol TEXT NOT NULL,
            action TEXT NOT NULL,
            price REAL,
            signal_time INTEGER NOT NULL,
            received_at INTEGER NOT NULL,
            interval TEXT,
            strategy TEXT,
            message TEXT,
            rsi REAL,
            macd REAL,
            macd_signal REAL,
            macd_histogram REAL,
            ema_fast REAL,
            ema_slow REAL,
            atr REAL,
            volume REAL,
            volume_ratio REAL,
            score REAL,
            trend TEXT,
            bar_confirmed INTEGER,
            raw_json TEXT NOT NULL
        )
        """
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_tv_received ON tradingview_signals(received_at DESC)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_tv_symbol_time ON tradingview_signals(symbol, signal_time DESC)")
    conn.commit()
    return conn


def _tradingview_number(payload: dict[str, Any], key: str, *, minimum: float | None = None, maximum: float | None = None) -> float | None:
    value = payload.get(key)
    if value is None or value == "":
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise HTTPException(status_code=400, detail=f"TRADINGVIEW_WEBHOOK_INVALID_{key.upper()}")
    if minimum is not None and number < minimum:
        raise HTTPException(status_code=400, detail=f"TRADINGVIEW_WEBHOOK_INVALID_{key.upper()}")
    if maximum is not None and number > maximum:
        raise HTTPException(status_code=400, detail=f"TRADINGVIEW_WEBHOOK_INVALID_{key.upper()}")
    return number


def _normalize_tradingview_payload(payload: dict[str, Any], raw_body: bytes) -> dict[str, Any]:
    symbol = str(payload.get("symbol", "")).strip().upper()
    action = str(payload.get("action", payload.get("signal", ""))).strip().upper()
    if not symbol or not symbol.replace("_", "").isalnum() or len(symbol) > 32:
        raise HTTPException(status_code=400, detail="TRADINGVIEW_WEBHOOK_INVALID_SYMBOL")
    if action not in {"BUY", "SELL", "LONG", "SHORT", "WATCH", "EXIT"}:
        raise HTTPException(status_code=400, detail="TRADINGVIEW_WEBHOOK_INVALID_ACTION")
    signal_time = normalize_timestamp(payload.get("signalTime", payload.get("timestamp", payload.get("time"))))
    if signal_time <= 0:
        raise HTTPException(status_code=400, detail="TRADINGVIEW_WEBHOOK_INVALID_SIGNAL_TIME")
    now_ms = int(time.time() * 1000)
    if signal_time > now_ms + 300_000:
        raise HTTPException(status_code=400, detail="TRADINGVIEW_WEBHOOK_SIGNAL_TIME_IN_FUTURE")
    interval = str(payload.get("interval", "")).strip()[:32] or None
    strategy = str(payload.get("strategy", "")).strip()[:128] or None
    message = str(payload.get("message", "")).strip()[:1000] or None
    event_id = str(payload.get("eventId", payload.get("id", ""))).strip()[:128]
    if not event_id:
        event_id = hashlib.sha256(raw_body).hexdigest()
    bucket = signal_time // TRADINGVIEW_DEDUPE_WINDOW_MS
    semantic_material = "|".join([symbol, action, interval or "", strategy or "", str(bucket)])
    semantic_key = hashlib.sha256(semantic_material.encode("utf-8")).hexdigest()
    bar_confirmed_raw = payload.get("barConfirmed")
    bar_confirmed = None if bar_confirmed_raw is None else bool(bar_confirmed_raw)
    return {
        "id": event_id,
        "semanticKey": semantic_key,
        "symbol": symbol,
        "action": action,
        "price": _tradingview_number(payload, "price", minimum=0.0),
        "signalTime": signal_time,
        "receivedAt": now_ms,
        "interval": interval,
        "strategy": strategy,
        "message": message,
        "rsi": _tradingview_number(payload, "rsi"),
        "macd": _tradingview_number(payload, "macd"),
        "macdSignal": _tradingview_number(payload, "macdSignal"),
        "macdHistogram": _tradingview_number(payload, "macdHistogram"),
        "emaFast": _tradingview_number(payload, "emaFast", minimum=0.0),
        "emaSlow": _tradingview_number(payload, "emaSlow", minimum=0.0),
        "atr": _tradingview_number(payload, "atr", minimum=0.0),
        "volume": _tradingview_number(payload, "volume", minimum=0.0),
        "volumeRatio": _tradingview_number(payload, "volumeRatio", minimum=0.0),
        "score": _tradingview_number(payload, "score", minimum=0.0, maximum=100.0),
        "trend": str(payload.get("trend", "")).strip()[:64] or None,
        "barConfirmed": bar_confirmed,
        "rawJson": raw_body.decode("utf-8"),
    }


def _tradingview_cleanup(conn: sqlite3.Connection, now_ms: int) -> None:
    cutoff = now_ms - TRADINGVIEW_RETENTION_DAYS * 86_400_000
    conn.execute("DELETE FROM tradingview_signals WHERE received_at < ?", (cutoff,))


def _tradingview_insert(signal: dict[str, Any]) -> tuple[bool, str]:
    conn = _tradingview_connect()
    try:
        _tradingview_cleanup(conn, int(signal["receivedAt"]))
        cursor = conn.execute(
            """
            INSERT OR IGNORE INTO tradingview_signals (
                id, semantic_key, symbol, action, price, signal_time, received_at, interval, strategy, message,
                rsi, macd, macd_signal, macd_histogram, ema_fast, ema_slow, atr, volume, volume_ratio, score,
                trend, bar_confirmed, raw_json
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                signal["id"], signal["semanticKey"], signal["symbol"], signal["action"], signal["price"],
                signal["signalTime"], signal["receivedAt"], signal["interval"], signal["strategy"], signal["message"],
                signal["rsi"], signal["macd"], signal["macdSignal"], signal["macdHistogram"], signal["emaFast"],
                signal["emaSlow"], signal["atr"], signal["volume"], signal["volumeRatio"], signal["score"],
                signal["trend"], None if signal["barConfirmed"] is None else int(signal["barConfirmed"]), signal["rawJson"],
            ),
        )
        conn.commit()
        inserted = cursor.rowcount == 1
        return inserted, "INSERTED" if inserted else "DUPLICATE"
    finally:
        conn.close()


def _tradingview_list(limit: int, cursor: int | None) -> dict[str, Any]:
    conn = _tradingview_connect()
    try:
        now_ms = int(time.time() * 1000)
        _tradingview_cleanup(conn, now_ms)
        params: list[Any] = []
        where = ""
        if cursor is not None:
            where = "WHERE rowid < ?"
            params.append(cursor)
        params.append(limit + 1)
        rows = conn.execute(
            f"SELECT rowid, * FROM tradingview_signals {where} ORDER BY rowid DESC LIMIT ?", params
        ).fetchall()
        conn.commit()
        has_more = len(rows) > limit
        rows = rows[:limit]
        items: list[dict[str, Any]] = []
        for row in rows:
            signal_age = max(0, now_ms - int(row["signal_time"]))
            items.append({
                "id": row["id"], "symbol": row["symbol"], "action": row["action"], "price": row["price"],
                "signalTime": int(row["signal_time"]), "sourceAt": int(row["signal_time"]), "receivedAt": int(row["received_at"]),
                "interval": row["interval"], "strategy": row["strategy"], "message": row["message"],
                "rsi": row["rsi"], "macd": row["macd"], "macdSignal": row["macd_signal"],
                "macdHistogram": row["macd_histogram"], "emaFast": row["ema_fast"], "emaSlow": row["ema_slow"],
                "atr": row["atr"], "volume": row["volume"], "volumeRatio": row["volume_ratio"], "score": row["score"],
                "trend": row["trend"], "barConfirmed": None if row["bar_confirmed"] is None else bool(row["bar_confirmed"]),
                "signalAgeMs": signal_age, "freshSignal": signal_age <= TRADINGVIEW_SIGNAL_FRESHNESS_MS,
                "source": "TRADINGVIEW_WEBHOOK", "marketDataRealtime": False,
            })
        next_cursor = int(rows[-1]["rowid"]) if has_more and rows else None
        return {"items": items, "hasMore": has_more, "nextCursor": str(next_cursor) if next_cursor else None, "receivedAt": now_ms}
    finally:
        conn.close()


@app.post("/v1/tradingview/webhook")
async def tradingview_webhook(
    request: Request,
    x_tradingview_signature: str | None = Header(default=None, alias="X-TradingView-Signature"),
):
    if not _tradingview_ready():
        raise HTTPException(status_code=503, detail={"code": "FEATURE_DISABLED", "feature": "tradingViewSignals"})
    raw = await request.body()
    if not raw or len(raw) > 65_536:
        raise HTTPException(status_code=400, detail="TRADINGVIEW_WEBHOOK_INVALID_BODY")
    supplied = (x_tradingview_signature or "").strip()
    if supplied.lower().startswith("sha256="):
        supplied = supplied[7:]
    expected = hmac.new(TRADINGVIEW_WEBHOOK_SECRET.encode("utf-8"), raw, hashlib.sha256).hexdigest()
    if not supplied or not hmac.compare_digest(supplied.lower(), expected.lower()):
        raise HTTPException(status_code=401, detail="TRADINGVIEW_WEBHOOK_SIGNATURE_INVALID")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise HTTPException(status_code=400, detail="TRADINGVIEW_WEBHOOK_INVALID_JSON")
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="TRADINGVIEW_WEBHOOK_INVALID_JSON")
    signal = _normalize_tradingview_payload(payload, raw)
    inserted, status = await asyncio.to_thread(_tradingview_insert, signal)
    return {"ok": True, "accepted": inserted, "status": status, "id": signal["id"], "receivedAt": signal["receivedAt"]}


@app.get("/v1/tradingview/signals")
async def tradingview_signals(
    limit: int = Query(50, ge=1, le=200),
    cursor: int | None = Query(default=None, ge=1),
    authorization: str | None = Header(default=None),
):
    await require_app_auth(authorization)
    if not _tradingview_ready():
        raise HTTPException(status_code=503, detail={"code": "FEATURE_DISABLED", "feature": "tradingViewSignals", "message": "Kalıcı TradingView webhook deposu yapılandırılmadı; sahte boş liste döndürülmez."})
    return await asyncio.to_thread(_tradingview_list, limit, cursor)


@app.get("/v1/research/foundation")
async def research_foundation_disabled(
    symbol: str = Query(..., min_length=1),
    category: str = Query("ALL"),
    asOfTime: int | None = Query(default=None),
    authorization: str | None = Header(default=None),
):
    del symbol, category, asOfTime
    await require_app_auth(authorization)
    raise HTTPException(status_code=503, detail={"code": "FEATURE_DISABLED", "feature": "researchFoundation", "message": "Doğrulanmış research foundation backend'i etkin değil; yerel fallback ayrı katmanda kullanılabilir."})


@app.get("/v1/realtime/opportunities")
async def realtime_opportunities(
    limit: int = Query(100, ge=1, le=200),
    symbols: str | None = Query(default=None),
    analysisTimeframeMinutes: int = Query(5, ge=1, le=239),
    scanCadenceMinutes: int = Query(5, ge=1, le=1440),
    scanMode: str = Query("MANUAL"),
    authorization: str | None = Header(default=None),
):
    """Reserved V538 realtime contract. Never aliases the dynamic/closed-bar scanner."""
    await require_app_auth(authorization)
    raise HTTPException(
        status_code=503,
        detail={
            "code": "REALTIME_SCANNER_NOT_AVAILABLE",
            "message": "V538 attestation/replay sözleşmesi backend tarafında etkin değil; dinamik scanner sonucu realtime diye yükseltilmez.",
            "realtime": False,
            "attestationReady": False,
    "attestationConfigured": bool(ATTESTATION_PRIVATE_KEYS_JSON and ATTESTATION_ACTIVE_KEY_ID and ATTESTATION_ACTIVE_KEY_GENERATION > 0),
        },
    )


async def _reject_disabled_websocket(websocket: WebSocket, feature: str) -> None:
    authorization = websocket.headers.get("authorization", "").strip()
    installation_id = websocket.headers.get("x-install-id", "").strip()
    auth_valid = not APP_API_KEY
    if APP_API_KEY and authorization == f"Bearer {APP_API_KEY}":
        auth_valid = True
    elif authorization.startswith("BorsaSession "):
        token = authorization.removeprefix("BorsaSession ").strip()
        auth_valid = _verify_session_token(token, expected_installation_id=installation_id)
    if APP_API_KEY and not auth_valid:
        await websocket.close(code=1008, reason="BACKEND_AUTH_INVALID")
        return
    if APP_ENV == "production" and not APP_API_KEY:
        await websocket.close(code=1011, reason="BACKEND_AUTH_NOT_CONFIGURED")
        return
    await websocket.accept()
    await websocket.send_json({
        "type": "error",
        "code": "FEATURE_DISABLED",
        "feature": feature,
        "detail": "Backend capability bu WebSocket özelliğini etkin ilan etmiyor.",
    })
    await websocket.close(code=1013, reason="feature-disabled")


@app.websocket("/v1/live")
async def live_market_socket(websocket: WebSocket):
    await _reject_disabled_websocket(websocket, "liveMarketWebSocket")


@app.websocket("/v1/scanner/live")
async def realtime_scanner_socket(websocket: WebSocket):
    await _reject_disabled_websocket(websocket, "realtimeScannerWebSocket")


@app.get("/v1/news")
async def news(category: str = "ALL", symbol: str | None = None, authorization: str | None = Header(default=None)):
    await require_app_auth(authorization)
    return {"items": [], "category": category.upper(), "symbol": symbol.upper() if symbol else None, "source": "NO_NEWS_PROVIDER_CONFIGURED"}
