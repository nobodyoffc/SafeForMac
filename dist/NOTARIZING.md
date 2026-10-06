# Notarizing the DMG (when you want to)

Notarization is what removes the first-launch Gatekeeper prompt described in
`INSTALL.md`. Worth knowing up front:

- It needs **your** Apple ID (or an App Store Connect API key) **once, on the
  build machine, with an internet connection**. The people installing the app
  still never need an Apple ID.
- The current build already satisfies every prerequisite — Developer ID
  signature, hardened runtime (`flags=0x10000(runtime)`), secure timestamp — so
  there is nothing to rebuild.
- After stapling, the ticket travels inside the DMG. Recipients are verified
  **offline**; their Macs do not phone Apple.

## One-time: store the credential

Run this yourself (`!` prefix in Claude Code runs it in the session) so the
password never lands in a transcript or a build script. Generate an
app-specific password at <https://account.apple.com> → Sign-In and Security →
App-Specific Passwords.

```sh
xcrun notarytool store-credentials "safe-notary" \
  --apple-id "you@example.com" \
  --team-id 5768V787GP \
  --password "xxxx-xxxx-xxxx-xxxx"
```

The credential goes into the login keychain under the profile name
`safe-notary`; later commands reference the profile, not the secret.

## Each release

```sh
# 1. Build (signs the .app with Developer ID + hardened runtime)
./gradlew :app-desktop:packageDmg

DMG=app-desktop/build/compose/binaries/main/dmg/Safe-1.0.1.dmg   # matches appVersion

# 2. Submit and wait — a few minutes, mostly upload
xcrun notarytool submit "$DMG" --keychain-profile "safe-notary" --wait

# 3. Attach the ticket to the DMG so it verifies offline
xcrun stapler staple "$DMG"

# 4. Confirm
xcrun stapler validate "$DMG"
spctl -a -vvv -t open --context context:primary-signature "$DMG"
```

`spctl` should report `accepted` and `source=Notarized Developer ID`.

Staple the DMG, not just the app inside it — that is what makes a fresh
download open cleanly on a machine that has never seen the app.

## If notarization is rejected

```sh
xcrun notarytool log <submission-id> --keychain-profile "safe-notary"
```

The usual causes are a missing hardened runtime or an unsigned nested binary.
Neither applies to the current build, but the JVM and Skia dylibs bundled in
`Safe.app/Contents/app/` are the place to look if that changes.

## Using an API key instead

If you would rather not use an app-specific password, `store-credentials`
accepts an App Store Connect key:

```sh
xcrun notarytool store-credentials "safe-notary" \
  --key ~/private_keys/AuthKey_XXXXXXXXXX.p8 \
  --key-id XXXXXXXXXX \
  --issuer xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

Everything after that is identical.

## Note on `build.gradle.kts`

`app-desktop/build.gradle.kts` already declares a `notarization` block reading
`APPLE_ID` and `APPLE_APP_SPECIFIC_PASSWORD` from the environment, for the
Gradle `notarizeDmg` task. The `notarytool` route above is independent of it
and does not need those variables set.
