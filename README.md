<!--suppress ALL -->
<div align="center">
  <h1>OwU</h1>

  <p>Owe you - keep track of who owes whom</p>

  <img src="https://forthebadge.com/images/badges/built-for-android.svg" alt="Built for Android">
  <img src="https://forthebadge.com/images/badges/built-with-love.svg" alt="Built with love">
  <br>
    <a href="https://github.com/helcel-net/iOweU/actions/workflows/build.yml">
    <img src="https://github.com/helcel-net/iOweU/actions/workflows/build.yml/badge.svg?branch=main" alt="Build Status">
  </a>
</div>

## ⭐ Features

- Signed IOUs: each OwU is a chain of custody, signed with a P-256 key held by the app and sealed by the phone's keystore
- Back it all up - identity, OwUs, templates, contacts - to one passphrase-encrypted file, and restore it on your next phone
- Write, trade (each side puts in an OwU or nothing; a swap is atomic) and redeem OwUs in person, over Bluetooth with nothing to pair. No server.
- Being asked to trade, and being asked to redeem, reach the other person wherever they are in the app - and a "no" is sent back rather than left as silence
- Tie an OwU to a place or a time window
- Identities exchanged by QR code (the only thing codes are used for)
- Small & Fast
- 100% Free and Open Source software, with no proprietary dependencies

See [docs/SPEC.md](docs/SPEC.md) for the protocol.

## 🤝 How it works

Every OwU is a promise from one person (the debtor) to another (the holder), signed with a key that never leaves the debtor's phone. Nothing happens on a server; OwUs move only when two people meet.

1. **Your identity** is a key pair, and a name you choose for yourself that travels with it. You meet somebody by scanning the code on their profile, which stores their key under whatever you decide to call them - and that key is what lets an OwU they owe be recognised as theirs even when it reaches you through somebody else. OwUs owed by people you have not met say so.
2. **The table** is how anything moves. Tap the trade arrow beside somebody in your contacts and they are asked to the table wherever they are in the app; then each of you puts one OwU on your side of it, or nothing. You both see what is there and what you would each walk away with; it happens when you have both confirmed. A gift is your OwU against their nothing; a swap is an OwU on each side, and it is atomic - neither side can end up short. Nothing is paired: the phones find each other by advertisement and open a connection that lasts as long as the trade. You can only trade with somebody you have named, which is the point of naming them.
3. **`+`** writes a promise you are _ready_ to make, and keeps it. Nothing is signed and nobody is owed anything yet. Each time you put it on a table, a fresh OwU is minted and signed from it - so one "1 Beer" serves every round you ever buy. Delete it when you are done with it.
4. **Redeeming** is the same table, pointed the other way, and it needs no screen: press Redeem on an OwU and the person who owes it gets a prompt, wherever they are. They accept, or they say not now and you are told so. If they are not around the ask waits and goes out by itself when they turn up. OwUs can be tied to a place or a time window, and that is shown in red when it has passed or you are elsewhere - never enforced, because only the person who wrote the promise can say whether it still counts.

**Forging an OwU is not possible** without the private key of the person who owes it, and keys never leave the phone that made them. **Copying one cannot be prevented** - no offline system can stop someone signing the same OwU over to two people - so OwU catches it instead: the moment an OwU is put on the table it is checked against the copy you already hold, and a history that contradicts yours, or one that has already moved on, cannot be accepted. Failing that, the debtor is the final arbiter: whichever copy they honour wins and the other is dead.

## 📳 Installation

<div style="display: flex; justify-content: center; align-items: center; flex-direction: row;">
    <a href="https://github.com/helcel-net/iOweU/releases/latest">
        <img width="200" height="84" alt="APK Download" src=".github/images/apk.png">
    </a>
</div>

## ⚙️ Permissions

- **Location** - only when you redeem an OwU that is tied to a place, to check you are there. Never stored or sent.
- **Camera** - only while scanning a contact's QR code.
- **Nearby devices (Bluetooth)** - while the app is open, to find and talk to other phones running OwU next to you, so that a trade or a redeem can reach you on any screen. It stops the moment the app is not the one in use.

## 📝 Contribute

OwU is a user-driven project. We welcome any contribution, big or small.

- **🖥️ Development:** Fix bugs, implement features, or research issues. Open a PR for review.
- **🍥 Design:** Improve interfaces, including accessibility and usability.
- **📂 Issue Reporting:** Report bugs and edge cases with relevant info.
- **🌍 Localization:** Translate if it doesn't support your language.

## ✏️ Acknowledgements

Thanks to all contributors, the developers of our dependencies, and our users.

## 📝 License

GNU GPL v3 or later. The full text is in [LICENSE](LICENSE).

```
Copyright (C) 2026 Helcel

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```
