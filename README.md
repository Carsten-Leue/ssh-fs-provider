# SSH FS Provider

An Android app that mounts remote **SSH/SFTP servers as document roots** inside
the [Storage Access Framework (SAF)][saf].  Once configured, any SAF-aware
app — the system Files picker, text editors, video players, photo pickers — can
open, save, and browse files on your SSH servers without a separate sync step.

---

## Features

- **SAF integration** — servers appear as first-class storage roots in every
  Android file picker
- **Large-file support** — seek-capable reads via server-side SFTP offsets;
  writes buffered locally and uploaded atomically (`.part` rename pattern)
- **Concurrent access** — each open file gets its own dedicated thread; a slow
  upload never blocks directory listings or other open files
- **Secure key storage** — private keys and config are encrypted with
  AES-256-GCM using a master key held in the Android Keystore; Android Backup
  is disabled so keys never leave the device
- **Import and export** — transfer your config bundle to/from the device as a
  single `.tgz` file
- **Host key verification** — optional `known_hosts` support with
  `StrictHostKeyChecking = yes`; clearly warns when disabled
- **Modern SSH** — Ed25519, ECDSA (nistp256), RSA (rsa-sha2-256/512);
  curve25519 and ECDH key exchange

**Requirements:** Android 8.0 (API 26) or later

---

## Quick start

### 1 — Prepare a config bundle on your computer

Create a `.tgz` archive containing the SSH files you need.  The archive must be
**flat** (no sub-directories):

```
bundle.tgz
├── config          ← OpenSSH config file (required)
├── known_hosts     ← host key file (recommended)
└── id_ed25519      ← private key file(s)
```

#### Minimal `config` example

```
Host myserver
    HostName 192.168.1.10
    User alice
    Port 22
    IdentityFile id_ed25519
```

Multiple `Host` blocks are supported — each becomes a separate root in the
Android file picker.

#### Create the bundle

```bash
# From your ~/.ssh directory (adjust filenames as needed)
tar -czf bundle.tgz -C ~/.ssh config known_hosts id_ed25519
```

#### Generate `known_hosts` for a specific server

```bash
ssh-keyscan -H myserver.example.com >> ~/.ssh/known_hosts
```

> **Security note:** Including `known_hosts` is strongly recommended.  Without
> it, host key checking is disabled and connections are vulnerable to
> man-in-the-middle attacks.

---

### 2 — Install the app

Download the latest APK from the [GitHub Releases][releases] page and install
it (enable *Install from unknown sources* if prompted), or build it yourself
(see [Building](#building)).

---

### 3 — Import the bundle

1. Transfer `bundle.tgz` to your Android device (email, USB, cloud storage, …).
2. Open **SSH FS Provider** and tap **Import config bundle (.tgz)**.
3. Navigate to the bundle file in the picker and select it.
4. A success message confirms how many hosts and keys were imported.

The status card on the main screen lists all configured hosts.

---

### 4 — Browse files in other apps

After a successful import your SSH servers appear as storage roots in the
Android file picker:

1. In any app that supports file access, tap **Open** or **Browse**.
2. Look for your server name (host alias) in the left navigation drawer or the
   list of storage locations.
3. Navigate directories and open files exactly like local storage.

#### Notes on file operations

| Operation | Behaviour |
|-----------|-----------|
| **Read** | Streamed directly from the server; random seeks reopen the SFTP stream at the requested offset |
| **Write / edit** | Buffered in a local temp file; uploaded atomically to `<target>.part` then renamed on close |
| **Create file** | Creates an empty file via SFTP `put` |
| **Create folder** | `sftp.mkdir` |
| **Delete** | `sftp.rm` for files; `sftp.rmdir` for directories |
| **Rename** | `sftp.rename` |

---

## Exporting the config bundle

To back up your config or move it to another device:

1. Open **SSH FS Provider**.
2. Tap the **export icon** (↓) in the toolbar.
3. Choose a save location in the file picker.
4. The app writes `ssh_config_bundle.tgz` containing the config file, the
   `known_hosts` file (if present), and all stored private keys.

The exported bundle is in exactly the format the import step expects, so it can
be re-imported on any Android device running SSH FS Provider.

> **Security note:** The exported `.tgz` contains your private keys in plain
> text.  Store it securely and delete it once it is no longer needed.

---

## Clearing the configuration

Tap **Clear configuration** to remove all stored credentials from the device.
The provider immediately disappears from the SAF file picker.  This operation
is irreversible — export first if you need to keep the keys.

---

## Document ID format

SAF document IDs use the format `<hostAlias>:<absolutePath>`:

```
myserver:/                         ← root
myserver:/home/alice/docs/file.pdf ← file
```

---

## Building

### Prerequisites

- JDK 17 (temurin recommended)
- Android SDK (compileSdk 34, minSdk 26)
- No local Gradle installation required — CI downloads Gradle 8.6 automatically

### Debug build

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Release build (unsigned)

```bash
./gradlew :app:assembleRelease
# APK: app/build/outputs/apk/release/app-release-unsigned.apk
```

### Signed release via GitHub Actions

Push to any branch.  The workflow at `.github/workflows/build.yml` produces
both a debug APK and an unsigned release APK as build artifacts.

To produce a **signed** release APK, add the following repository secrets:

| Secret | Description |
|--------|-------------|
| `SIGNING_KEY` | Base64-encoded `.jks` / `.keystore` file |
| `SIGNING_KEY_ALIAS` | Key alias inside the keystore |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_PASSWORD` | Key password |

---

## Architecture

```
ConfigImportActivity        ← Import / export / clear UI
  └─ KeyStorage             ← AES-256-GCM encrypted storage (Android Keystore)

SshDocumentsProvider        ← SAF DocumentsProvider
  └─ SshConnectionManager   ← JSch Session pool (one session per host alias)
       └─ ChannelSftp       ← one channel per open document

SshReadProxyCallback        ← ProxyFileDescriptorCallback for reads
  └─ per-file HandlerThread ← prevents slow reads blocking other files

SshWriteProxyCallback       ← ProxyFileDescriptorCallback for writes
  └─ per-file HandlerThread ← atomic upload on release
```

### Key design decisions

**Double-checked locking in `SshConnectionManager`**
The `sessions` map is accessed only under `synchronized(this)`, but network I/O
(`session.connect`) runs outside any lock so slow connections never block
callers for unrelated hosts.

**Lazy channel open in `SshReadProxyCallback`**
The SFTP channel is opened on the first `onRead()` call rather than at
construction time, eliminating the race where the channel could disconnect
before `openProxyFileDescriptor` finishes registering the callback.

**Atomic remote writes**
Files are uploaded to `<target>.part` and then renamed to `<target>`.  If the
upload fails, the original remote file is preserved intact.  On SFTP v3 servers
that reject renames onto existing files, the app falls back to delete + rename.

**mtime-based config cache in `KeyStorage`**
`loadConfig()` compares the config file's modification time against a cached
value; AES decryption is skipped on cache hits.  This matters because
`queryRoots()` is called by the system on every file picker interaction.

---

## Supported SSH capabilities

| Category | Values |
|----------|--------|
| Key types | Ed25519 · ECDSA nistp256 · RSA (rsa-sha2-256, rsa-sha2-512) |
| Key exchange | curve25519-sha256 · ecdh-sha2-nistp256 · diffie-hellman-group14-sha256 |
| Authentication | Public-key only |
| SFTP operations | stat · ls · get (with offset) · put · mkdir · rm · rmdir · rename |

---

## Troubleshooting

**Server not appearing in the file picker**
- Re-import the bundle — the picker refreshes automatically on import.
- Verify the `config` file contains at least one `Host` block.

**Connection refused / timeout**
- Check the hostname, port, and network reachability from the device.

**Authentication failed**
- Confirm the value of `IdentityFile` in the config exactly matches the
  filename of the key in the bundle.
- Ensure the corresponding public key is in `~/.ssh/authorized_keys` on the
  server.

**Host key verification failed**
- Re-run `ssh-keyscan` and re-import the bundle with the updated `known_hosts`.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).

[saf]: https://developer.android.com/guide/topics/providers/document-provider
[releases]: https://github.com/carsten-leue/ssh-fs-provider/releases
