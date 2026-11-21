Safe for macOS — the offline Safe wallet (keys, secrets, TOTP, multisig, transactions) on the Mac. This release catches up with Android Safe 2.4.

## What's new

- **The vault is opened by a data key, not by your password.** Each wallet has a random 32-byte data key, wrapped under your password with Argon2id. Nothing derived from your password is stored on disk any more (earlier builds named the wallet folder after a SHA-256 hash of the password, which let someone with a copy of the folder test guesses cheaply). Same model as Android Safe 2.4.
- **Change password** (Tools → Change password). Only the wrapped key changes; your records are untouched.
- **Screenshots and screen recordings don't capture Safe's windows**, like Android's FLAG_SECURE.
- **Reads everything Android Safe 2.4 writes**: type-4 password bundles, ChaCha20 / X25519 ciphers, Bitcore ciphers, and 2.4's key and secret backups. Checked against the shared FTSP test vectors.
- Faster key use: signing no longer runs Argon2id for every key.
- The Android app's icon.
- Logs go to `~/Library/Logs/com.fc.safe/safe.log` again, with secrets redacted (packaged 1.0.x builds didn't write them).

## Before you upgrade

- **The first unlock after upgrading moves your wallet to its new key.** That can take up to a minute. Every record is copied, re-encrypted and checked before the old files are removed; if anything fails, the wallet stays as it was and still opens, and the move is retried on the next unlock.
- **After upgrading, don't go back to 1.0.x**: it can't open a wallet that has moved. Your data is still there, and 1.1.0 opens it again.
- **A wallet that 1.0.x said "Wrong password" for may open now.** Earlier builds could lose a newly created wallet from their wallet list, so that its correct password was refused; 1.1.0 finds such a wallet by its files and restores it.
- Export a backup first if you'd like a copy outside the vault (My Keys / Secrets → Export).

## Install

Apple Silicon Macs (M1 or later), macOS 11 or later. Open the DMG and drag Safe to Applications. No Apple ID, account or internet connection is needed to install or run it.

The app is signed with Developer ID (team 5768V787GP). **Apple's notarization of this build is still pending**, so the first launch of a downloaded copy is blocked once:

- **macOS 15 or later:** open Safe, dismiss the warning, then System Settings → Privacy & Security → **Open Anyway**.
- **macOS 14 or earlier:** Control-click Safe in Applications → **Open** → **Open**.
- **Or in Terminal:** `xattr -dr com.apple.quarantine /Applications/Safe.app`

When Apple approves the build, the DMG here will be replaced by the notarized one (same app, with Apple's ticket attached) and this section updated.

To check the signature:
```
codesign --verify --deep --strict --verbose=2 /Applications/Safe.app
```
should report `valid on disk` and `satisfies its Designated Requirement`.

## Verify the DMG

```
shasum -a 256 Safe-1.1.0.dmg
```
SHA-256: `bede92522fd5428ea66f96a660e6663a5cc19e5195b263f9eb04346a850716e6`

