#!/usr/bin/env python3
"""
Peace owner-side tool — keygen, challenge response, master-password hash.

Requires: pip install cryptography

SECURITY
  - Keep owner-tool/keys/private.pem secret. Never commit it. Never put it on a phone.
  - Only the PUBLIC key (and password salt+hash) go into the Android app.
  - Challenge responses are ECDSA P-256 signatures over the challenge payload.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import os
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import (
        decode_dss_signature,
        encode_dss_signature,
    )
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
except ImportError:
    print("Missing dependency. Install with:\n  pip install cryptography", file=sys.stderr)
    sys.exit(1)

ROOT = Path(__file__).resolve().parent
KEYS_DIR = ROOT / "keys"
PRIVATE_PEM = KEYS_DIR / "private.pem"
PUBLIC_PEM = KEYS_DIR / "public.pem"

PBKDF2_ITERATIONS = 600_000  # must match OwnerUnlockConfig.PBKDF2_ITERATIONS
CHALLENGE_TTL_MS = 10 * 60 * 1000

CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def b32_encode(data: bytes, group_every: int = 4) -> str:
    if not data:
        return ""
    buffer = 0
    bits_left = 0
    out = []
    for b in data:
        buffer = (buffer << 8) | b
        bits_left += 8
        while bits_left >= 5:
            index = (buffer >> (bits_left - 5)) & 0x1F
            bits_left -= 5
            out.append(CROCKFORD[index])
    if bits_left > 0:
        index = (buffer << (5 - bits_left)) & 0x1F
        out.append(CROCKFORD[index])
    raw = "".join(out)
    if group_every <= 0:
        return raw
    return "-".join(raw[i : i + group_every] for i in range(0, len(raw), group_every))


def b32_decode(grouped: str) -> bytes:
    filtered = []
    for ch in grouped.upper():
        if ch in "- \t\n\r":
            continue
        if ch == "O":
            ch = "0"
        elif ch in ("I", "L"):
            ch = "1"
        elif ch == "U":
            continue
        if ch not in CROCKFORD:
            raise ValueError(f"Invalid Base32 char: {ch}")
        filtered.append(ch)
    buffer = 0
    bits_left = 0
    out = bytearray()
    for ch in filtered:
        value = CROCKFORD.index(ch)
        buffer = (buffer << 5) | value
        bits_left += 5
        if bits_left >= 8:
            out.append((buffer >> (bits_left - 8)) & 0xFF)
            bits_left -= 8
    return bytes(out)


def cmd_keygen(_: argparse.Namespace) -> int:
    KEYS_DIR.mkdir(parents=True, exist_ok=True)
    if PRIVATE_PEM.exists():
        print(f"Refusing to overwrite existing {PRIVATE_PEM}", file=sys.stderr)
        print("Delete it first if you really want a new keypair.", file=sys.stderr)
        return 1

    private_key = ec.generate_private_key(ec.SECP256R1())
    public_key = private_key.public_key()

    PRIVATE_PEM.write_bytes(
        private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    os.chmod(PRIVATE_PEM, 0o600)

    PUBLIC_PEM.write_bytes(
        public_key.public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )

    der = public_key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    hex_key = der.hex()

    print("Keypair written:")
    print(f"  private: {PRIVATE_PEM}  (KEEP SECRET — do not commit)")
    print(f"  public:  {PUBLIC_PEM}")
    print()
    print("Paste this into OwnerUnlockConfig.kt → OWNER_PUBLIC_KEY_X509_HEX:")
    print()
    print(f'    const val OWNER_PUBLIC_KEY_X509_HEX: String = "{hex_key}"')
    print()
    return 0


def load_private_key():
    if not PRIVATE_PEM.exists():
        raise SystemExit(
            f"Missing {PRIVATE_PEM}. Run:\n  python3 owner-tool/peace_owner.py keygen"
        )
    return serialization.load_pem_private_key(PRIVATE_PEM.read_bytes(), password=None)


def cmd_respond(args: argparse.Namespace) -> int:
    private_key = load_private_key()
    challenge = args.challenge
    if not challenge:
        challenge = input("Challenge code (from phone): ").strip()
    try:
        payload = b32_decode(challenge)
    except ValueError as e:
        print(f"Bad challenge: {e}", file=sys.stderr)
        return 1

    if len(payload) != 25 or payload[0] != 1:
        print(
            f"Unexpected challenge payload (len={len(payload)}, version={payload[0] if payload else '?'}). "
            "Expected version=1, 25 bytes.",
            file=sys.stderr,
        )
        return 1

    ts = int.from_bytes(payload[1:9], "big")
    import time

    now_ms = int(time.time() * 1000)
    age = now_ms - ts
    if age > CHALLENGE_TTL_MS + 60_000:
        print(
            f"Warning: challenge timestamp is {age // 1000}s old "
            f"(TTL is {CHALLENGE_TTL_MS // 1000}s). App may reject it.",
            file=sys.stderr,
        )

    # Sign the raw payload bytes (same bytes the app verifies).
    der_sig = private_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der_sig)
    raw = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    response = b32_encode(raw, group_every=4)

    print()
    print("Response code (read back to the user / have them paste into the app):")
    print()
    print(response)
    print()
    print(f"({len(raw)} raw bytes → Crockford Base32, single-use, ~10 min window)")
    return 0


def cmd_password_hash(_: argparse.Namespace) -> int:
    password = getpass.getpass("Master password: ")
    confirm = getpass.getpass("Confirm: ")
    if password != confirm:
        print("Passwords do not match.", file=sys.stderr)
        return 1
    if len(password) < 12:
        print("Warning: short password; prefer 16+ random characters.", file=sys.stderr)

    salt = os.urandom(16)
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        iterations=PBKDF2_ITERATIONS,
    )
    digest = kdf.derive(password.encode("utf-8"))

    print()
    print("Paste into OwnerUnlockConfig.kt:")
    print()
    print(f'    const val MASTER_PASSWORD_SALT_HEX: String = "{salt.hex()}"')
    print(f'    const val MASTER_PASSWORD_HASH_HEX: String = "{digest.hex()}"')
    print(f"    // PBKDF2_ITERATIONS = {PBKDF2_ITERATIONS}")
    print()
    print("Use a strong unique password. The hash is extractable from the APK.")
    return 0


def cmd_public_hex(_: argparse.Namespace) -> int:
    if not PUBLIC_PEM.exists():
        # derive from private if needed
        private_key = load_private_key()
        public_key = private_key.public_key()
    else:
        public_key = serialization.load_pem_public_key(PUBLIC_PEM.read_bytes())
    der = public_key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    print(der.hex())
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Peace owner unlock tool (ECDSA P-256 + PBKDF2 helpers)"
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_keygen = sub.add_parser("keygen", help="Generate EC P-256 keypair")
    p_keygen.set_defaults(func=cmd_keygen)

    p_respond = sub.add_parser(
        "respond", help="Sign a challenge from a locked phone → response code"
    )
    p_respond.add_argument(
        "challenge",
        nargs="?",
        help="Challenge code (Crockford Base32). If omitted, prompted.",
    )
    p_respond.set_defaults(func=cmd_respond)

    p_pw = sub.add_parser("password-hash", help="Derive PBKDF2 salt+hash for the app")
    p_pw.set_defaults(func=cmd_password_hash)

    p_pub = sub.add_parser("public-hex", help="Print X.509 public key as hex")
    p_pub.set_defaults(func=cmd_public_hex)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
