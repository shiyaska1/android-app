# Documentation

Customer-facing material for introducing and demoing the app.

| File | Use |
|---|---|
| `POS-Billing-Malayalam.pdf` | Malayalam introduction + 9-step demo guide. Print it, or send it over WhatsApp — the Malayalam font is embedded, so it renders correctly on any device. |
| `POS-Billing-Malayalam.html` | The same document as a standalone web page. Open it in any browser; works offline. |

## Contents

1. **എന്താണ് ഈ ആപ്പ്** — what the app is, at a glance
2. **ആർക്കൊക്കെ ഉപയോഗിക്കാം** — the 15 business types it covers
3. **പ്രധാന സൗകര്യങ്ങൾ** — features, grouped the same way the app's own dashboard groups them
4. **പ്രത്യേക മൊഡ്യൂളുകൾ** — Lab, Gym, Coaching, Service, Rental, Production, Bulk SMS
5. **എടുത്തുപറയേണ്ട സവിശേഷതകൾ** — GST/VAT, printing, WhatsApp, backup, OCR, users, stock
6. **ഡെമോ ചെയ്യുന്ന വിധം** — a 9-step demo script, each step with a line to say out loud
7. **പതിവ് ചോദ്യങ്ങൾ** — the six questions shop owners usually ask

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
```

Check the result with `pdffonts` — a Malayalam face (e.g. `Meera-Regular`)
must appear in the embedded font list, otherwise the text has silently
fallen back and the conjuncts will be wrong.

The version number appears twice in the HTML (masthead and footer); update
both when the app version changes.

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
