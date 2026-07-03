# POS Billing (Native Android, offline)

A fully **native Kotlin + Jetpack Compose** billing/POS app that runs **100% offline**.
All data is stored on-device in a Room (SQLite) database — no internet required.

## Features
- **Billing screen** with auto bill number (`INV-0001`, `INV-0002`, …) and today's date filled automatically.
- **Default "Cash Customer"** selected on every new bill; switch to any other customer from the dropdown.
- **Create a customer on the fly** — the **New** button opens a popup on the billing window.
- **Items with or without tax** — each item has a price and a tax % (0 = without tax).
- **Create items on the fly** — **New item** popup; items add straight to the bill grid.
- **Editable line grid** — increase/decrease quantity, remove lines; totals update live.
- **Payment methods** — Cash, UPI, Card, Credit.
- **Additional charge & discount** fields at the bottom of the bill.
- **Save** the bill to the local database.
- **PDF + Share** — generates an invoice PDF and shares it to any other app (WhatsApp, Gmail, Drive…).
- **Thermal printer (58mm)** — prints an ESC/POS receipt over Bluetooth.
- **Sales report** — Today / This Month / All, with total sales and bill count; tap a bill to re-share its PDF.

## Requirements
- **Android Studio** (Ladybug 2024.2 or newer recommended) — includes the JDK and Android SDK.
- An Android device or emulator running **Android 8.0 (API 26)** or newer.

## Build & run
1. Install **Android Studio** (https://developer.android.com/studio).
2. **File → Open…** and select this `POSBilling` folder.
3. Android Studio will sync Gradle and download everything automatically the first time
   (it also generates the Gradle wrapper if missing — just accept the prompt).
4. Plug in a phone (USB debugging on) or start an emulator, then press **▶ Run**.

## Using a Bluetooth thermal printer
1. Pair the printer once in **Android Settings → Bluetooth**.
2. In the app, tap **Print** — grant the Bluetooth permission when asked.
3. It auto-selects a paired device whose name contains `print`/`pos`/`bt`/`thermal`
   (otherwise the first paired device). Optimised for **58mm / 32-column** printers.

## Customising
- **Shop name** on invoices/receipts: `BillingViewModel.shopName` (and the `"My Shop"` string in
  `ReportsScreen.kt`). Change both to your shop name.
- **Currency**: `util/Format.kt` uses `₹`. Change `rupee()` if needed.
- **Bill number format**: `Repository.nextBillNo()`.

## Project layout
```
app/src/main/java/com/billing/pos/
├─ MainActivity.kt            # Compose entry + navigation (billing ⇄ reports)
├─ data/                      # Room entities, DAOs, database, repository
├─ ui/billing/               # Billing screen, ViewModel, dialogs
├─ ui/reports/               # Sales report screen + ViewModel
├─ pdf/InvoicePdf.kt         # PDF invoice generator (shareable)
├─ print/ThermalPrinter.kt   # ESC/POS Bluetooth thermal printing
└─ util/Format.kt            # money/date/qty formatting
```

Everything works with no network connection.
