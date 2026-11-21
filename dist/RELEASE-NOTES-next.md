Safe for macOS — unreleased changes since 1.1.1. Rename this file to the version number when it ships.

## What's new

- **A new icon:** the 密签 logo, in black chrome, on champagne gold. It replaces Android's blue "Safe" icon and stays easy to spot in the Dock in dark mode. Android and iOS Safe use the same icon.
- **Chinese when macOS is set to Chinese.** The app's own text and the open/save panels follow the system language. What you stored (labels, memos) and what goes into exports is left as it is.
- **One Import keys dialog** replaces the separate private key, cipher, public key, FID and backup dialogs, as in Android Safe 2.5. It tells what you pasted while you type, so a watch-only public key or FID is never mistaken for a key that can sign, and a typo is reported rather than guessed. Backups can be opened from a file, and it says when a backup is encrypted.
- **Input phrase** stretches a phrase with Argon2id. SHA256 stays as an option only to recreate keys made with it.
- **Random keys** offers ten fresh keys at a time and saves the ones you tick.
- **Find a nice FID** generates keys until ten have an FID ending the way you asked. Candidates' private keys are wiped when you leave the screen.
- **Security guidelines** (Tools) carries Android Safe 2.5's text.
