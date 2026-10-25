"""Check the vendored notices, generated in-app text, and pinned native dependency."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
vendor = root / "third_party/airplay2-sender-cpp"
for path in [root / "LICENSE", vendor / "LICENSE", vendor / "NOTICE", vendor / "licenses/THIRD-PARTY-NOTICES.txt", vendor / "third_party/ed25519/LICENSE.txt"]:
    assert path.is_file() and path.stat().st_size > 200, path
asset = (root / "app/src/main/assets/licenses.txt").read_text(encoding="utf-8")
for path in [root / "LICENSE", vendor / "NOTICE", vendor / "licenses/THIRD-PARTY-NOTICES.txt"]:
    assert path.read_text(encoding="utf-8") in asset, path
assert re.search(r"GIT_TAG\s+[a-f0-9]{40}", (vendor / "CMakeLists.txt").read_text())
assert re.search(r"[a-f0-9]{40}", (vendor / "UPSTREAM.txt").read_text())
assert "compileOnly 'de.robv.android.xposed:api:82'" in (root / "app/build.gradle").read_text()
print("Native dependencies are pinned; licenses and in-app notices match")
