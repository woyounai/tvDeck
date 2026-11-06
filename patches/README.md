# Embedded ADB build patches

TVADB links a prebuilt `libadb-full.a` for iPhoneOS arm64 and Apple Silicon
Simulator arm64, plus Intel Simulator x86_64. The archives are stored in `Vendor/adb-prebuilt` so a normal
GitHub clone can build without downloading files larger than GitHub's 100 MB
per-file limit.

The static libraries were built from `wsvn53/adb-mobile` commit
`8a1d2136d74535e651b591d09ab87dc9f7ef2ec4` and its pinned submodules. The
patches in this directory record the local iOS compatibility changes used by
the shipped binaries:

- `adb-mobile-porting.patch`: adb-mobile build scripts and output fixes.
- `aosp-adb-ios.patch`: embedded AOSP ADB client portability changes.
- `aosp-libbase-ios.patch`: Android libbase portability change.
- `protobuf-ios.patch`: iOS protobuf build configuration.
- `abseil-ios.patch`: Apple Clang compatibility configuration.

Prebuilt archive SHA-256:

```text
addee382f85a4a40b3af4f10c2b5f4ce65082a7114f896bd72ba25e70f1ef247  libadb-full-iphoneos-arm64.zip
0e809d784c5354c700d7151ee538281812b9de912b8550efd1ca1f0f07901f90  libadb-full-iphonesimulator-arm64.zip
```

The TVADB application source is MIT licensed. Embedded AOSP, BoringSSL,
protobuf, Abseil, Brotli, LZ4, Zstandard, and other third-party components
retain their respective upstream licenses.

## Intel simulator addition

The x86_64 simulator archive uses the same pinned adb-mobile source and iOS
porting changes as the arm64 archives (Xcode 26.2, iPhoneSimulator 26.2 SDK,
protobuf/protoc 28.3). It is an iOS Simulator Mach-O library, not a macOS build.
Build dependencies include CMake, pkg-config and the matching host protoc.
The existing porting/scripts/make-{lz4,zstd,brotli,protobuf,adb}.sh scripts accept
TARGET=<library>/iphonesimulator/x86_64 and OUTPUT=../output from porting/.
The porting patch requires SDK-specific pkg-config files for Brotli/LZ4/Zstd;
these must point at the libraries in output/iphonesimulator/x86_64, never host
Homebrew libraries. Merge the resulting archives with Apple's static libtool.

The app's preparation phase iterates ARCHS, validates the extracted archive's
architecture, and prepares each platform/architecture directory independently.
Use scripts/run-simulator.sh for the native host architecture. The real Mach-O
fixture test is tests/test_adb_prebuilt.py. The repeatable runtime check is tests/run-adb-simulator-smoke.sh <booted-UDID>.
It calls the production ADB version API without connecting to a television.

SHA-256:

```text
d73e4f468f890691d320f991403edf4279042ddf988d249c7c7619366eea3b47  libadb-full-iphonesimulator-x86_64.zip
```

Intel iOS 26.2 simulator execution returned ADB 1.0.41 / 35.0.2.

Validation: the complete TVADB app and Widget extension built for both simulator
architectures; the app installed and launched on the Intel iOS 26.2 simulator.
The original device/simulator arm64 archive checksums remain unchanged.
