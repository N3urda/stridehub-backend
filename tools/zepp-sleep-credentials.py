#!/usr/bin/env python3
"""Install a private server credential file; never print a token or user ID."""

import argparse
import getpass
import importlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-amazfit-project", type=Path, help="Read the already-authorized macOS Keychain connection from this local project")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "storage/zepp/credentials.json")
    args = parser.parse_args()
    try:
        if args.from_amazfit_project:
            project = args.from_amazfit_project.expanduser().resolve()
            if not (project / "amazfit_analysis/core.py").is_file():
                raise ValueError()
            sys.path.insert(0, str(project))
            core = importlib.import_module("amazfit_analysis.core")
            user_id, host = core.load_settings()
            token = core.load_token_from_keychain(user_id)
        else:
            user_id = input("Zepp user ID: ").strip()
            host = input("Zepp API host (api-mifit…zepp.com): ").strip().lower()
            token = getpass.getpass("Zepp app token (hidden): ").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", user_id) or not re.fullmatch(r"api-mifit[a-z0-9-]*\.(?:zepp|huami)\.com", host):
            raise ValueError()
        if not token or len(token) > 4096 or any(ord(c) <= 32 or ord(c) == 127 for c in token):
            raise ValueError()
        target = args.output.expanduser().resolve()
        events_user_id = None
        if target.exists():
            old = json.loads(target.read_text())
            if old.get("userId") != user_id:
                print("Refusing to replace a different Zepp account. Keep each user's health data separate.", file=sys.stderr)
                return 1
            events_user_id = old.get("eventsUserId")
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(target.parent, 0o700)
        fd, temporary = tempfile.mkstemp(prefix=".credentials-", dir=target.parent)
        try:
            with os.fdopen(fd, "w") as handle:
                os.fchmod(handle.fileno(), 0o600)
                credentials = {"userId": user_id, "host": host, "appToken": token}
                if events_user_id is not None:
                    credentials["eventsUserId"] = events_user_id
                json.dump(credentials, handle)
                handle.write("\n")
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        print("Zepp credentials installed privately (0600). Run app:health:sleep:sync --days=90 for the first import.")
        return 0
    except (Exception, KeyboardInterrupt):
        # Native Keychain and cloud errors can include identifiers; keep diagnostics bounded.
        print("Unable to configure Zepp. Check the source login, target permissions, and credential format. No credential was printed.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
