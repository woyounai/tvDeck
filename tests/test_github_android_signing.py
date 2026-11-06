import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/configure-github-android-signing.py'
spec = importlib.util.spec_from_file_location('signing', SCRIPT)
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class SigningUploadTests(unittest.TestCase):
    def test_secret_values_use_stdin_and_explicit_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            key = root / 'release.jks'
            key.write_bytes(b'fake-key')
            config = root / 'signing.json'
            config.write_text(json.dumps(dict(keystore=str(key), alias='release',
                                              store_password='test-password', key_password='test-password')))
            with patch('sys.argv', [str(SCRIPT), '--repo', 'owner/project', '--credentials', str(config)]), \
                    patch.object(signing.subprocess, 'run') as run, patch('builtins.print'):
                signing.main()
            self.assertEqual(run.call_count, 4)
            for call in run.call_args_list:
                command = call.args[0]
                self.assertEqual(command[-2:], ['--repo', 'owner/project'])
                self.assertNotIn('test-password', command)
                self.assertTrue(call.kwargs['input'])
                self.assertTrue(call.kwargs['check'])


if __name__ == '__main__':
    unittest.main()
