#!/usr/bin/env python3
"""Use tiny real simulator archives to test the production preparation script."""
from pathlib import Path
import shutil, subprocess, tempfile, zipfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as folder:
    fixture = Path(folder)
    (fixture / "scripts").mkdir()
    archives = fixture / "Vendor/adb-prebuilt"
    archives.mkdir(parents=True)
    script = fixture / "scripts/prepare-adb-prebuilt.sh"
    shutil.copyfile(root / "scripts/prepare-adb-prebuilt.sh", script)
    source = fixture / "fixture.c"
    source.write_text("int tvadb_library_fixture(void) { return 7; }\n")
    for arch in ["arm64", "x86_64"]:
        obj = fixture / f"{arch}.o"
        lib = fixture / f"{arch}.a"
        subprocess.run(["xcrun", "--sdk", "iphonesimulator", "clang", "-target", f"{arch}-apple-ios17.0-simulator", "-c", str(source), "-o", str(obj)], check=True)
        subprocess.run(["xcrun", "libtool", "-static", "-o", str(lib), str(obj)], check=True)
        with zipfile.ZipFile(archives / f"libadb-full-iphonesimulator-{arch}.zip", "w") as archive:
            archive.write(lib, "libadb-full.a")
    def prepare(platform, arch, ok=True):
        result = subprocess.run(["bash", str(script), platform, arch], capture_output=True, text=True)
        assert (result.returncode == 0) == ok, result.stderr
    for arch in ["arm64", "x86_64"]:
        prepare("iphonesimulator", arch)
        prepare("iphonesimulator", arch)  # cached path also validates architecture
        output = fixture / f"Vendor/adb-mobile/output/iphonesimulator/{arch}/libadb-full.a"
        subprocess.run(["xcrun", "lipo", str(output), "-verify_arch", arch], check=True)
    prepare("iphoneos", "x86_64", ok=False)
    prepare("macosx", "arm64", ok=False)
    prepare("iphonesimulator", "i386", ok=False)
    # A mislabeled archive must fail before replacing the usable cached file.
    archive = archives / "libadb-full-iphonesimulator-x86_64.zip"
    with zipfile.ZipFile(archive, "w") as target:
        target.write(fixture / "arm64.a", "libadb-full.a")
    output = fixture / "Vendor/adb-mobile/output/iphonesimulator/x86_64/libadb-full.a"
    output.touch()
    import os
    os.utime(output, (1, 1))
    before = output.read_bytes()
    prepare("iphonesimulator", "x86_64", ok=False)
    assert output.read_bytes() == before
print("ADB preparation: both simulator architectures, caching, rejection and atomic replacement passed")
