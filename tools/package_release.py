"""Build, sign and verify a release; keep signing material outside Git and CI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import zipfile
from check_apk import ROOT, check, tool

def run(args, **kwargs):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True, **kwargs)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--initialize-key", action="store_true")
    parser.add_argument("--source-ref", default="HEAD")
    args = parser.parse_args()
    signing = ROOT / ".signing"
    signing.mkdir(exist_ok=True)
    if os.name == "nt":
        account = subprocess.check_output(["whoami"], text=True).strip()
        run(["icacls", signing, "/inheritance:r", "/grant:r", f"{account}:(OI)(CI)F", "SYSTEM:(OI)(CI)F"], stdout=subprocess.DEVNULL)
    else:
        signing.chmod(0o700)
    key = signing / "release.p12"
    password_file = signing / "password"
    if args.initialize_key and not key.exists():
        if not password_file.exists():
            password_file.write_text(secrets.token_urlsafe(48), encoding="ascii")
            if os.name != "nt": password_file.chmod(0o600)
        environment = os.environ.copy()
        environment["HOMEPOD_SIGN_PASSWORD"] = password_file.read_text().strip()
        keytool = Path(os.environ["JAVA_HOME"]) / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")
        run([keytool, "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-alias", "homepodbridge-release",
             "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000", "-dname", "CN=HomePod Bridge",
             "-storepass:env", "HOMEPOD_SIGN_PASSWORD"], env=environment)
    if not key.exists() or not password_file.exists(): raise RuntimeError("Initialize a private local signing key first")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT): raise RuntimeError("Commit changes before packaging")
    commit = subprocess.check_output(["git", "rev-parse", args.source_ref], cwd=ROOT, text=True).strip()
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if commit != head: raise RuntimeError("Package the checked-out HEAD to match the source archive")
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    run([gradle, ":app:testDebugUnitTest", ":app:lintRelease", ":app:assembleRelease", "--console=plain"])
    unsigned = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
    badging = check(unsigned)
    version = re.search(r"versionName='([^']+)'", badging)[1]
    version_code = int(re.search(r"versionCode='(\d+)'", badging)[1])
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    apk = dist / f"HomePodBridge-{version}-release.apk"
    aligned = dist / "aligned.apk"
    run([tool("zipalign"), "-P", "16", "-f", "4", unsigned, aligned])
    environment = os.environ.copy()
    environment["HOMEPOD_SIGN_PASSWORD"] = password_file.read_text().strip()
    run([tool("apksigner"), "sign", "--ks", key, "--ks-key-alias", "homepodbridge-release", "--ks-pass", "env:HOMEPOD_SIGN_PASSWORD", "--out", apk, aligned], env=environment)
    verification = subprocess.check_output([str(tool("apksigner")), "verify", "--verbose", "--print-certs", str(apk)], cwd=ROOT, text=True)
    signature = dist / f"HomePodBridge-{version}-signature.txt"
    signature.write_text(verification, encoding="utf-8")
    check(apk)
    run([tool("zipalign"), "-c", "-P", "16", "-v", "4", apk], stdout=subprocess.DEVNULL)
    certificate = re.search(r"certificate SHA-256 digest: (\w+)", verification)[1]
    public_cert = signing / "certificate-sha256.txt"
    if public_cert.exists() and public_cert.read_text().strip() != certificate: raise RuntimeError("Signing identity changed")
    public_cert.write_text(certificate + "\n", encoding="ascii")
    source = dist / f"HomePodBridge-{version}-source.zip"
    run(["git", "archive", "--format=zip", f"--prefix=HomePodBridge-{version}/", "-o", source, commit])
    with zipfile.ZipFile(source) as archive:
        assert not any(".signing/" in path or ".research/" in path for path in archive.namelist())
    metadata = dist / "release-metadata.json"
    metadata.write_text(json.dumps({"packageName": "dev.ryuya.homepodbridge", "versionCode": version_code,
        "versionName": version, "commit": commit, "apk": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "certificateSha256": certificate}, indent=2) + "\n")
    files = [apk, source, signature, metadata]
    (dist / f"SHA256SUMS-{version}.txt").write_text("".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in files), encoding="ascii")
    aligned.unlink()
    (dist / (apk.name + ".idsig")).unlink(missing_ok=True)
    print("Verified signed release and matching source:", apk.name)

if __name__ == "__main__": main()
