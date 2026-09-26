export function canonicalSymbol(v) {
  return String(v || '').trim().toUpperCase();
}

export function finite(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

export function positive(v) {
  const n = finite(v);
  return n != null && n > 0 ? n : null;
}

export function toMs(v) {
  if (v == null || v === '') return null;
  const n = Number(v);
  if (Number.isFinite(n) && n > 0) {
    return n < 10_000_000_000 ? Math.round(n * 1000) : Math.round(n);
  }
  const d = Date.parse(String(v));
  return Number.isFinite(d) && d > 0 ? d : null;
}

export function toYearMonth(v, fallbackMs = null) {
  const s = String(v || '').trim();
  if (/^\d{4}-(0[1-9]|1[0-2])$/.test(s)) return s;
  const ms = fallbackMs || toMs(v);
  if (!ms) return '';
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

export function canonicalContract(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const symbol = canonicalSymbol(raw.symbol);
  if (!symbol) return null;

  // expiry is a YYYY-MM metadata label. Do not reinterpret it as midnight
  // on the first day of the month. Active/expired state is decided from the
  // provider's real lastTradingAt and explicit expiryAt when present.
  const expiryAt = toMs(raw.expiryAt);
  const lastTradingAt = toMs(raw.lastTradingAt);
  const expiry = toYearMonth(raw.expiry, expiryAt);

  let contractType = String(raw.contractType || '').trim().toUpperCase();
  if (!contractType && /^F_/.test(symbol)) contractType = 'FUTURE';

  return {
    ...raw,
    symbol,
    underlying: canonicalSymbol(raw.underlying),
    expiry,
    expiryAt,
    lastTradingAt,
    tickSize: positive(raw.tickSize),
    multiplier: positive(raw.multiplier),
    currency: String(raw.currency || '').trim().toUpperCase(),
    contractType,
  };
}

export function metadataVerdict(contract) {
  if (!contract) return { ok: false, code: 'CONTRACT_NULL', missing: ['contract'] };

  const missing = [];
  if (!contract.symbol) missing.push('symbol');
  if (!contract.underlying) missing.push('underlying');
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(contract.expiry || '')) missing.push('expiry');
  if (!(Number(contract.lastTradingAt) > 0)) missing.push('lastTradingAt');
  if (!(Number(contract.tickSize) > 0)) missing.push('tickSize');
  if (!(Number(contract.multiplier) > 0)) missing.push('multiplier');
  if (!contract.currency) missing.push('currency');
  if (contract.contractType !== 'FUTURE') missing.push('contractType');

  return {
    ok: missing.length === 0,
    code: missing.length ? 'VIOP_CONTRACT_METADATA_UNAVAILABLE' : null,
    missing,
  };
}

export function activeFutureVerdict(contract, nowMs = Date.now()) {
  const meta = metadataVerdict(contract);
  if (!meta.ok) return { ok: false, code: meta.code, missing: meta.missing };

  if (!(contract.lastTradingAt > nowMs)) {
    return { ok: false, code: 'VIOP_CONTRACT_EXPIRED' };
  }
  if (contract.expiryAt != null && !(contract.expiryAt > nowMs)) {
    return { ok: false, code: 'VIOP_CONTRACT_EXPIRED' };
  }
  return { ok: true, code: null };
}

export function splitReadiness(items, nowMs = Date.now()) {
  const canonical = (Array.isArray(items) ? items : [])
    .map(canonicalContract)
    .filter(Boolean);

  const checked = canonical.map((contract) => ({
    contract,
    metadata: metadataVerdict(contract),
  }));

  const metadataValid = checked
    .filter((x) => x.metadata.ok)
    .map((x) => x.contract);

  const active = metadataValid.filter((contract) =>
    activeFutureVerdict(contract, nowMs).ok
  );

  return {
    contractsReady: canonical.length > 0,
    metadataReady: canonical.length > 0 && checked.every((x) => x.metadata.ok),
    activeFutureReady: active.length > 0,
    contracts: canonical,
    metadataValid,
    active,
    firstMetadataFailure: checked.find((x) => !x.metadata.ok)?.metadata || null,
  };
}
