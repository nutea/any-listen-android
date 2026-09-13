#!/usr/bin/env python3
"""Create a gitignored local upload keystore if one is missing. Never prints secrets."""

from __future__ import annotations

import os
import secrets
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROPS = ROOT / "keystore.properties"
STORE = ROOT / "secrets" / "upload.jks"
ALIAS = "upload"


def main() -> int:
    if PROPS.exists() and STORE.exists():
        print("Local keystore already present (gitignored).")
        return 0
    java_home = os.environ.get("JAVA_HOME")
    keytool = str(Path(java_home) / "bin" / "keytool") if java_home else "keytool"
    password = secrets.token_urlsafe(24)
    STORE.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        keytool,
        "-genkeypair",
        "-keystore",
        str(STORE),
        "-alias",
        ALIAS,
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "10000",
        "-storepass",
        password,
        "-keypass",
        password,
        "-dname",
        "CN=Any Listen Upload, OU=Private, O=nutea, L=Local, ST=Local, C=CN",
    ]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    except FileNotFoundError:
        print("keytool not found. Set JAVA_HOME to JDK 17.", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as error:
        print(error.stderr or "keytool failed", file=sys.stderr)
        return 1
    PROPS.write_text(
        "\n".join(
            [
                "storeFile=secrets/upload.jks",
                f"storePassword={password}",
                f"keyAlias={ALIAS}",
                f"keyPassword={password}",
                "",
            ]
        ),
        encoding="utf-8",
    )
    print("Wrote gitignored keystore.properties and secrets/upload.jks. Back them up privately.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
