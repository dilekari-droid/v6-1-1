# V6.1.1 VİOP R4 production runtime

R4 is fail-closed. It must not declare VİOP READY or produce a production LONG/SHORT path until provider authentication, contract metadata, live/current-session quote and OHLC/history are all verified.

## Already configured non-secret endpoints

- `TRADEWIZE_BASE_URL=https://api.tradewize.com.tr`
- `TRADEWIZE_TOKEN_URL=https://api.tradewize.com.tr/oauth/token`
- `TRADEWIZE_DETAILS_URL=https://api.tradewize.com.tr/api/v1/market-data/viop/last-price/details?all=true`
- `VIOP_MAX_QUOTE_AGE_MS=60000`
- `VIOP_MAX_DELAY_SECONDS=5`

The details endpoint is used as a discovery/quote source only if its real response proves universe completeness and contains the mandatory metadata. No missing metadata is synthesized.

## Authentication — one explicit mode is required

R4 intentionally does not guess the provider authentication contract.

### Direct access token

Set `TRADEWIZE_ACCESS_TOKEN`.

### API key sent directly to data endpoints

Set all of:

- `TRADEWIZE_AUTH_MODE=API_KEY_HEADER`
- `TRADEWIZE_API_KEY=<production secret>`
- `TRADEWIZE_API_KEY_HEADER=<documented provider header name>`

### OAuth/API-key token exchange

Set all of:

- `TRADEWIZE_AUTH_MODE=OAUTH_API_KEY`
- `TRADEWIZE_API_KEY=<production secret>`
- `TRADEWIZE_API_KEY_HEADER=<documented provider header name>`
- `TRADEWIZE_TOKEN_URL=<documented token URL>`
- optionally `TRADEWIZE_TOKEN_HEADERS_JSON`
- optionally `TRADEWIZE_TOKEN_BODY_JSON`
- optionally `TRADEWIZE_TOKEN_FIELD` (default `access_token`)
- optionally `TRADEWIZE_TOKEN_TTL_FIELD` (default `expires_in`)

`TRADEWIZE_TOKEN_BODY_JSON` can use `{apiKey}` only when the provider documentation requires the API key in a body value. Secrets are never hardcoded into source.

## Provider response field mapping

If provider field names differ from the canonical contract, set `TRADEWIZE_FIELD_MAP_JSON`.

Mandatory contract metadata after mapping:

- `symbol`
- `underlying` (may be checked against a safely parsed FUTURE symbol)
- `expiry` (`YYYY-MM`)
- `lastTradingAt`
- `tickSize > 0`
- `multiplier > 0`
- `currency` (3-letter code)

Mandatory live quote semantics after mapping:

- positive `price`
- valid exchange/data timestamp
- `realtime=true`
- `currentSessionIncluded=true`
- non-negative declared delay not exceeding configured limit

Missing values are rejected; they are not fabricated.

## Contract universe completeness

R4 accepts the discovery response as complete only when one of these is proven:

- `X-TradeWise-Selection-Complete: true/1/yes`, or
- `totalCount == number of raw returned provider records` and `hasMore != true`.

Metadata readiness then additionally requires every discovered FUTURE contract to pass metadata validation.

If a separate documented universe endpoint exists, configure `TRADEWIZE_UNIVERSE_URL`; otherwise R4 attempts to use the verified details endpoint and still requires completeness proof.

## OHLC/history

A documented provider history endpoint is required. Configure:

- `TRADEWIZE_HISTORY_URL_TEMPLATE=https://.../{symbol}...`
- optionally `TRADEWIZE_HISTORY_FIELD_MAP_JSON`

The response must prove:

- matching canonical `symbol`
- requested `interval`
- `lastBarClosed=true`
- valid exchange timestamp
- ordered and internally consistent OHLCV candles

Without a documented history endpoint R4 returns `NO_HISTORY`; it does not synthesize candles.

## Health endpoint

`GET /v1/viop/health`

Reports independently:

- provider/connection/capability state
- `contractCount`
- `metadataValidCount`
- `metadataInvalidCount`
- `activeContractCount`
- `liveQuoteCount`
- `staleQuoteCount`
- last quote timestamp and age
- session state
- OHLC/history readiness
- technical-engine readiness
- explicit reason/error code

## Publication gate

Android VİOP scanning remains:

`contracts -> live quote -> history/OHLC -> liquidity/freshness -> TechnicalAnalyzer -> V531 -> VİOP-specific analysis -> LONG/SHORT/WATCH`

Any failed upstream gate returns NO_DATA/REJECTED/INSUFFICIENT and does not fabricate a LONG/SHORT signal.
