#!/usr/bin/env python3
"""Upload local Android release credentials to explicitly selected GitHub repo secrets."""
import argparse
import base64
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True, help='GitHub owner/repository receiving the signing secrets')
    parser.add_argument('--credentials', type=Path,
                        default=Path.home() / '.android/tvadb-release/signing.json')
    args = parser.parse_args()
    data = json.loads(args.credentials.read_text())
    values = {
        'ANDROID_RELEASE_KEYSTORE_BASE64': base64.b64encode(Path(data['keystore']).read_bytes()),
        'ANDROID_RELEASE_KEY_ALIAS': data['alias'].encode(),
        'ANDROID_RELEASE_STORE_PASSWORD': data['store_password'].encode(),
        'ANDROID_RELEASE_KEY_PASSWORD': data['key_password'].encode(),
    }
    if not all(values.values()):
        raise SystemExit('Signing fields must not be empty')
    for name, value in values.items():
        subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repo], input=value, check=True)
        print(f'Configured {name}')


if __name__ == '__main__':
    main()
