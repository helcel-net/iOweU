# Privacy Policy for iOweU

**App:** iOweU (`net.helcel.owu`)
**Developer:** Helcel
**Effective date:** 25 September 2026

## Data collection

iOweU does not collect, transmit, or share any personal or sensitive user data.
The app has no internet access, contains no analytics, advertising, or
crash-reporting libraries, and requires no account.

It asks for three permissions, each only at the moment it is needed:

- **Location**, when an OwU is tied to a place. The reading is compared with
  that place on your device and then discarded. It is not stored, not written
  into the OwU, and not sent anywhere.
- **Camera**, while you scan another person's identity QR code. No image is
  stored.
- **Nearby devices (Bluetooth)**, while the app is open, to find and trade with
  a phone next to you. Bluetooth is never used to work out where you are.

Two phones find each other by advertising a short code (a hash of the public
key, not the key itself) that any phone in range can hear. The exchange
itself goes over a connection to the phone you picked, so only that phone
receives what you trade. It is not encrypted, so treat an OwU's contents as
readable by whoever you are trading with, and by anyone who can listen to
that connection. Nothing else on your device is ever put on the air.

## Data stored on your device

Everything you enter in the app - your OwUs, your address book of contacts'
public keys and the names you give them, and your display preferences - is
saved only in the app's private storage on your device. This data is never
sent to us or to any third party. An OwU is only ever sent to another person
when you choose to share it.

Your signing key is generated on your device and stored in the app's private
storage, sealed with a key held in the Android Keystore - a copy
of the file is useless on any other device.

You can export everything, including that key, to a backup file you choose the
location of (Settings › Backup). That file is encrypted with a passphrase you
pick, and nobody - including us - can open it without that passphrase. It is
also the one thing that can make another phone _be_ you, so keep it as you
would keep a key to your home.

If Android's system backup is enabled on your device, your operating system may
include this data in your device backup. That is handled by Android under your
device vendor's privacy policy, not by iOweU.

## Data sharing

None. No data leaves your device, so there is nothing to share, sell, or
disclose to third parties.

## Data retention and deletion

Your data remains on your device until you delete it. Clearing the app's data
in Android Settings, or uninstalling iOweU, permanently removes everything the
app has stored.

## Children

iOweU is suitable for all ages and collects no data from any user, including
children.

## Security

Because no data is transmitted or stored on any server, there is no remote data
to secure. On-device data is protected by Android's app sandbox.

## Open source

iOweU is released under the GNU General Public License, version 3 or later. The full source is
available at https://github.com/helcel-net/iOweU, so these statements can be
independently verified.

## Changes

Any change affecting privacy will be published in this document before or with
the release that introduces it.

## Contact

Issues: https://github.com/helcel-net/iOweU/issues
