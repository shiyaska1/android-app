# Documentation

Customer-facing material for introducing and demoing the app, in both Malayalam and English.

| File | Use |
|---|---|
| `POS-Billing-Malayalam.pdf` / `.html` | Malayalam introduction + demo guide. Print it, or send it over WhatsApp — the Malayalam font is embedded in the PDF, so it renders correctly on any device. The HTML works offline in any browser. |
| `POS-Billing-English.pdf` / `.html` | Same document in English. |

## Contents

1. **What is this app** — what the app is, at a glance
2. **Who can use it** — the 15 business types it covers
3. **Main features** — features, grouped the same way the app's own dashboard groups them
4. **Modules for specialised businesses** — Lab, Gym, Coaching, Service, Rental, Production, Bulk SMS
5. **Online ordering & the customer app** — catalog upload, customer ordering (app or QR-linked web page), live push notifications, shop↔customer chat, nearby-shop directory, referral links
6. **Other features worth mentioning** — GST/VAT, printing, WhatsApp, backup, OCR, users, stock
7. **How to run a demo** — a step-by-step demo script, each step with a line to say out loud
8. **Pricing** — free 1-month trial, then a one-time licence key (₹3,000 under 100 items, ₹6,000
   for 100+) for the shop owner's app only; a customer ordering from a shop never needs a key
9. **Frequently asked questions** — the questions shop owners usually ask

## Regenerating the PDF

The HTML is the source; the PDF is rendered from it. Malayalam needs a real
Malayalam font installed — the generic `FreeSerif`/`Unifont` fallbacks claim
coverage but break conjuncts (koottaksharam):

```sh
apt-get install -y fonts-smc-meera fonts-smc-manjari

chromium --headless --disable-gpu --no-sandbox \
  --print-to-pdf=docs/POS-Billing-Malayalam.pdf \
  --print-to-pdf-no-header --virtual-time-budget=6000 \
  docs/POS-Billing-Malayalam.html

chromium --headless --disable-gpu --no-sandbox \
  --print-to-pdf=docs/POS-Billing-English.pdf \
  --print-to-pdf-no-header --virtual-time-budget=6000 \
  docs/POS-Billing-English.html
```

Check the result with `pdffonts` — a Malayalam face (e.g. `Meera-Regular`)
must appear in the embedded font list for the Malayalam PDF, otherwise the
text has silently fallen back and the conjuncts will be wrong.

The version number appears twice in each HTML file (masthead and footer);
update all four spots when the app version changes, and keep the two
documents' content in sync when a feature is added or removed.

## Regenerating the Play Store QR code

The QR code in the download box is a base64 PNG embedded directly in the
HTML (`<img src="data:image/png;base64,...">`), so the document stays a
single file. Regenerate it if the Play Store link ever changes:

```sh
pip install qrcode[pil]
python3 -c "
import qrcode, base64
url = 'https://play.google.com/store/apps/details?id=com.billing.pos&pcampaignid=web_share'
img = qrcode.make(url, error_correction=qrcode.constants.ERROR_CORRECT_M)
img.save('play-qr.png')
print(base64.b64encode(open('play-qr.png','rb').read()).decode())
"
```

Paste the printed base64 string in place of the existing one after
`data:image/png;base64,` in the `.cta-qr img` tag.
