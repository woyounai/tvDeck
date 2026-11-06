# Third-party notices

TVADB application code is distributed under the repository's MIT License.
The embedded ADB static library is built from the
[adb-mobile](https://github.com/wsvn53/adb-mobile) project and includes code
from the Android Open Source Project and its dependencies.

Third-party components retain their original licenses, including:

- Android Open Source Project / ADB — Apache License 2.0.
- BoringSSL — ISC-style and component-specific licenses.
- Protocol Buffers — BSD 3-Clause License.
- Abseil — Apache License 2.0.
- Brotli — MIT License.
- LZ4 — BSD 2-Clause License.
- Zstandard — BSD 3-Clause and GPLv2 dual licensing.
- [QR Code generator](https://www.nayuki.io/page/qr-code-generator-library) — MIT License; Java source in `android-onboarding/app/src/main/java/io/nayuki/qrcodegen/` retains its copyright and license notices.

The complete upstream source and license files are available through the
`Vendor/adb-mobile` Git submodule and its recursively pinned submodules.
TVADB-specific compatibility patches and binary checksums are documented in
[`patches/README.md`](patches/README.md).

## Android phone/tablet client

`android-client/` uses LibADB Android 3.1.1 under its Apache-2.0 option, Spake2-Java 2.2.1 under LGPL-3.0, Conscrypt Android 2.5.3 under Apache-2.0 and component licenses, Bouncy Castle 1.81 under its MIT-style license, and ZXing Core 3.5.3 under Apache-2.0. Source links and license texts are bundled in `android-client/app/src/main/assets/`. The application source and Gradle configuration support rebuilding with modified dependencies.
