package com.fc.safe.desktop

import data.fcData.FcEntity

/**
 * Placeholder FcEntity used to seed each wallet's encrypted main DB during
 * unlock. The unlock flow opens (or creates) a `vault` DB keyed to the
 * user's password — that's what forces Argon2 KDF to run and the validator
 * row to be decrypted, which is how we actually authenticate the password.
 *
 * Real entity types (KeyInfo, Cash, Secret, Multisig) replace this as their
 * managers are ported in Phase 2.
 */
class DesktopVault : FcEntity() {
    var createdAt: Long = 0
}
