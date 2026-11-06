"""Exercise release entry points without downloading SDKs or using real keys."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReleaseScriptsTests(unittest.TestCase):
    def test_release_outputs_and_signing_validation(self):
        with tempfile.TemporaryDirectory(prefix='tvadb release ') as directory:
            root = Path(directory)
            shutil.copytree(ROOT / 'scripts', root / 'scripts')
            env = {key: value for key, value in os.environ.items()
                   if not key.startswith('TVADB_')}
            env.update(JAVA_HOME=str(root / 'jdk'), ANDROID_HOME=str(root / 'sdk'))
            for module, entry, name in [
                ('android-client', 'client', 'TVDeck-Android'),
                ('android-onboarding', 'tv', 'TVDeck-Connect'),
            ]:
                project = root / module
                project.mkdir()
                gradle = project / 'gradlew'
                gradle.write_text('''#!/bin/sh
set -eu
[ "$1" = "--no-daemon" ]
case "$2" in testDebugUnitTest|testReleaseUnitTest) ;; *) exit 1 ;; esac
[ "$3" = "assembleRelease" ]
mkdir -p app/build/outputs/apk/release
printf 'test apk' > app/build/outputs/apk/release/app-release-unsigned.apk
''')
                gradle.chmod(0o755)
                script = root / 'scripts' / f'build-android-{entry}-release.sh'
                subprocess.run([str(script)], env=env, check=True, capture_output=True)
                self.assertEqual((root / 'dist' / f'{name}-release-unsigned.apk').read_text(), 'test apk')
                result = subprocess.run([str(script)], env=dict(env, TVADB_KEY_ALIAS='incomplete'),
                                        capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((root / 'dist' / f'{name}-release.apk').exists())
            tools = root / 'sdk/build-tools/35.0.0'
            tools.mkdir(parents=True)
            (tools / 'zipalign').write_text('#!/bin/sh\ncp "$5" "$6"\n')
            (tools / 'apksigner').write_text("""#!/bin/sh
set -eu
if [ "$1" = verify ]; then
    [ -s "$2" ]
    exit
fi
[ "$1" = sign ]
[ "$7" = env:TVADB_STORE_PASSWORD ]
[ "$9" = env:TVADB_KEY_PASSWORD ]
[ "$TVADB_STORE_PASSWORD" = test-only ]
[ "$TVADB_KEY_PASSWORD" = test-only ]
cp "${12}" "${11}"
""")
            for tool in tools.iterdir():
                tool.chmod(0o755)
            (root / 'test key.jks').touch()
            signed_env = dict(env, TVADB_KEYSTORE='test key.jks', TVADB_KEY_ALIAS='test',
                              TVADB_STORE_PASSWORD='test-only', TVADB_KEY_PASSWORD='test-only')
            subprocess.run([str(root / 'scripts/build-android-tv-release.sh')], cwd=root,
                           env=signed_env, check=True, capture_output=True)
            self.assertEqual((root / 'dist/TVDeck-Connect-release.apk').read_text(), 'test apk')
            result = subprocess.run([str(root / 'scripts/build-android-release.sh'), 'invalid'],
                                    env=env, capture_output=True)
            self.assertEqual(result.returncode, 2)


if __name__ == '__main__':
    unittest.main()
