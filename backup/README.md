# Signing-key backup

`room-browser-signing-backup.tar.gz.gpg` is an **encrypted** copy of the
release signing keystore (`room-browser-release.jks`), its store/key passwords,
and the key alias. It is produced by
[`.github/workflows/backup-signing-key.yml`](../.github/workflows/backup-signing-key.yml),
which is manual-only (`workflow_dispatch`).

## Why this file is in a public repository

The repository is public, and a signing keystore is the one secret that must
not be published in the clear: whoever holds it can sign an APK that Android
will accept as a legitimate update to an already-installed copy of the app.
That is also not undoable — git history, forks and crawlers keep it.

What is committed here is **ciphertext only**. The archive is sealed with
AES-256 (gpg symmetric, SHA-512 S2K) under `BACKUP_PASSPHRASE`, so this file
cannot sign anything on its own. No plaintext keystore, password, or
`keystore.properties` is ever written into the tree — the workflow uploads and
commits the `.gpg` and nothing else. The hand-added `key-sign-apk.zip` below is
the one other container in this directory.

## A second container: `key-sign-apk.zip`

`key-sign-apk.zip` holds the same three files in a WinZip **AES-256** archive,
added by hand so the key can be opened on a machine that has no `gpg`. Its
password is not in this repository and is not a repository secret; it exists
only with the owner, beside `BACKUP_PASSPHRASE`.

Say the weak part plainly, because it is the reason the `.gpg` is still the copy
that matters: WinZip AES derives its key with PBKDF2-HMAC-SHA1 at **1000
iterations**, where the `.gpg` above uses an iterated SHA-512 S2K, so a guess
against this archive costs a small fraction of a guess against that one. It is
here for convenience, not as a second lock. Anyone who can read this repository
can attack it offline, exactly as the section above describes.

Opening it needs a reader that supports WinZip AES: Info-ZIP `unzip` refuses it
with `unsupported compression method 99`, which means the archive is intact
rather than corrupt.

## The passphrase is the whole security of this file

Because the blob is world-readable, its only protection is the passphrase, and
it is exposed to offline cracking forever. Two consequences:

- The workflow refuses to run unless `BACKUP_PASSPHRASE` is at least 24
  characters. Use 32+ random characters.
- **Keep the passphrase outside GitHub as well.** It lives in the repository
  secret `BACKUP_PASSPHRASE` and in your password manager. If it exists
  *only* as a GitHub secret, then losing the repository loses the passphrase
  too, and this blob becomes noise. The copy in your password manager is the
  actual root of trust; the blob here is the redundant copy that survives
  losing a laptop.

## Restoring

```sh
# 1. Decrypt (prompts for the passphrase)
gpg -d backup/room-browser-signing-backup.tar.gz.gpg > /tmp/rb-backup.tar.gz

# 2. Unpack — yields room-browser-release.jks, keystore.properties, README.txt
tar -xzf /tmp/rb-backup.tar.gz -C /tmp

# 3. Confirm it is the same key: this must match the fingerprint the workflow
#    printed under "Certificate SHA-256 fingerprint at backup time".
keytool -list -v -keystore /tmp/room-browser-release.jks -alias "$(grep keyAlias /tmp/keystore.properties | cut -d= -f2)"
```

To sign a build locally, the Gradle config reads four environment variables
(`android/app/build.gradle.kts`):

```sh
export ROOMBROWSER_KEYSTORE=/tmp/room-browser-release.jks
export ROOMBROWSER_STORE_PASSWORD=...   # from keystore.properties
export ROOMBROWSER_KEY_ALIAS=...        # from keystore.properties
export ROOMBROWSER_KEY_PASSWORD=...     # from keystore.properties
```

To restore CI signing after a loss, re-create the secrets from the unpacked
files:

```sh
base64 -w0 /tmp/room-browser-release.jks | gh secret set ROOMBROWSER_KEYSTORE_B64
gh secret set ROOMBROWSER_STORE_PASSWORD
gh secret set ROOMBROWSER_KEY_ALIAS
gh secret set ROOMBROWSER_KEY_PASSWORD
```

## Where the backup that matters actually lives

GitHub secrets are **write-only**: the API returns names and timestamps, never
values. Before this workflow existed, `ROOMBROWSER_KEYSTORE_B64` was the only
copy of the signing key in existence, and it could not be read back out — not
by CI, not by the repository owner. Deleting that secret, or losing access to
the account, would have meant permanently losing the ability to publish updates
for an installed app. That is why this backup exists; keep the downloaded
`.gpg` and the passphrase somewhere that is not GitHub.
