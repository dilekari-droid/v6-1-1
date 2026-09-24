import json
import re
from pathlib import Path

from fastapi.routing import APIRoute, APIWebSocketRoute

from main import app

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "contracts" / "routes-v1.json"
ANDROID = ROOT / "android" / "app" / "src" / "main" / "java"

_STRING_LITERAL = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')
_ROUTE_FRAGMENT = re.compile(r'/?v1/[A-Za-z0-9_./{}$-]+')
_DYNAMIC_SEGMENT = re.compile(r'/\$(?:\{[^}]+\}|[A-Za-z_][A-Za-z0-9_]*)')
_TRAILING_INTERPOLATION = re.compile(r'\$(?:\{[^}]+\}|[A-Za-z_][A-Za-z0-9_]*)$')
_CONTRACT_PARAM = re.compile(r'\{[^}/]+\}')


def _normalize_android_path(raw: str) -> str:
    path = raw if raw.startswith("/") else f"/{raw}"
    # `$query` is appended to a fixed path in a few clients; it is not a path segment.
    if "/$" not in path:
        path = _TRAILING_INTERPOLATION.sub("", path)
    path = _DYNAMIC_SEGMENT.sub("/{param}", path)
    return path.rstrip("/") or "/"


def _structural(path: str) -> str:
    return _CONTRACT_PARAM.sub("{param}", path.rstrip("/"))


def _discover_android_routes() -> dict[str, set[str]]:
    found: dict[str, set[str]] = {}
    for source in ANDROID.rglob("*.kt"):
        text = source.read_text(encoding="utf-8")
        for literal in _STRING_LITERAL.findall(text):
            for match in _ROUTE_FRAGMENT.finditer(literal):
                path = _normalize_android_path(match.group(0))
                found.setdefault(_structural(path), set()).add(str(source.relative_to(ANDROID)))
    return found


def _contract_routes() -> list[dict]:
    payload = json.loads(CONTRACT.read_text(encoding="utf-8"))
    assert payload["scope"] == "all-android-backend-routes"
    return payload["routes"]


def test_contract_is_bidirectionally_complete_against_android_source_discovery():
    discovered = _discover_android_routes()
    contract = _contract_routes()
    contract_structural = {_structural(item["path"]): item for item in contract}

    missing_from_contract = sorted(set(discovered) - set(contract_structural))
    stale_contract_entries = sorted(set(contract_structural) - set(discovered))
    assert not missing_from_contract, (
        "Android source uses backend routes missing from routes-v1.json: "
        + ", ".join(f"{path} <- {sorted(discovered[path])}" for path in missing_from_contract)
    )
    assert not stale_contract_entries, (
        "routes-v1.json contains routes no longer referenced by Android source: "
        + ", ".join(stale_contract_entries)
    )


def test_every_discovered_contract_route_exists_in_fastapi_with_declared_method():
    http_routes: set[tuple[str, str]] = set()
    websocket_routes: set[tuple[str, str]] = set()
    for route in app.routes:
        if isinstance(route, APIRoute):
            for method in route.methods or set():
                http_routes.add((method.upper(), _structural(route.path)))
        elif isinstance(route, APIWebSocketRoute):
            websocket_routes.add(("WS", _structural(route.path)))

    for item in _contract_routes():
        declared = (item["method"].upper(), _structural(item["path"]))
        if declared[0] == "WS":
            assert declared in websocket_routes, f"Missing backend websocket route {item['path']}"
        else:
            assert declared in http_routes, f"Missing backend HTTP route {item['method']} {item['path']}"


def test_route_discovery_normalizes_dynamic_and_websocket_style_literals():
    assert _normalize_android_path('/v1/bist/quote/$safe') == '/v1/bist/quote/{param}'
    assert _normalize_android_path('/v1/bist/history/${symbol}') == '/v1/bist/history/{param}'
    assert _normalize_android_path('/v1/scanner/live') == '/v1/scanner/live'
    discovered = _discover_android_routes()
    assert _structural('/v1/bist/quote/{symbol}') in discovered
    assert _structural('/v1/bist/history/{symbol}') in discovered
    assert _structural('/v1/live') in discovered
    assert _structural('/v1/scanner/live') in discovered
