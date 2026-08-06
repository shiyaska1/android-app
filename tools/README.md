# Tools

Standalone browser pages for running the app — open any of these directly in a
browser, no server or build step needed.

| File | Use |
|---|---|
| `key-generator.html` | Computes a customer's activation key from their Device ID. Same HMAC-SHA256 logic as `License.activationKey()` in the app (`app/src/main/java/com/billing/pos/data/License.kt`) — keep the two in sync if the secret ever changes there. |
| `saved-token.txt` | Saved reference value. |

The customer-ordering install link/QR builder (`customer-link-builder.html`)
now lives in `server/`, next to `pos_online_catalog.php` — the server files
and the tool that builds links pointing at them stay in one place.

## Keeping the secret in sync

`key-generator.html`'s "Secret Key" field defaults to the same value as
`License.SECRET` in `License.kt`. If that constant is ever changed there
(recommended before the app is public — see the comment above it), update
this file's default to match, or activation keys generated here won't match
what the app expects.
