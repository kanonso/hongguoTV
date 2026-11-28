#!/usr/bin/env bash
set -euo pipefail

: "${OPENHONGGUO_SOURCE_DIR:?Set OPENHONGGUO_SOURCE_DIR to a clean OpenHongguo v0.4.1 checkout}"
: "${OPENHONGGUO_OUT_DIR:?Set OPENHONGGUO_OUT_DIR to the output directory}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the pinned Android NDK}"
command -v gomobile >/dev/null || { echo "gomobile is required" >&2; exit 2; }

SOURCE_DIR="$(cd "$OPENHONGGUO_SOURCE_DIR" && pwd)"
OUT_DIR="$(mkdir -p "$OPENHONGGUO_OUT_DIR" && cd "$OPENHONGGUO_OUT_DIR" && pwd)"
MOBILE_DIR="$SOURCE_DIR/bindings/mobile"
BUILD_SCRIPT="$MOBILE_DIR/build.sh"
AAR="$MOBILE_DIR/android/build/outputs/aar/openhongguo-release.aar"

# Patch only the Android gomobile link invocation in the temporary upstream checkout.
python3 - "$BUILD_SCRIPT" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = '-trimpath -ldflags="-s -w" -o "$out/android/core.aar" .'
new = '-trimpath -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" -o "$out/android/core.aar" .'
if text.count(old) != 1:
    raise SystemExit(f"expected exactly one Android gomobile link invocation, found {text.count(old)}")
path.write_text(text.replace(old, new), encoding="utf-8")
PY

# The upstream script builds the Go mobile core and packages its generated JNI libraries into AAR.
(cd "$MOBILE_DIR" && ./build.sh android)
test -s "$AAR"

# Inspect every JNI library before accepting the rebuilt AAR.
python3 - "$AAR" "$OUT_DIR" "$ANDROID_NDK_HOME" "$SOURCE_DIR" <<'PY'
import hashlib, json, pathlib, shutil, subprocess, sys, tempfile, zipfile
aar = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
ndk = pathlib.Path(sys.argv[3])
source = pathlib.Path(sys.argv[4])
readelf = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
if not readelf.is_file():
    raise SystemExit(f"llvm-readelf not found: {readelf}")

with tempfile.TemporaryDirectory(prefix="openhongguo-aar-") as temp:
    root = pathlib.Path(temp)
    with zipfile.ZipFile(aar) as archive:
        native = [name for name in archive.namelist() if name.startswith("jni/") and name.endswith("/libgojni.so")]
        if not native:
            raise SystemExit("AAR contains no jni/*/libgojni.so")
        alignments = {}
        for name in native:
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
            output = subprocess.check_output([str(readelf), "-lW", str(target)], text=True)
            loads = []
            for line in output.splitlines():
                fields = line.split()
                if fields and fields[0] == "LOAD":
                    try:
                        loads.append(int(fields[-1], 16))
                    except (ValueError, IndexError):
                        raise SystemExit(f"cannot parse PT_LOAD alignment in {name}: {line}")
            if not loads:
                raise SystemExit(f"no PT_LOAD program headers found in {name}")
            alignments[name] = loads
            bad = [value for value in loads if value < 0x4000]
            if bad:
                rendered = ", ".join(hex(value) for value in bad)
                raise SystemExit(f"{name} is not 16KB ELF aligned; PT_LOAD alignments below 0x4000: {rendered}")
    if not any(name.startswith("jni/arm64-v8a/") for name in native):
        raise SystemExit("AAR has no arm64-v8a/libgojni.so")
    source_commit = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    copied = out / "openhongguo-0.4.1-android-16kb.aar"
    shutil.copy2(aar, copied)
    digest = hashlib.sha256(copied.read_bytes()).hexdigest()
    manifest = {
        "upstream": "https://github.com/beilunyang/openhongguo",
        "version": "v0.4.1",
        "sourceCommit": source_commit,
        "ndk": ndk.name,
        "artifact": copied.name,
        "sha256": digest,
        "jniLibraries": alignments,
        "loadAlignmentMinimum": "0x4000",
        "linkerFlags": ["-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384"],
        "note": "Validated rebuild candidate only; hongguoTV Gradle dependency is not switched until this artifact is reviewed and integrated.",
    }
    (out / "openhongguo-16kb-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "SHA256SUMS").write_text(f"{digest}  {copied.name}\n", encoding="ascii")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
PY

echo "Verified 16KB-aligned OpenHongguo AAR candidate: $OUT_DIR"
