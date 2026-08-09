# Server

Everything needed to run online ordering / cloud backup / activation for this
app, in one folder — PHP scripts to upload to your own host, plus standalone
browser tools (no server or build step needed, just open them directly).

| File | Use |
|---|---|
| `pos_online_catalog.php` | Online ordering: catalog upload/fetch, place order, order status/message chat (both directions), nearby-shops directory. See the doc comment at the top of the file for the full API. |
| `pos_backup_sync.php` | Cloud backup: push/pull the backup zip to/from your own server. |
| `pos_customer_app.php` | PHP-based customer ordering web app (PWA) for iOS/Android — an alternative to installing the native customer app. |
| `customer-link-builder.html` | Builds a shop's customer-ordering install link (and QR code) — points at `pos_online_catalog.php`. |
| `key-generator.html` | Computes a customer's activation key from their Device ID. Same HMAC-SHA256 logic as `License.activationKey()` in the app (`app/src/main/java/com/billing/pos/data/License.kt`) — keep the two in sync if the secret ever changes there. |
| `saved-token.txt` | Saved reference value. |

## Keeping the activation secret in sync

`key-generator.html`'s "Secret Key" field defaults to the same value as
`License.SECRET` in `License.kt`. If that constant is ever changed there
(recommended before the app is public — see the comment above it), update
this file's default to match, or activation keys generated here won't match
what the app expects.

## Install (PHP scripts)

1. Upload `pos_online_catalog.php` and `pos_backup_sync.php` to your host.
2. Set each file's `$API_KEY` to a secret of your choosing (blank disables the check).
3. Make sure each file's storage folder is writable by the web server.
4. In the shop owner's app: Settings > Online ordering / Cloud backup, point the
   URLs at wherever you uploaded these files.
