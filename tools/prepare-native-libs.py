"""Fetch pinned dependencies and prepare the native libraries used by Gradle."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from urllib.request import urlopen
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
MAVEN = "https://repo.maven.apache.org/maven2"
ABIS = {"arm64-v8a", "x86_64"}
SHA256 = {
    "libvlc-3.7.7-64bit.aar": "b48dab96e0e90e34cce3861963500c144a5aadb1b249d501d6a4b104d849e61b",
    "ffmpeg-kit-audio-8.1.9-vlc-cxx.aar": "e72e9371031bfad32e4246b677ef8e23e246ccb9bc6342193f9551d9953dd415",
}


def fetch(url, digest, cache):
    filename = url.rsplit("/", 1)[-1]
    destination = cache / filename
    if destination.is_file() and hashlib.sha256(destination.read_bytes()).hexdigest() == digest:
        return destination
    temporary = destination.with_suffix(".download")
    try:
        with urlopen(url, timeout=120) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != digest:
            raise ValueError(f"SHA-256 mismatch for {filename}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def prepare_aar(artifact, cache):
    filename = artifact["file"]
    upstream = fetch(artifact["originalUrl"], SHA256[filename], cache)
    target = ROOT / "app/libs" / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".tmp")
    try:
        with ZipFile(upstream) as incoming, ZipFile(temporary, "w") as outgoing:
            for member in incoming.infolist():
                if member.is_dir():
                    continue
                parts = member.filename.split("/")
                if parts[0] == "jni" and parts[1] not in ABIS:
                    continue
                if filename.startswith("ffmpeg") and member.filename.endswith("/libc++_shared.so"):
                    continue
                outgoing.writestr(member, incoming.read(member))
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def prepare_libarchive(cache):
    provenance = json.loads((ROOT / "third-party/libarchive/provenance.json").read_text(encoding="utf-8"))
    version = provenance["version"]
    url = f"{MAVEN}/me/zhanghai/android/libarchive/library/{version}/library-{version}.aar"
    archive_path = fetch(url, provenance["originalAarSha256"], cache)
    with ZipFile(archive_path) as incoming:
        for abi in sorted(ABIS):
            relative = f"app/src/main/jniLibs/{abi}/libarchive-jni.so"
            expected = next(item["sha256"] for item in provenance["files"] if item["file"] == relative)
            library = incoming.read(f"jni/{abi}/libarchive-jni.so")
            if hashlib.sha256(library).hexdigest() != expected:
                raise ValueError(f"SHA-256 mismatch for {abi} libarchive JNI")
            destination = ROOT / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(library)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / "tools/downloads")
    options = parser.parse_args()
    options.cache.mkdir(parents=True, exist_ok=True)
    artifacts = json.loads((ROOT / "third-party/native-artifacts.json").read_text(encoding="utf-8"))
    for artifact in artifacts:
        prepare_aar(artifact, options.cache)
    prepare_libarchive(options.cache)
    print("Prepared LibVLC, FFmpegKit and libarchive for ARM64 and x86_64.")


if __name__ == "__main__":
    main()
