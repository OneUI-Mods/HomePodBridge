"""Build the closed root-only period diagnostic; this never executes it on a device."""
import argparse
import hashlib
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parent.parent
EXPECTED = "2a17b3593bf2c68221d4becd9f120f7c13730791945111a38d24805f462dc431"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if "30.0.14904198" not in (args.ndk / "source.properties").read_text():
        raise RuntimeError("The executable is pinned to NDK 30.0.14904198")
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    compiler = args.ndk / "toolchains/llvm/prebuilt" / host / "bin" / (
        "aarch64-linux-android36-clang.cmd" if os.name == "nt" else "aarch64-linux-android36-clang"
    )
    subprocess.run([str(compiler), "-O2", "-Wall", "-Wextra", "-Werror", "-Wl,--build-id=sha1",
                    "tools/native/output_period_probe_cli.c", "-ldl", "-o", str(args.output.resolve())],
                   cwd=ROOT, check=True)
    digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
    print("Executable SHA256:", digest)
    if digest != EXPECTED:
        raise RuntimeError("Build does not match the pinned executable; do not provision it")
    print("Pinned native diagnostic built; no device operation performed")


if __name__ == "__main__":
    main()
