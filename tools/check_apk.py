"""Validate the release package without signing keys or a device."""
import os
from pathlib import Path
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def sdk_path():
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(variable):
            return Path(os.environ[variable])
    local = ROOT / "local.properties"
    if local.exists():
        for line in local.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    raise RuntimeError("Set ANDROID_HOME to the Android SDK")

def tool(name):
    suffix = ".bat" if name == "apksigner" and os.name == "nt" else ".exe" if os.name == "nt" else ""
    return sdk_path() / "build-tools" / "37.0.0" / (name + suffix)

def defined_classes(data):
    def u32(offset): return struct.unpack_from("<I", data, offset)[0]
    strings = u32(60)
    types = u32(68)
    for index in range(u32(96)):
        class_id = u32(u32(100) + index * 32)
        descriptor = u32(types + class_id * 4)
        offset = u32(strings + descriptor * 4)
        while data[offset] & 128: offset += 1
        offset += 1
        yield data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace")

def check(apk):
    badging = subprocess.check_output([str(tool("aapt2")), "dump", "badging", str(apk)], text=True, encoding="utf-8")
    assert "package: name='dev.ryuya.homepodbridge'" in badging
    assert "application-debuggable" not in badging
    assert "application-testOnly" not in badging
    with zipfile.ZipFile(apk) as archive:
        assert archive.read("assets/xposed_init").strip() == b"dev.ryuya.homepodbridge.VectorModule"
        assert "Apache License" in archive.read("assets/licenses.txt").decode()
        for abi in ("arm64-v8a", "x86_64"):
            assert f"lib/{abi}/libhomepod_bridge.so" in archive.namelist()
        for name in archive.namelist():
            assert ".signing" not in name and not name.endswith((".keystore", ".p12"))
            if name.endswith(".dex"):
                for cls in defined_classes(archive.read(name)):
                    assert not cls.startswith("Lde/robv/android/xposed/"), "Xposed framework must remain compileOnly"
    print("Release APK: package, module, ABIs, licenses and non-debuggable manifest verified")
    return badging

if __name__ == "__main__":
    check(Path(sys.argv[1]))
