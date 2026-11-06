"""Exercise workflow version validation and draft creation without network access."""
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


if __name__ == '__main__':
    unittest.main()
