package com.fc.safe.desktop

import data.fcData.FcEntity

/**
 * Address-book entry — a remote party's FID with an optional local
 * label. Mirrors Android's "FID list" (`SafeApplication.getFidList`,
 * `FidListActivity`) but stores in our SqliteDB instead of
 * SharedPreferences.
 *
 * `id` (inherited) is the FID itself, so duplicates collapse on
 * insert and the list is naturally deduplicated.
 */
class DesktopFid : FcEntity() {
    var label: String? = null
    var savedAt: Long = 0
}
