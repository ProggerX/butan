# Butan

A minimal end-to-end messenger for Android, built with Kotlin, Jetpack Compose
(Material 3 Expressive), **age** encryption and **phone-to-phone NFC**.

There are no tags, servers or accounts. Two phones exchange encrypted envelopes
directly. A phone that receives an envelope which is not addressed to it keeps
the envelope in a carry queue and passes it to the next phone.

## What it does

- **Two tabs: Chats and Transfer.** Every known contact and every person you
  have exchanged messages with gets a conversation.
- **Real chat history.** Open a person to read everything you have exchanged
  with them and write a new message.
- **Selectable messages.** Message text can be selected and copied. Links are
  clickable, and the message menu can copy the whole text or open its link.
- **Transfer queue.** The second tab shows everything that can be handed to the
  next phone: your messages, relayed messages and contact cards.
- **Share contacts.** Send your own signed contact card or forward somebody
  else's original card from the Transfer tab.
- **Passing does not delete.** A transferred message stays in your chat and in
  the Transfer tab. It also stays in the outgoing queue, so it can travel on to
  the next phone. Remove it explicitly with the card menu when you are done.
- **age v1 / X25519** encryption. The implementation is wire-compatible with the
  reference `age` CLI for binary X25519 files.
- **Ed25519 message signatures** so a relay cannot forge the sender. The signing
  key is derived deterministically from the same 32-byte identity seed.
- **Material 3 Expressive** UI: `MaterialExpressiveTheme`, expressive motion,
  expressive loading indicator, Material Shapes and a teal/coral palette.

## The two tabs

### Chats

The list contains contacts with no messages as well as conversations started by
unknown senders. Search filters by name and message text. A conversation shows:

- the complete message history with that person;
- delivery state for your own messages (**В очереди** / **Передано**);
- signature state for incoming messages (**Подпись** / **Без подписи**);
- unread counts in the list.

Messages are rendered inside a `SelectionContainer`, so text can be selected
with the usual Android handles and copied. URLs are annotated with
`LinkAnnotation.Url`; tapping one opens the browser.

### Transfer

The Transfer tab is the outgoing side of the delay-tolerant network:

- **В очереди** — messages and contact cards that will go into the next NFC
  bundle. Items stay here after a hand-off and are offered again, so a relay can
  pass the same envelope to several people.
- **Мой контакт** — queues your signed contact card.
- **Другой контакт** — lets you pick a saved contact and forward its original
  signed card. Contact cards are self-signed by their owner, so the app forwards
  the exact stored bytes instead of trying to rebuild the card.

When a bundle is written successfully, items are only marked as passed
(`passedAt` + `passCount`); the chat message and its transfer entry remain in
local storage and in the queue. The card menu can remove a queue entry without
touching the chat history.

## How a transfer works

Phones are not tags. The app uses Android Host Card Emulation (HCE) on one side
and an `IsoDep` reader on the other, over a **private AID** instead of NDEF:

1. Both phones have Butan open. The app automatically alternates between
   listening and offering a bundle.
2. The sender emulates a small ISO-DEP card over HCE and serves a private
   `F0425554414E01` application.
3. The receiver uses `NfcAdapter.enableReaderMode` with
   `FLAG_READER_SKIP_NDEF_CHECK`, selects the private AID and reads the frame
   with `READ BINARY`.
4. The bundle can contain several entries: your contact card, queued outgoing
   messages, relayed messages and forwarded contact cards.
5. On the receiving phone:
   - an envelope that decrypts and verifies goes into the chat with its sender;
   - an envelope addressed to somebody else goes into the relay queue;
   - a contact card is verified and saved, or its stored card is refreshed.

Both phones must have the app open. Android cannot emit a reader field and
emulate a card at the same time, so the app switches roles automatically.

The private AID is the important part on Xiaomi and other OEM phones: the
receiver never enters Android's NDEF stack, so Mi Connect/Mi Share and other
vendor NDEF handlers cannot replace the envelope with their own tag.

### First contact

1. Phone A and phone B both open Butan.
2. Tap the phones together.
3. Both phones receive the other's contact card and the contact appears in
   **Чаты**.

You can also queue your card explicitly with **Мой контакт** in the Transfer
tab.

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
- Your own contact card is rejected if it comes back through a relay, so it
  cannot turn into a self-contact.

The app stores its identity seed and state in app-private internal storage.
`android:allowBackup` is disabled so secrets are not copied into cloud backups.

## Limits

- Body is limited to 3000 UTF-8 bytes. Frames can carry up to 65526 bytes, and
  the receiver reads them in 240-byte ISO-DEP chunks.
- There is no delivery receipt and no background syncing. Relaying is manual and
  opportunistic, which is the point.
- A relay that receives an envelope not addressed to it cannot read it, but it
  can drop it. This is a delay-tolerant design, not a guaranteed delivery
  network.
- A queued message keeps being offered in every bundle until you remove it with
  the card menu. That is intentional for relaying, but it also means the queue
  can grow; prune it from time to time.

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

The app version is `5.1` (`versionCode 7`).

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
  data/Models.kt              contacts, chat messages, transfer queue
  data/ButanStore.kt          persistence, conversations, queue, dedupe
  nfc/ButanHostApduService.kt  HCE ISO-DEP sender (private AID)
  nfc/NfcGateway.kt           reader-mode receiver
  nfc/NfcSlot.kt              currently armed ISO-DEP frame
  ui/ButanViewModel.kt        beam state and actions
  ui/ButanRoot.kt             two-tab scaffold and navigation
  ui/ChatsScreen.kt           conversation list and profile hero
  ui/ChatScreen.kt            message history, selection, links, composer
  ui/TransferScreen.kt        transfer queue and contact sharing
  ui/Common.kt                shared expressive components and formatting
  ui/theme/Theme.kt           MaterialExpressiveTheme, shapes, palette
```

## Test coverage

`gradle :core:test` covers:

- Bech32 round trips and the age specification key example.
- age round trips including empty and multi-chunk payloads.
- cross-compatibility both ways with the reference `age` CLI.
- signed envelopes, wrong-recipient handling, forged signatures, contact cards
  and tampered contact cards.
- NFC frame/APDU round trips and a one-session bundle loopback.
