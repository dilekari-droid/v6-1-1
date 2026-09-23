from __future__ import annotations

import base64
import hashlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import urllib.request

REPO = "dilekari-droid/v6-1-1"
PAYLOAD_SHA256 = "a8272efc8853e500d3a83578fa6658da1a10157ae865eca7a6342c82eae98e61"
PART_COUNT = 10


def main() -> None:
    commit = os.environ.get("R4_GITHUB_SHA", "").strip()
    if len(commit) != 40:
        raise SystemExit("R4_GITHUB_SHA must be an exact 40-character commit SHA")

    base = f"https://raw.githubusercontent.com/{REPO}/{commit}/.ci/v611-backend-r4"
    encoded = bytearray()
    for index in range(PART_COUNT):
        url = f"{base}/part{index:02d}.b64"
        with urllib.request.urlopen(url, timeout=30) as response:
            encoded.extend(response.read().strip())

    archive = base64.b64decode(bytes(encoded), validate=True)
    digest = hashlib.sha256(archive).hexdigest()
    if digest != PAYLOAD_SHA256:
        raise SystemExit(f"R4 backend payload SHA-256 mismatch: {digest}")

    target = Path("/tmp/v611-r4")
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:xz") as bundle:
        try:
            bundle.extractall(target, filter="data")
        except TypeError:
            bundle.extractall(target)

    backend = target / "backend"
    requirements = backend / "requirements.txt"
    main_py = backend / "main.py"
    if not requirements.is_file() or not main_py.is_file():
        raise SystemExit("R4 backend payload is incomplete")

    subprocess.check_call([sys.executable, "-m", "pip", "install", "--no-cache-dir", "-r", str(requirements)])
    os.chdir(backend)
    port = os.environ.get("PORT", "8080")
    os.execvp(sys.executable, [sys.executable, "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", port])


if __name__ == "__main__":
    main()
