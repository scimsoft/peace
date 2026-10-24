# Peace owner tool

Offline helper for the product owner (Gerrit) to authorize early unlocks.

```bash
cd owner-tool
python3 -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## 1. Generate a keypair (once)

```bash
python3 peace_owner.py keygen
```

- Writes `keys/private.pem` (**secret — gitignored**) and `keys/public.pem`.
- Prints `OWNER_PUBLIC_KEY_X509_HEX=...` — paste into  
  `app/.../OwnerUnlockConfig.kt`.
- Rebuild/reinstall the app after pasting.

## 2. When a locked user calls

1. User opens **Owner unlock** → **Challenge–response**, reads the challenge aloud.
2. You run:

```bash
python3 peace_owner.py respond 'XXXX-XXXX-XXXX-…'
```

3. Read the printed **response** code back (or have them paste it).  
   The app verifies the ECDSA signature with the embedded public key and unlocks.  
   Challenges expire in ~10 minutes and are single-use.

## 3. Optional master password

```bash
python3 peace_owner.py password-hash
```

Paste `MASTER_PASSWORD_SALT_HEX` / `MASTER_PASSWORD_HASH_HEX` into `OwnerUnlockConfig.kt`.  
Use a long random password; the hash can be offline-attacked if extracted from the APK.

## Commands

| Command | Purpose |
|---|---|
| `keygen` | Create EC P-256 keypair |
| `respond [challenge]` | Sign challenge → response code |
| `password-hash` | PBKDF2-HMAC-SHA256 salt+hash (600k iters) |
| `public-hex` | Reprint public key hex |
