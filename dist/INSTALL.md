# Installing Safe on another Mac

`Safe-1.0.1.dmg` — 173 MB
SHA-256: `4f78d953f27de23567821f865601f877319aeefe1423d31c89bbebebcecb6322`

Verify the download before installing:

```sh
shasum -a 256 Safe-1.0.1.dmg
```

## Requirements

- **Apple Silicon Mac** (M1 / M2 / M3 / M4). This build is arm64-only and will
  not launch on an Intel Mac — Rosetta cannot help, it translates the other
  direction.
- macOS 10.13 or newer.
- No Apple ID, no internet, no account of any kind is needed to install or run
  it. The Java runtime is bundled inside the app.

## Install

1. Open the DMG and drag **Safe** into **Applications**.
2. Eject the DMG.

## How you hand it over decides whether there's a prompt at all

The Gatekeeper warning below is triggered by the `com.apple.quarantine`
attribute, which some transfer methods attach and others don't. It is not a
property of the DMG — the same file produces different experiences depending on
how it travelled:

| Transfer | Quarantine | First launch |
|---|---|---|
| USB stick / external drive | no | opens normally, no prompt |
| Local file share (SMB/AFP), `scp`, `rsync` | no | opens normally, no prompt |
| Browser download, AirDrop, email, Messages | yes | one-time prompt, see below |

For an air-gapped machine you are almost certainly carrying this over on a USB
stick, in which case there is nothing to work around — no Apple ID, no
notarization, no override. Copy `Safe.app` to Applications and open it.

The recipient can confirm which case they're in:

```sh
xattr -l /Applications/Safe.app | grep quarantine
```

No output means no prompt is coming.

## First launch: getting past Gatekeeper

The app is signed with a Developer ID certificate (team `5768V787GP`) but is
**not notarized**, so the first launch on each Mac is blocked with:

> "Safe" cannot be opened because Apple cannot check it for malicious software.

Only applies if the file arrived with a quarantine attribute (see the table
above). One-time step per machine. Pick whichever applies:

### macOS 15 (Sequoia) and newer

Control-clicking no longer works on these versions.

1. Double-click **Safe** in Applications. Dismiss the warning.
2. Open **System Settings → Privacy & Security**.
3. Scroll to the Security section — it will say *"Safe" was blocked to protect
   your Mac*. Click **Open Anyway**.
4. Authenticate, then launch Safe again and click **Open**.

### macOS 14 (Sonoma) and older

1. **Control-click** (right-click) **Safe** in Applications → **Open**.
2. Click **Open** in the dialog.

### Any version — Terminal, one command

After copying the app to Applications:

```sh
xattr -dr com.apple.quarantine /Applications/Safe.app
```

Then open it normally. This strips the quarantine flag the browser or AirDrop
attached to the download; it does not alter the app or its signature.

## Verifying the app is intact

The signature can be checked on the receiving Mac without an Apple ID and
without a network:

```sh
codesign --verify --deep --strict --verbose=2 /Applications/Safe.app
codesign -dv --verbose=2 /Applications/Safe.app 2>&1 | grep Authority
```

Expected:

```
/Applications/Safe.app: valid on disk
/Applications/Safe.app: satisfies its Designated Requirement
Authority=Developer ID Application: CHANGYONG LIU (5768V787GP)
Authority=Developer ID Certification Authority
Authority=Apple Root CA
```

If that passes, the app is exactly what was signed on the build machine — the
Gatekeeper warning is only about the missing notarization ticket, not about
tampering.

## Removing the warning for good

Notarizing this same build removes the first-launch step for every recipient.
It does not require rebuilding — the app is already signed with a hardened
runtime and a secure timestamp, which is everything notarization needs. It
takes one credential-storing command on the build machine, then a submit and
staple. See `NOTARIZING.md` when you want to do it.
