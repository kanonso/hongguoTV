#!/usr/bin/env python3
"""Package independently installable Android TV debug APKs for real-device testing."""
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "kotlin-tv/app"
RELEASE_CERTIFICATE = "3579f7a8a91bbbc44688db60fb16a5561921784af23fa92f39b849086d76ca98"

VARIANTS = (
    ("", "app-debug.apk", "android5", 21, []),
    ("platform/", "app-universal-debug.apk", "android6", 23, []),
    ("platform/", "app-armeabi-v7a-debug.apk", "android6-arm32-v7a", 23,
     [{"filterType": "ABI", "value": "armeabi-v7a"}]),
    ("platform/", "app-arm64-v8a-debug.apk", "android6-arm64-v8a", 23,
     [{"filterType": "ABI", "value": "arm64-v8a"}]),
)


def run(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    android_home = Path(os.environ["ANDROID_HOME"])
    build_tools = android_home / "build-tools/36.0.0"
    aapt = build_tools / "aapt"
    apksigner = build_tools / "apksigner"
    gradle = (APP / "build.gradle.kts").read_text()
    base_version = re.search(r'versionName\s*=.*?:\s*"([^"]+)"', gradle)
    base_code = re.search(r'versionCode\s*=.*?:\s*(\d+)', gradle)
    if not base_version or not base_code:
        raise SystemExit("Cannot read the Android versionName/versionCode from build.gradle.kts")
    version, version_code = base_version.group(1), int(base_code.group(1))

    output = ROOT / "outputs/debug-candidate"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    artifacts = []
    for build_dir, apk_name, label, min_sdk, filters in VARIANTS:
        source = APP / "build" / build_dir / "outputs/apk/debug" / apk_name
        metadata_path = source.parent / "output-metadata.json"
        if not source.is_file() or not metadata_path.is_file():
            raise SystemExit(f"Missing current build output: {source.relative_to(ROOT)}")
        metadata = json.loads(metadata_path.read_text())
        entries = [entry for entry in metadata.get("elements", [])
                   if entry.get("outputFile") == apk_name]
        if len(entries) != 1:
            raise SystemExit(f"Missing/duplicate output metadata for {apk_name}")
        entry = entries[0]
        if entry.get("filters", []) != filters:
            raise SystemExit(f"ABI output metadata mismatch for {apk_name}")
        if int(entry.get("versionCode", -1)) != version_code:
            raise SystemExit(f"Version code mismatch for {apk_name}")
        built_version = entry.get("versionName", "")
        if not built_version.startswith(version + "-debug"):
            raise SystemExit(f"Not a debug build: {apk_name} ({built_version})")

        badging = run(str(aapt), "dump", "badging", str(source))
        package = re.search(
            r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
            badging, re.M)
        sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
        if not package or package.group(1) != "com.hongguotv.nativeapp.debug":
            raise SystemExit(f"Unexpected application ID in {apk_name}")
        if int(package.group(2)) != version_code or package.group(3) != built_version:
            raise SystemExit(f"APK version metadata mismatch for {apk_name}")
        if not sdk or int(sdk.group(1)) != min_sdk:
            raise SystemExit(f"Unexpected minSdk in {apk_name}")

        signature = run(str(apksigner), "verify", "--verbose",
                        "--min-sdk-version", str(min_sdk), "--print-certs", str(source))
        certs = re.findall(
            r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$",
            signature, re.M)
        if len(certs) != 1 or certs[0].lower() == RELEASE_CERTIFICATE:
            raise SystemExit(f"Debug APK signer is absent or unexpectedly matches production: {apk_name}")

        target_name = f"hongguotv-{version}-debug-{label}.apk"
        target = output / target_name
        shutil.copy2(source, target)
        artifacts.append({
            "name": target_name,
            "size": target.stat().st_size,
            "sha256": sha256(target),
            "applicationId": package.group(1),
            "versionCode": version_code,
            "versionName": built_version,
            "minSdk": min_sdk,
            "abiFilters": [item["value"] for item in filters],
            "signing": "ephemeral Android debug certificate",
        })

    commit = os.environ.get("GITHUB_SHA", "local-build")
    manifest = {
        "repository": "kanonso/hongguoTV",
        "sourceCommit": commit,
        "packageName": "com.hongguotv.nativeapp.debug",
        "versionCode": version_code,
        "baseVersionName": version,
        "isProductionRelease": False,
        "canUpdateProductionApp": False,
        "installAlongsideProductionApp": True,
        "artifacts": artifacts,
    }
    manifest_path = output / "debug-candidate.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    sums = output / "SHA256SUMS"
    sums.write_text("".join(f"{item['sha256']}  {item['name']}\n" for item in artifacts)
                    + f"{sha256(manifest_path)}  {manifest_path.name}\n")

    report = output / "TEST-REPORT.md"
    report.write_text(
        "# hongguoTV Debug 候選建置\n\n"
        f"- Repository: kanonso/hongguoTV\n"
        f"- Source commit: {commit}\n"
        f"- Version: {version} (versionCode {version_code})\n"
        "- Application ID: com.hongguotv.nativeapp.debug\n"
        "- Signer: ephemeral Android debug certificate (not the production key)\n"
        "- Production update: disabled by package-name separation; this is not a Release\n"
        "- All four APK outputs were checked for package ID, version, minSdk, ABI filters, and APK signature.\n\n"
        "## APKs\n\n"
        + "".join(f"- {item['name']} — {item['size']} bytes, SHA-256 {item['sha256']}\n"
                  for item in artifacts)
        + "\n"
        "This build is for parallel installation and real-device testing only. A new Actions run "
        "may have a different temporary debug signing certificate; uninstall the previous "
        ".debug app before installing a candidate from a different run. Do not uninstall the "
        "production app.\n"
    )
    print(report.read_text())


if __name__ == "__main__":
    main()
