# Butan

A minimal end-to-end messenger for Android, built with Kotlin, Jetpack Compose
(Material 3 Expressive), **age** encryption and **phone-to-phone NFC**.

There are no tags, servers or accounts. Two phones exchange single-message
envelopes directly. A phone that receives an envelope which is not addressed to
it keeps the envelope in a carry queue and passes it to the next phone.

## What it does

- **age v1 / X25519** encryption. The implementation is wire-compatible with the
  reference `age` CLI for binary X25519 files (tested in CI/dev with the `age`
  binary).
- **Ed25519 message signatures** so a relay cannot forge the sender. The signing
  key is derived deterministically from the same 32-byte identity seed.
- **NFC contact exchange**: phones share a self-signed contact card containing a
  display name, age recipient and signing key.
- **Store-and-forward relay**: every phone keeps a FIFO queue. If an envelope
  decrypts for you it goes to the inbox; if not, it is queued and offered to the
  next phone.
- **Single-message format**: one age file per envelope, one envelope per NFC tap.
- **Material 3 Expressive** UI: `MaterialExpressiveTheme`, expressive motion,
  expressive loading indicator and Material Shapes.

## How a transfer works

Phones are not tags. The app uses Android Host Card Emulation (HCE) on one side
and an `IsoDep` reader on the other, over a **private AID** instead of NDEF:

1. Sender opens **Beam** and chooses **Send next envelope**, **Share my contact**
   or **Receive**.
2. The sender emulates a small ISO-DEP card over HCE and serves a private
   `F0425554414E01` application.
3. The receiver uses `NfcAdapter.enableReaderMode` with
   `FLAG_READER_SKIP_NDEF_CHECK`, selects the private AID and reads the frame
   with `READ BINARY`.
4. If the frame is an **envelope**, the receiver tries the age identity:
   - decrypts and verifies the signature -> **Inbox**
   - not addressed to this identity -> **carry queue**
5. If the frame is a **contact card**, the receiver verifies its signature and
   stores the contact.
6. The receiver can then switch to **Send next envelope** and pass the envelope
   to the next phone.

Both phones must have the app open. Roles are explicit because Android cannot
emit a reader field and emulate a card at the same time.

The private AID is the important part on Xiaomi and other OEM phones: the
receiver never enters Android's NDEF stack, so Mi Connect/Mi Share and other
vendor NDEF handlers cannot replace the envelope with their own tag.

### First contact

1. Phone A: **Beam -> Share my contact**.
2. Phone B: **Beam -> Receive**. Tap the phones together.
3. Reverse the roles so A receives B's contact card.

## Crypto details

- Identity: 32 random bytes, encoded as an age secret key
  (`AGE-SECRET-KEY-...`). The recipient is the X25519 public key encoded as
  `age1...` (Bech32).
- Message plaintext is signed JSON:
  `{"content":{"v":1,"from":...,"fromName":...,"signKey":...,"ts":...,"body":...},"sig":"..."}`.
- Envelope = age v1 X25519 file:
  - header with one `X25519` stanza, header MAC (HMAC-SHA-256),
  - payload nonce + ChaCha20-Poly1305 STREAM chunks (64 KiB).
- Contact card = self-signed JSON with `name`, `age` recipient and `signKey`.
- A message is only marked **Verified** if the sender's age recipient is a known
  contact *and* the included Ed25519 key matches the key from that contact card.

The app stores its identity seed and state in app-private internal storage.
`android:allowBackup` is disabled so secrets are not copied into cloud backups.

## Limits

- One envelope per NFC tap.
- Body is limited to 3000 UTF-8 bytes. Frames can carry up to 65526 bytes, and
  the receiver reads them in 240-byte ISO-DEP chunks.
- There is no delivery receipt and no background syncing. Relaying is manual and
  opportunistic, which is the point.
- A relay that receives an envelope not addressed to it cannot read it, but it
  can drop it. This is a delay-tolerant design, not a guaranteed delivery
  network.

## Development with Nix

Everything used for development is declared in `flake.nix`; the nixpkgs input is
pinned in `flake.lock`.

```sh
nix develop
```

The shell provides:

- JDK 17
- Gradle 9.8.2 (from the pinned nixpkgs)
- Android SDK: platform `android-37.0`, build-tools `36.0.0`, platform-tools
- `age` 1.3.2 (used by the age interoperability tests)
- `git`, `jq`, `unzip`

Build and test:

```sh
gradle :core:test
gradle :app:assembleDebug
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it with the SDK platform-tools:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If a configured Nix substituter is unavailable, pin the official cache for the
command:

```sh
nix develop --option substituters 'https://cache.nixos.org'
```

### Versions

The main versions are declared in `gradle/libs.versions.toml`:

- AGP 9.5.1 / Gradle 9.8.2 / Kotlin 2.2.10 (AGP built-in Kotlin)
- Compose BOM 2026.09.00
- Material 3 `1.5.0-rc01` (Material 3 Expressive APIs)
- Bouncy Castle 1.86 (X25519, Ed25519, ChaCha20-Poly1305, HKDF, HMAC)
- compileSdk 37 (minor 0), minSdk 28, targetSdk 37

## Project layout

```
core/                         pure Kotlin/JVM, no Android dependencies
  Age.kt                      age v1 X25519 encryption/decryption
  Bech32.kt                   age key encoding
  Crypto.kt                   Bouncy Castle primitives
  Identity.kt                 X25519 identity + Ed25519 signing key
  Messages.kt                 signed envelopes and contact cards
  src/test                    unit tests + age CLI interoperability tests

app/                          Android app
  data/ButanStore.kt          persistence, inbox, carry queue, dedupe
  nfc/ButanHostApduService.kt  HCE ISO-DEP sender (private AID)
  nfc/NfcGateway.kt           reader-mode receiver
  nfc/NfcSlot.kt              currently armed ISO-DEP frame
  ui/ButanViewModel.kt        beam state and actions
  ui/ButanRoot.kt             Material 3 Expressive Compose UI
  ui/theme/Theme.kt           MaterialExpressiveTheme
```

## Test coverage

`gradle :core:test` covers:

- Bech32 round trips and the age specification key example.
- age round trips including empty and multi-chunk payloads.
- cross-compatibility both ways with the reference `age` CLI.
- signed envelopes, wrong-recipient handling, forged signatures, contact cards
  and tampered contact cards.
