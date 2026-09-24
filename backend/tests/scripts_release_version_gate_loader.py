from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path


def load_module():
    path = Path(__file__).resolve().parents[2] / "scripts" / "release-version-gate.py"
    spec = spec_from_file_location("release_version_gate", path)
    assert spec and spec.loader
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
