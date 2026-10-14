#!/usr/bin/env python3
"""Resume the prepared local project and open its department or citizen interface."""
import argparse
import subprocess
import sys
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--citizen', action='store_true', help='Open HTTPS citizen and issuer sign-in')
    parser.add_argument('--menu', action='store_true', help='Choose the interface interactively')
    parser.add_argument('--no-open', action='store_true', help='Check and resume services without opening a browser')
    args = parser.parse_args()
    citizen = args.citizen
    if args.menu:
        print('GovDoc local project\n1. Department demo\n2. Citizen and issuer sign-in\n')
        choice = input('Choose 1 or 2: ').strip()
        if choice not in {'1', '2'}:
            raise SystemExit('No changes made. Run again and choose 1 or 2.')
        citizen = choice == '2'
    command = [sys.executable, str(ROOT / 'scripts/resume-demo.py')]
    if citizen:
        command.append('--citizen')
    subprocess.run(command, cwd=ROOT, check=True)
    if not args.no_open:
        if citizen:
            subprocess.run([sys.executable, str(ROOT / 'scripts/open-citizen-browser.py')], cwd=ROOT, check=True)
        elif not webbrowser.open('http://127.0.0.1:18081/'):
            print('Open http://127.0.0.1:18081/ in your browser.')
    if citizen:
        print('Sign in through the identity provider. The citizen reference appears after citizen sign-in.')
    else:
        print('Choose Development account. Use issuer for issuance or reader for read-only access.')
    print('Passwords stay in the private runtime credentials files; this launcher never prints them.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, subprocess.SubprocessError):
        raise SystemExit('The project could not be opened. Start Docker and check the prepared services; no data was reset.')
    except (EOFError, KeyboardInterrupt):
        raise SystemExit('Launcher cancelled.')
