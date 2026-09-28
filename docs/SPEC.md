# OwU: ledger and protocol specification

A serverless, local-first ledger of signed IOUs ("OwUs") exchanged in person over Bluetooth Low Energy.

---

## 1. Concepts

- **Debtor:** who wrote the OwU and owes what it says.
- **Holder:** who currently holds the right to redeem it. The creditor at issue, or whoever it has since been signed over to.
- **Ledger:** the immutable sequence of signed blocks under one `iou_id`. There is no global ledger; each OwU carries its own.

### States

1.  **ACTIVE:** signed by its issuer and live on the chain. Every OwU is in this state from the moment it is written until it is redeemed.
2.  **REDEEMED:** terminal. The issuer's signature confirms the promise was kept, and nothing may follow.

A **template** is not a state: no chain, no signature, and putting one on a table mints a fresh ACTIVE OwU from it (section 3). There is no state for "waiting on somebody", by design (section 2.5).

### Gates: place and time

An OwU may name a place (point, radius, label) and a redemption window (`not_before`, `not_after`, epoch seconds). Issue refuses a window that closes before it opens; both are optional.

**Neither is enforced, by the protocol or the app.** A window that has passed or a place you are not at is painted red on both sides of the table, and nothing more: only the person who wrote the promise can say whether it still counts, and they say so by accepting or not. Enforcing it would be theatre in any case, since a GPS fix and a block timestamp are both self-reported by the device that wants the answer.

Both are covered by the ISSUE signature, so neither can be edited after the fact.

### Non-transferable

An OwU may be marked `non_transferable`. It can be given away by its debtor, and handed back to them to be redeemed, and that is all: whoever receives it cannot pass it on, by gift or by swap. **Unlike the gates, this is enforced**, by the verifier (section 2.3), so a chain that passes one on is rejected wherever it goes. The flag is in the signed metadata, so it cannot be removed after issue. In the canonical form it appears only when set, which leaves the hash of every OwU written without it unchanged.

---

## 2. Cryptography

### 2.1 Keys **[`crypto/Identity.kt`]**

- **Algorithm:** ECDSA over secp256r1 (NIST P-256), `SHA256withECDSA`, DER signatures in the one canonical encoding of section 2.4.
- **Storage:** the app's own storage, sealed with AES-GCM under a key that _is_ in the Android Keystore. The file is worthless off the device; the identity can still be read out by its owner.
- **Why not a Keystore signing key.** Such a key cannot be extracted, so the identity would die with the phone and every OwU anyone holds from you would be impossible to redeem, since only its author can close a promise (2.5). A backup that cannot carry the key is not a backup.
- **What that costs:** the backup file _is_ the identity, and its passphrase (section 4) is the only thing between a copied file and a stolen name.
- **Identity:** the public key, as base64 of the X.509 `SubjectPublicKeyInfo` DER (91 bytes for P-256). Shown to humans as a 16-hex-digit fingerprint: the first 64 bits of its SHA-256.

### 2.2 Payload schema **[`ledger/Model.kt`]**

An OwU is its metadata and a chain of blocks.

```json
{
    "iou_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "metadata": {
        "display_title": "1 Heavy Hug",
        "description": "Whenever you need one. No questions.",
        "template_id": "tmpl_hug_01",
        "geoloc": {
            "lat_e6": 46519700,
            "lon_e6": 6632300,
            "radius_m": 200,
            "label": "Lausanne"
        },
        "not_before": 1790000000,
        "not_after": 1792600000,
        "non_transferable": true
    },
    "ledger_chain": [
        {
            "action": "ISSUE",
            "sequence": 0,
            "timestamp": 1790000000,
            "parent_hash": "0000...0000",
            "debtor_pub_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
            "creditor_pub_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
            "metadata_hash": "3a7f...",
            "signature": "MEQCIFzV8hNz..."
        },
        {
            "action": "TRANSFER",
            "sequence": 1,
            "timestamp": 1790050000,
            "parent_hash": "9c21...",
            "transferor_pub_key": "...",
            "transferee_pub_key": "...",
            "signature": "MEQCIH8XmY9w..."
        }
    ]
}
```

Block types and who signs them:

| `action`   | signer         | extra fields                                                                         |
| ---------- | -------------- | ------------------------------------------------------------------------------------ |
| `ISSUE`    | debtor         | `debtor_pub_key`, `creditor_pub_key`, `metadata_hash`                                |
| `TRANSFER` | current holder | `transferor_pub_key`, `transferee_pub_key`, and `agreement` for a swap (section 2.6) |
| `REDEEMED` | debtor         | `debtor_pub_key`                                                                     |

**Canonical form.** Signatures and hashes are never computed over the wire JSON. They are computed over a canonical encoding (`crypto/Canonical.kt`): the RFC 8785 subset of objects with keys sorted by UTF-16 code unit, strings, integers, booleans and lists; map entries whose value is null are omitted; floating point is rejected (coordinates are integer micro-degrees). This lets the transport format grow fields without invalidating a single signature.

**What a signature covers.** `canonical({iou_id, sequence, action, timestamp, parent_hash, ...action fields})`. Including `iou_id` means an ISSUE block cannot be replayed as a second OwU under a different id.

**Metadata is signed.** `metadata_hash = SHA-256(canonical(metadata))` is inside the ISSUE block, so the title, the description, the place and the redemption window cannot be edited after issue - neither by the debtor nor by anyone who later holds it.

**Linking is by hash, not by signature.** `parent_hash = SHA-256(canonical(previous block payload + its signature))`, hex. ECDSA signatures are malleable - `(r, s)` and `(r, n - s)` are both valid - so linking by the literal parent signature would let one history exist as two byte-different chains. Hashing the whole previous block gives one head per history. ISSUE uses 64 zeros.

### 2.3 Verification **[`ledger/Verifier.kt`]**

A received chain is accepted only if every rule holds; otherwise it is dropped with the failing block index and reason.

1.  **Block 0** is `ISSUE`: `sequence == 0`, `parent_hash == 0...0`, `metadata_hash` matches the metadata, signature verifies under `debtor_pub_key`. Debtor and creditor may be the same key: a blank promise (section 3). Initial state: holder = creditor, status = ACTIVE.
2.  **For each block n >= 1:** `sequence == n`; `parent_hash` equals the hash of block n-1; no block may follow a `REDEEMED`; the signature verifies under the block's designated signer, **and is in the one accepted encoding** - minimal DER, low _s_ (section 2.4).
3.  **Authorization matrix:**
    - `TRANSFER`: status is ACTIVE; `transferor_pub_key` is the current holder; transferee != transferor; if the metadata is `non_transferable`, the holder or the transferee is the debtor. Holder becomes transferee. With an `agreement`, section 2.6 applies on top of those rules.
    - `REDEEMED`: status is ACTIVE, `debtor_pub_key` is the OwU's debtor, **and the debtor is the current holder** - a promise is closed by its maker, once it is back in their hands. Status becomes REDEEMED.

Timestamps are not validated against each other; device clocks are not trusted and they are for display only.

### 2.4 Forgery and duplication **[`crypto/Keys.kt`, `store/IouStore.kt`, `peer/PeerEngine.kt`]**

Two things can go wrong with an OwU, and they are not the same kind of problem.

**Forgery is prevented.** Every block names the identity that must have signed it, and section 2.3 verifies each under that key before the chain is kept. Writing an OwU in someone's name, or moving one you do not hold, needs their private key. What the protocol cannot supply is that a key belongs to the person in front of you: that comes from meeting them (section 4).

Signatures are additionally required to be **canonical**: minimal DER with low _s_. ECDSA accepts (r, s) and (r, n - s) alike, so without this rule anyone who has merely _seen_ an OwU could rewrite its head signature - still valid, different block hash - and make two copies that look like a double-spend by their holder. With the rule, a fork means what it says.

**Duplication can only be detected, never prevented.** A holder can sign two competing blocks at the same sequence - the same OwU to C and to D - and no offline system can stop them. Two things catch it:

1.  **At the table** (`PeerEngine.adoptTheirs`, `IouStore.compare`): the moment an OwU reaches the other side, in a `TABLE` or riding on an `ACCEPT`, it is checked against the copy they already hold. A history that contradicts theirs, or a head they know has already moved on, is refused - the OwU cannot be accepted and the confirm button is dead. This is the only check that works with the debtor nowhere in sight, and it is why the whole chain travels with every message.
2.  **At redemption** (`PeerEngine.given`, on the `GIVE` that brings an OwU home): the debtor merges the presented chain into their own. A fork from a chain they have already redeemed is declined outright; a fork from an open copy is shown as a conflict, and honouring it anyway is the debtor's call. **The debtor is the arbiter**: whichever chain they sign `REDEEMED` on is the one that was honoured, and the other is dead.

No risk score is offered. Counting how far an OwU has travelled was tried and removed: amber on almost everything, silent about whether a double happened, and too late to act on. The table shows who owes it instead, which is the fact you are weighing.

### 2.5 Redemption **[`Ledger.redeem`, `PeerEngine.closeIfHome`]**

**Redeeming an OwU is handing it back to the person who made it.** It is the table of section 3 with one OwU on it whose debtor is the other side: the holder signs an ordinary `TRANSFER` home, and the debtor, now holding their own promise, appends `REDEEMED` - terminal, and valid only when holder and debtor are the same person. The table already collects both consents, so the ledger needs no requested state and no cancel block. A debtor who would rather not simply does not accept; `DECLINE` (section 3) tells the asker so, and touches no chain.

A promise that arrives back with its author closes itself however it came, handed over or swapped (`PeerEngine.closeIfHome`), and the closed chain goes back as a receipt. There is no manual "close", because no path leaves a promise open in its author's hands: your own OwUs are only ever ones that have not left yet, and those are yours to give away or delete.

_(This replaced a two-phase `REDEEM_REQUEST` design that left OwUs frozen in a "requested" state. The handshake is the consent; a separate request was ceremony.)_

### 2.6 Exchange (atomic swap) **[`Ledger.proposeExchange` / `acceptExchange` / `applyExchange`]**

Two holders swap **bundles** in one step: each side puts down one or more OwUs - five beers, two hugs and a surprise trip is one side - and all of them move together or none does. It runs on an **agreement** that both sign:

```json
{
    "exchange_id": "...",
    "timestamp": 1790060000,
    "left": {
        "holder_pub_key": "A",
        "ious": [
            { "iou_id": "X1", "head_hash": "..." },
            { "iou_id": "X2", "head_hash": "..." }
        ],
        "signature": "...by A"
    },
    "right": {
        "holder_pub_key": "B",
        "ious": [{ "iou_id": "Y", "head_hash": "..." }],
        "signature": "...by B"
    }
}
```

Each side's `ious` are sorted by `iou_id` in the signed bytes, so both parties sign the same canonical form whatever order they assembled the bundle in.

1.  **Propose:** A, holding every OwU on the left, builds the agreement naming each of them at its current head and each of B's (as A last saw them) at its current head, and signs the core (everything except the two signatures).
2.  **Accept:** B, holding every OwU on the right, checks that each is still at the stated head and that B is the named holder, and signs the same core.
3.  **Apply:** with both signatures present, a `TRANSFER` carrying the agreement is appended to _every_ chain named. On A's OwUs the block is signed by A, on B's by B - and the block's signature **is** that side's agreement signature, so once both have signed, either party can append every block. Because the agreement is what one signature has to commit to, such a block signs `signingBytes()` rather than its own payload; that is the only case where the two differ.

On top of the `TRANSFER` rules, the verifier accepts an agreement-bearing block on chain Z only if: the agreement names Z on one side and not on the other; that side's holder is the block's `transferor_pub_key` and the other side's holder is its `transferee_pub_key`; Z's pinned `head_hash` in that side equals the block's `parent_hash`; the block's timestamp equals the agreement's; the other side is not empty; both signatures are present and verify; and the block's signature equals this side's agreement signature.

Pinning **every** OwU to a head hash is what makes the bundle one deal: if any of them moves first the agreement is void, and no partial swap can land.

---

## 3. Meeting in person **[`peer/`, `ble/`]**

There is no synchronisation. Two devices only ever exchange messages as steps of something a user asked for, and every message carries the whole chain it concerns, so the receiver verifies it from the ISSUE block.

To the user there are two things to do with someone: put something on the **table**, and **redeem**. The table has two sides; each person puts a bundle of OwUs on their side or leaves it empty, sees what the other put, and says yes. Nothing moves until both have said yes to the _same_ table. Everything that moves an OwU is this shape: a gift is my bundle against their nothing, a new promise is one I write and put down, a swap is a bundle each. A bundle may hold several copies of one template - five beers are five separate OwUs that travel together.

Both of these reach a person wherever they are in the app, by a prompt: being asked to a table, and being asked to honour a promise, are the two interruptions worth making. Everything else waits until somebody looks.

| Handshake   | Steps                                                                                                                                                            | Who signs what                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Invite**  | `INVITE{key}` on opening a table                                                                                                                                 | Nothing signed, no ledger state, no answer expected. Without it, opening a table reaches nobody not already on the right screen. Refused before authentication like any message, and ignored unless the sender is a contact.                                                                                                                                                                                                                                                                                                                                                                                                       |
| **Decline** | `DECLINE{key, to}`, `to` being `INVITE` or `TABLE`                                                                                                               | Nothing signed. Refusing the **table** clears it on both sides - OwUs stay with their holders, a minted OwU that never left is discarded - so nothing sits refused.                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| **Table**   | `TABLE{key, ious[]}` either way, any number of times -> `ACCEPT{key, deal, ious[], agreement?}` from each side -> `GIVE{ious[]}` when only one side has anything | The `deal` is a hash of every OwU on the table, each pinned to its current head, so both sides compute the same string and a yes that crosses a change is refused. **One side only:** on the second yes its holder signs a `TRANSFER` per OwU and sends them all in one `GIVE`. **Both sides:** the side holding the lowest OwU id puts a half-signed `ExchangeAgreement` (section 2.6) covering both bundles in its `ACCEPT`; the other countersigns, applies the agreement-bearing `TRANSFER` to every OwU, and returns the complete agreement, which the first side applies too. **Nothing on either side:** nothing to accept. |
| **Redeem**  | the Table above, with promises of theirs on my side -> `GIVE{ious[]}` -> `REDEEMED{iou}` per OwU                                                                 | it is a gift back to the person who owes them: the holder's `TRANSFER` sends each home, and the debtor appends `REDEEMED` to each on receipt and returns the closed chains as receipts (section 2.5)                                                                                                                                                                                                                                                                                                                                                                                                                               |

**A promise you write is a template, not an OwU** (`store/Template`, in `files/templates/`). No chain, no signature; it stays until deleted. Putting one on a table _mints_ an OwU: new `iou_id`, ISSUE signed there and then, debtor = creditor = you. So one "1 Beer" becomes as many beers as you hand out. A minted OwU that never leaves is deleted again, which is safe because it is still a lone ISSUE block in its author's hands and nobody else can hold it. The verifier allows debtor = creditor; the app never offers to redeem an OwU from yourself.

### 3.1 One carrier: a connection **[`ble/Link.kt`, `ble/Frames.kt`]**

Nothing to pair and nothing to tap, but a connection underneath.

- **Finding each other.** One connectable legacy advertisement: our service UUID (`0000f077-...`, 16-bit aliased so it fits in 31 bytes) and the device's 8-byte beacon, the first 8 bytes of SHA-256 over the public key's DER. Every device scans for that service. The peer list is the same whatever the radios can do.
- **A connection is opened** when a contact is picked to trade with, or when an OwU is asked to be redeemed from anywhere in the app, and dropped when that is done. Each device runs both a GATT server and a client, so either side can dial; whoever dials writes to the characteristic and the other notifies back on it.
- **Framing** (`ble/Frames.kt`, pure and unit-tested): `length (4) + sender beacon (8) + payload`, written in pieces of at most `min(MTU - 3, 512)` bytes - an attribute value is 512 bytes however large the MTU says it is, and Android throws above that.
- The beacon rides in every frame because a connection says which _device_ is talking, not which identity, and the side that accepted the connection has not yet heard an advertisement from the side that dialled.
- **Handshake:** on a new connection both sides send `HELLO{identity, nonce}` and answer the other's with `AUTH{signature}` over `"owu-auth-v1\n<their nonce>\n<my key>"`. Until that signature checks out, nothing else is accepted from them - but a message arriving before it is answered with our own `HELLO` rather than dropped, or a side whose radio restarted would leave the other waiting for ever on an answer it will never send. A new nonce from a verified peer means they restarted, so this side does too.
- **Foreground only.** The app puts the device on the air while it is open and takes it off when it is paused, so an ask can be made and answered from any screen but never while the app is away. A peer unheard of for 12 seconds is dropped - being heard advertising is the only evidence of presence, since a peer that sleeps or is killed often leaves its connection behind with no disconnect ever arriving.
- **Permissions:** `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`; `BLUETOOTH` and `BLUETOOTH_ADMIN` are declared `maxSdkVersion="30"` for API 28-30, where scanning also needs location.
- **Nothing on the connection is encrypted.** Every block is signed, so a listener in range can forge nothing and alter nothing, but it can _read_ what two people trade: titles, descriptions, places, keys. The advertisement is public as well, and its beacon is stable, so a phone can be recognised by anyone watching for one.

---

## 4. Storage **[`store/`]**

- One JSON file per OwU under `files/ious/`, one per template under `files/templates/`, plus `files/contacts.json` and `files/met.json`. Written via temp-file-and-rename. Small enough to hold in memory; exposed to the UI as `StateFlow`s.
- **Merge rule for an incoming chain** (`IouStore.merge`): verify first; a new id is added; a longer chain whose prefix equals ours replaces ours; a shorter or equal one is ignored; anything else is a **fork**, reported and not stored (section 2.4). Every handshake in section 3 stores what it receives through this rule.
- **Backup** (`store/Backup.kt`, Settings > Backup) writes the whole phone to one file: the identity (both halves of the key), the display name, every OwU, every template and the address book. The file is JSON whose only readable part is how to open it - `{v, app, kdf, rounds, salt, nonce, data}` - with the contents under AES-256-GCM, keyed by PBKDF2-HMAC-SHA256 over a passphrase the user picks (210 000 rounds, 16-byte salt). A wrong passphrase and a tampered byte are the same event to GCM and get the same message.
    - Restoring puts OwUs back through the merge rule of this section, so a restore onto a phone that has moved on since cannot rewind anything.
    - Taking on the backup's identity is a separate question the app asks; it replaces the phone's own, and promises made under the old one could no longer be closed from there. OwUs are restored either way.
    - There is no single-OwU import or share: a chain says who holds it, so sending its text to somebody changes nothing. Section 3 is how OwUs move.
- **Who a key is** comes in two grades, which is the whole point of the address book.
    - **Met** (`files/met.json`): the name a key gave itself in its `HELLO`, kept once `AUTH` proved it holds that key. Nobody vouched for the _name_, so it only saves a screen from being a wall of hex.
    - **A contact** (`files/contacts.json`): a key you have met and named yourself, by scanning their code or entering it. This is what lets an OwU keep its meaning second-hand: the signature proves a key promised something, the contact says whose key it is. So an OwU owed by someone other than whoever offers it reads as _"owed by Dana, in your contacts"_, or _"owed by ce21 9047 de70 0252, whom you have not met"_ in red.
    - A peer introducing itself with a name you know, on a key you do not, is called out on the peer screen.
- **Permissions:** `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` (the place gate, asked when first needed) and `CAMERA` (QR scanning, asked by the scanner). Bluetooth is section 3.1.
