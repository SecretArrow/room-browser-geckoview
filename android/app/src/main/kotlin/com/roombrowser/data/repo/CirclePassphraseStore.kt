package com.roombrowser.data.repo

import com.roombrowser.domain.model.ProfileId
import com.roombrowser.security.OctCircleKeyCrypto

/**
 * The remembered `oct://` passphrase, one per profile.
 *
 * A sealed circle asks for its passphrase once so the reader does not retype it
 * for every circle; this is where that one answer lives. It is sealed with the
 * profile's own AndroidKeyStore key, so it is unreadable from another profile
 * and from a backup of the database alone. [forget] is the user-facing undo.
 */
interface CirclePassphraseStore {
    suspend fun remember(profileId: ProfileId, passphrase: CharArray)
    suspend fun recall(profileId: ProfileId): CharArray?
    suspend fun forget(profileId: ProfileId)
}

/**
 * [AppStateRepository] implementation — cross-process, so the browser process
 * and a later process death both find the same answer.
 */
class AppStateCirclePassphraseStore(
    private val appState: AppStateRepository
) : CirclePassphraseStore {

    override suspend fun remember(profileId: ProfileId, passphrase: CharArray) {
        appState.saveSealedCirclePassphrase(
            profileId.value,
            OctCircleKeyCrypto.encrypt(profileId, String(passphrase))
        )
    }

    override suspend fun recall(profileId: ProfileId): CharArray? = runCatching {
        appState.sealedCirclePassphrase(profileId.value)
            ?.let { OctCircleKeyCrypto.decrypt(profileId, it) }
            // The decrypted String cannot be wiped; handing back a CharArray at
            // least lets the caller zero its own copy.
            ?.toCharArray()
    }.getOrNull()

    override suspend fun forget(profileId: ProfileId) {
        appState.clearSealedCirclePassphrase(profileId.value)
    }
}
