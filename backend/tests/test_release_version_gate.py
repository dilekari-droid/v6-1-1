from pathlib import Path

from scripts_release_version_gate_loader import load_module


def test_current_source_passes_only_when_556_explicitly_verified_unused():
    mod = load_module()
    root = Path(__file__).resolve().parents[2]
    code, name = mod.read_version(root / "android" / "app" / "build.gradle.kts")
    assert (code, name) == (556, "5.4.16")
    assert mod.validate("verified-unused-556", code, name) == []
    assert mod.validate("incremented-after-556", code, name)
