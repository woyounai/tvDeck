"""Exercise workflow version validation and draft creation without network access."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def workflow_step(source, name):
    section = source.split('- name: ' + name + '\n', 1)[1]
    match = re.search(r'(?m)^        run: \|\n((?:          [^\n]*\n|\n)+)', section)
    return '\n'.join(line[10:] for line in match.group(1).splitlines())


class ReleaseWorkflowTests(unittest.TestCase):
    def test_versions_and_draft_assets(self):
        for filename, tag, apk in [
            ('android-release.yml', 'android-v0.1.1', 'TVDeck-Android-release.apk'),
            ('android-tv-release.yml', 'tv-v0.1.1', 'TVDeck-Connect-release.apk'),
        ]:
            source = (ROOT / '.github/workflows' / filename).read_text()
            validate = workflow_step(source, 'Validate version')
            env = dict(os.environ, TVADB_VERSION_NAME='0.1.1', TVADB_VERSION_CODE='2')
            subprocess.run(['bash', '-c', validate], env=env, check=True, capture_output=True)
            for version, code in [('0.1.1-beta.1', '2100000000'), ('1.0.0', '1')]:
                subprocess.run(['bash', '-c', validate],
                               env=dict(env, TVADB_VERSION_NAME=version, TVADB_VERSION_CODE=code),
                               check=True, capture_output=True)
            for version, code in [('$(exit 0)', '2'), ('v0.1.1', '2'), ('0.1.1', '0'),
                                  ('0.1.1', '02'), ('0.1.1', '2100000001'),
                                  ('0.1.1', '9' * 100), ('0.1.1', '-1'), ('0.1.1', '1+1')]:
                result = subprocess.run(['bash', '-c', validate],
                                        env=dict(env, TVADB_VERSION_NAME=version, TVADB_VERSION_CODE=code),
                                        capture_output=True)
                self.assertNotEqual(result.returncode, 0, (filename, version, code))
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                gh = root / 'gh'
                gh.write_text('#!' + sys.executable + '\nimport json, os, sys\n'
                              'with open(os.environ["ARGV_FILE"], "w") as f: json.dump(sys.argv[1:], f)\n')
                gh.chmod(0o755)
                env.update(PATH=str(root) + os.pathsep + env['PATH'], ARGV_FILE=str(root / 'argv.json'),
                           GITHUB_SHA='build-source-sha', GH_REPO='owner/project')
                subprocess.run(['bash', '-c', workflow_step(source, 'Create Release draft')],
                               env=env, cwd=root, check=True, capture_output=True)
                args = json.loads((root / 'argv.json').read_text())
                self.assertEqual(args[:3], ['release', 'create', tag])
                self.assertIn('--draft', args)
                self.assertEqual(args[args.index('--target') + 1], 'build-source-sha')
                self.assertIn('dist/' + apk, args)
                self.assertIn('dist/SHA256SUMS', args)
                self.assertNotIn('--clobber', args)

    def test_combined_versions_and_build_failure_cleanup(self):
        source = (ROOT / '.github/workflows/android-combined-release.yml').read_text()
        env = dict(os.environ, RELEASE_VERSION='0.2.0', CLIENT_VERSION='0.2.1',
                   CLIENT_VERSION_CODE='3', TV_VERSION='1.1.0', TV_VERSION_CODE='7')
        validate = workflow_step(source, 'Validate versions')
        subprocess.run(['bash', '-c', validate], env=env, check=True, capture_output=True)
        for key, invalid in [('RELEASE_VERSION', '$(exit 0)'), ('CLIENT_VERSION', 'v1.0.0'),
                             ('TV_VERSION', ''), ('CLIENT_VERSION_CODE', '0'),
                             ('TV_VERSION_CODE', '2100000001')]:
            result = subprocess.run(['bash', '-c', validate], env=dict(env, **{key: invalid}),
                                    capture_output=True)
            self.assertNotEqual(result.returncode, 0, key)
        for failure in ['', 'client', 'tv']:
            with tempfile.TemporaryDirectory(prefix='combined release ') as directory:
                root = Path(directory)
                (root / 'scripts').mkdir()
                for target, apk in [('client', 'TVDeck-Android'), ('tv', 'TVDeck-Connect')]:
                    script = root / 'scripts' / ('build-android-' + target + '-release.sh')
                    script.write_text('#!' + sys.executable + '\n' +
                        'import os, sys\nfrom pathlib import Path\n' +
                        'assert Path(os.environ["TVADB_KEYSTORE"]).read_bytes() == b"fake-key"\n' +
                        'if os.environ.get("FAIL_TARGET") == ' + repr(target) + ': sys.exit(9)\n' +
                        'Path("dist").mkdir(exist_ok=True)\n' +
                        'Path(' + repr('dist/' + apk + '-release.apk') + ').write_text(' +
                        'os.environ["TVADB_VERSION_NAME"] + ":" + os.environ["TVADB_VERSION_CODE"])\n')
                    script.chmod(0o755)
                gh = root / 'gh'
                gh.write_text('#!' + sys.executable + '\nimport json, sys\n' +
                              'with open("argv.json", "w") as f: json.dump(sys.argv[1:], f)\n')
                gh.chmod(0o755)
                run_env = dict(env, PATH=str(root) + os.pathsep + env['PATH'], FAIL_TARGET=failure,
                               TVADB_KEYSTORE=str(root / 'release.jks'), TVADB_KEY_ALIAS='test',
                               TVADB_STORE_PASSWORD='test-only', TVADB_KEY_PASSWORD='test-only',
                               ANDROID_RELEASE_KEYSTORE_BASE64=base64.b64encode(b'fake-key').decode(),
                               GITHUB_SHA='source-sha', GH_REPO='owner/project')
                result = subprocess.run(['bash', '-c', workflow_step(source, 'Build and sign both APKs')],
                                        env=run_env, cwd=root, capture_output=True)
                self.assertFalse((root / 'release.jks').exists(), 'Signing material must be removed')
                if failure:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse((root / 'dist/SHA256SUMS').exists())
                    self.assertFalse((root / 'argv.json').exists())
                    continue
                self.assertEqual(result.returncode, 0, result.stderr)
                expected = {'TVDeck-Android-release.apk': b'0.2.1:3',
                            'TVDeck-Connect-release.apk': b'1.1.0:7'}
                sums = (root / 'dist/SHA256SUMS').read_text().splitlines()
                self.assertEqual(len(sums), 2)
                for name, content in expected.items():
                    self.assertEqual((root / 'dist' / name).read_bytes(), content)
                    self.assertIn(hashlib.sha256(content).hexdigest() + '  ' + name, sums)
                subprocess.run(['bash', '-c', workflow_step(source, 'Create Release draft')],
                               env=run_env, cwd=root, check=True, capture_output=True)
                args = json.loads((root / 'argv.json').read_text())
                self.assertEqual(args[:3], ['release', 'create', 'v0.2.0'])
                self.assertEqual(args[args.index('--target') + 1], 'source-sha')
                self.assertIn('--draft', args)
                for name in expected:
                    self.assertIn('dist/' + name, args)
                self.assertIn('dist/SHA256SUMS', args)


if __name__ == '__main__':
    unittest.main()
