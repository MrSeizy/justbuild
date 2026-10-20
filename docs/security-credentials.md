# API credential storage

JustBuild encrypts OpenAI-compatible API keys before writing `config/justbuild.json`. The provider receives the
original key in memory when making a request. A one-way hash cannot replace that key because the endpoint needs
the original credential.

## Disk format and protection

Each profile stores an `encryptedApiKey` envelope using AES-256-GCM, with a fresh 96-bit nonce on each save and a
128-bit authentication tag. The profile name and endpoint are authenticated with the ciphertext. A key copied to
another profile or endpoint fails to unlock. The `credentialKey` field is a reference to a random 256-bit unlocking
key; it is not the key itself. API keys and unlocking keys are never included in command-line arguments.

| Platform | Default unlocking-key storage |
| --- | --- |
| macOS | A generic-password item in Keychain, under service `dev.justbuild.config-encryption`. |
| Windows | A DPAPI-protected file in `%USERPROFILE%\AppData\Local\JustBuild\credentials\`, tied to the OS user. |
| Linux / other POSIX hosts | An owner-only file in `~/.local/share/justbuild/credentials/`, outside the instance and world. |

The file vault directory uses mode `0700` and key files use `0600` on POSIX systems. Windows files are created with
an owner-only ACL protected from inherited permissions. Vault reads refuse symbolic links and permissions that grant other users access. macOS keychain
operations fail instead of opening a password prompt inside Minecraft. A locked or inaccessible keychain does not
trigger a weaker fallback.
The API-key field never includes entered text in narration or copy/cut actions, including while visually revealed.
Pasting a key remains supported. Saved keys are not repopulated into the field.

The encrypted config itself requests owner-only permissions where supported, but remains writable on game folders
whose file system cannot enforce them. The external vault always requires private permissions.

For a headless macOS server, or a dedicated server with a separate private storage location, add the JVM argument
`-Djustbuild.credentialsDir=/absolute/private/directory`. This explicitly chooses a file vault on macOS/Linux;
Windows still protects its files with DPAPI. Keep that directory outside instance exports and shared backups.
The directory must remain private to the account running Minecraft. Unit tests use temporary vaults; game tests
use an isolated directory under `build/`.

This protects keys when a config file or game folder is accidentally copied or shared. It cannot protect against
malware, another mod, or an administrator reading the running process. On file-vault systems, someone who obtains
both the config and its unlocking-key file can decrypt the credentials. Windows and macOS protection also depends
on the OS account and credential store.

## Migration and edits

On load, existing plaintext keys are encrypted and the config is replaced atomically. No plaintext backup or
temporary config is created. If vault access or saving fails, the
original file is left in place and the failure is reported. Old backups made before this migration can still contain
plaintext keys; encryption does not remove those backups or securely erase old filesystem blocks.

Use the profile editor to rename, copy, clear, or change a profile. It decrypts the current key locally and writes a
fresh envelope for the resulting profile. Changing an endpoint still drops the old key unless a replacement is
entered. Keys are not returned to remote clients by the server's panel-state packets.

An administrator can provision a new plaintext `apiKey` field by hand and reload; it is encrypted on load. Remove
that profile's `encryptedApiKey` first. Supplying both nonempty plaintext and ciphertext is rejected. Do not hand-edit
the name or endpoint of a profile with an encrypted key; use the editor, or remove the encrypted entry and enter the
key again. Moving a world alone does not move provider credentials.
Older mod versions cannot read `encryptedApiKey`. Preserve the encrypted config and matching vault before a
downgrade; opening and saving that config with an older version can discard fields it does not recognize.

## Recovery and moving an instance

Keep the config and vault backups private. Copying the config to another computer without its vault produces an
unlock error, rather than treating the profile as keyless or generating a new key over the old one. Windows DPAPI
files also require the original account's protection; copying those files alone to another user is insufficient.

If the keychain is locked, unlock it in the OS and reload. If the external key directory was moved, restore it or
set `justbuild.credentialsDir` to its location. If the unlocking key is lost, preserve a copy of the config, remove
the affected `encryptedApiKey` entries and the top-level `credentialKey`, then re-enter the API keys in the profile
editor. This also provides a straightforward way to move an instance to a different OS account.

## Verification

The unit suite covers plaintext migration, encrypted round trips, fresh nonces, name/endpoint binding, tampering,
missing and malformed vault keys, permissions, conflicting credential fields, failed replacement, and profile edits.
The profile and relay game tests exercise encrypted storage through the actual panel and networking paths.

`JUSTBUILD_TEST_KEYCHAIN=1 ./gradlew test --tests '*NativeCredentialKeysTest'` opts into a macOS native smoke check.
It creates a randomly named synthetic Keychain item, reads only that item, and deletes it afterward; it does not read
existing credentials. Normal tests never access the developer's Keychain.
Windows test runs also check DPAPI round trips and ACL inheritance protection.

The native integrations follow [Apple's Keychain item API](https://developer.apple.com/documentation/security/secitemcopymatching(_:_:))
and [Microsoft's user-scoped DPAPI API](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata).
