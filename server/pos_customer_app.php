<?php
/**
 * POS Billing — customer ordering web app (PHP), for shops whose customers can't use the
 * Android app / Play Store — works in any mobile browser (Android, iPhone, desktop), and can be
 * "installed" as a home-screen icon on both platforms (iPhone: Share > Add to Home Screen;
 * Android: browser menu > Install app / Add to Home Screen).
 *
 * Talks to the SAME server/pos_online_catalog.php endpoint the Android customer app uses — same
 * catalog fetch, same do=order, do=status, do=notifications. No separate backend needed.
 *
 * Single file, like pos_online_catalog.php and pos_backup_sync.php: this one PHP file serves the
 * app page, its manifest, its service worker, and its icons (embedded as base64 below) — nothing
 * else to upload.
 *
 * --- Install ---
 * 1. Upload this file next to pos_online_catalog.php on your server, e.g.:
 *      https://yourdomain.com/pos_customer_app.php
 * 2. Hand each customer a link (or QR code — the Customer Link Builder tool generates this too)
 *    shaped like:
 *      https://yourdomain.com/pos_customer_app.php?shop=<DeviceID>&url=https://yourdomain.com/pos_online_catalog.php?key=YOUR_KEY&type=Restaurant
 *    (shop/url/type/premium mean exactly what they mean in the Android app's referrer link —
 *    shop = your Device ID, url = your pos_online_catalog.php address, type is optional wording
 *    only, premium=1 optionally unlocks the photo-attachment field.)
 * 3. Opening that link works immediately in the browser — no install required. The customer can
 *    also tap "Add to Home Screen" (iPhone) or "Install app" (Android Chrome) to get an icon like
 *    a native app, launching straight back into their shop.
 *
 * --- Notifications ---
 * There's no push server behind this (no FCM/APNs) — the notification bell only checks for
 * updates while the page is open (on load and on manual refresh), unlike the Android app's
 * background AlarmManager poll. Good enough to see status changes when the customer reopens the
 * app; not a substitute for a true push notification if that's needed later.
 */

// ---- icon bytes, base64 (this keeps the whole app to one uploaded file) ----
const ICON_180_B64 = 'iVBORw0KGgoAAAANSUhEUgAAALQAAAC0CAYAAAA9zQYyAAADFklEQVR4nO3dXW7aQBhAUai6zHQB6YK6gS6UPiFVtAEbj3+4nPOaKB6Zqw9jrMn59PlxOUHEt70XACMJmhRBkyJoUgRNiqBJETQpgiZF0KQImhRBkyJoUgRNiqBJETQpgiZF0KQImhRBkyJoUgRNiqBJETQpgiZF0KQImhRBkyJoUgRNiqBJETQpgiZF0KQImhRBkyJoUgRNiqBJETQpgibl+94LKLr8+j3p984/f6y8kvdz9p9kl5sa8CMCX07QC4wK+ZawnyfoJ6wV8i1hz+dD4Uxbxbz1sSpM6In2jsu0nkbQE8yNeWp8a/3ddyboB7a6BedW3xiCvmNKZKMD2+OYJT4ULrBGWGJdxoT+wr1JuVV0R1jDqzGh/+MoId071t53XY5K0KQI+sZRpvOUY5rS/xL0RHtes7penk7QpAj6L6/4Fv6Ka16ToCc4wlv+EdbwCgRNiqBJ8U0hKSY0KYImRdCkCJoUQZMiaFIETYqgSRE0KYImJbedrscp56k9xZd6lkPMz6uEnbnkEPMylfOXCLryYuytcB4TQcOVoEkRNCmCJkXQpAiaFEGTImhSBE2KoEkRNCmCJkXQpAiaFEGTImhSBE2KoEkRNCmCJkXQpAiaFEGTImhSBE2KoEkRNCm57XSP5KsdPQt7yB2VoFfwaGva68+FPZ5LjsHm7LNc2ZP5SAQ90DOBinosQQ+yJExRjyNoUgQ9wIgJa0qPIWhSBE2KoEkRNCmCHmDEN36+NRxD0KQIepAlE9Z0HkfQAz0TppjHEvRgcwIV83geH13BNVTPQ29P0CsS7vZccpAiaFIETYqgSRE0KYImRdCkCJoUQZMiaFIETYqgSRE0KYImRdCkCJoUQZMiaFIETYqgSUkEbW/lMQrnMRE0XGWCLkyXPVXO3/n0+XHZexEj2QtjnkrIV7mgeW+ZSw44nQRNjKBJETQpgiZF0KQImhRBkyJoUgRNiqBJETQpgiZF0KQImhRBkyJoUgRNiqBJETQpgiZF0KQImhRBkyJoUgRNiqBJETQpgiZF0KQImhRBkyJoUgRNyh8Q/5Qk/E8qoQAAAABJRU5ErkJggg==';
const ICON_192_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAYAAABS3GwHAAADZklEQVR4nO3dUXKaUBiAUex0mekC0gV1A12offKhnamicoHc75zXZMKN+T8ginJZPj+uC0R9O3oBcCQBkCYA0gRAmgBIEwBpAiBNAKQJgDQBkCYA0gRAmgBIEwBpAiBNAKQJgDQBkCYA0gRAmgBIEwBpAiBNAKQJgDQBkCYA0gRAmgBIEwBpAiBNAKQJgDQBkCYA0gRAmgBIEwBpAiBNAKQJgDQBkCYA0r4fvYCS66/fq77v8vPH4JVwc1k+P65HL2JWawf+EUGMI4CNbTX0/yOGbQlgI6MH/19C2IZ/gjew9/Aftc0ZOQK84SxD6GjwOgG86JXhXzuoI382fxPAC54Z0HcHc89tFQngSUc9l+81hDEE8IQ1Qzh6AM+whpl4JXgjew3dbTtn+Qf8q/M06Er3Bu6IPe69bYpjPQGscLbhX7NtEawjANIE8MBZ9/5r1uAo8JgAXnSG4b8501q+GgGQJoA7ZjiFmOF3GEkALzjjKccZ1/QVCIA0AZAmANJcDEeaIwBpAiBNAKQJgDQBkCYA0gRAmgBIEwBpAiBt+o9FcT38+2a+1Hraa4EM/vZmDGHKUyDDP8aMj+uUAcBa0wUw417qTGZ7fKcLAJ4hANIEQJoASBMAaQIgTQCkCYA0AZAmANIEQJoASBMAaQIgTQCkCYA0AZAmANIEQJoASBMAaQIgTQCkCYA0AZAmANIEQJoASJv+Bhln9L/P2Z/tg2e/AgHs6NENJm5fF8J+nALt5Jm7q8x4J5azEsAOXhloEexDAIO9M8giGE8ApAlgoC324I4CYwmANAGQJgDSBECaAEgTwEBbXNLgsoixBECaAAZ7Zw9u7z+eAHbwyiAb/n0IYCfPDLTh34/3A+zoNtjeEHMeAjiAQT8Pp0CkCYA0AZAmANIEQJoASBMAaQIgTQCkCYA0AZAmANIEQJoASBMAaQIgTQCkCYA0AZAmANIEQJoASJsuALcUGmu2x3e6ABhntuFflkkDmPEPdbRZH9PL8vlxPXoRI/kUtvfNOvzLEggA7pnyFAjWEgBpAiBNAKQJgDQBkCYA0gRAmgBIEwBpAiBNAKQJgDQBkCYA0gRAmgBIEwBpAiBNAKQJgDQBkCYA0gRAmgBIEwBpAiBNAKQJgDQBkCYA0gRAmgBIEwBpAiBNAKT9AZO8nT5X4VLhAAAAAElFTkSuQmCC';
const ICON_512_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAgAAAAIACAYAAAD0eNT6AAALY0lEQVR4nO3dXVbbyBpAUdyLYcIAYEBMgIG6H7qzbi7pENnWT5XO3s+JKYrI31HJSS5Pby/XJwAg5a+jFwAA7E8AAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCno9eALCN68fnKq9zeX9d5XWAsVye3l6uRy8CuN9ag/5WwgDmJgBgMkcN/D8RBDAXAQCDG3Xg/4kggLEJABjUrIP/KyEAYxIAMJCzDP3fEQMwDgEAAzj74P9KCMDxBAAcqDb4vxICcBwBAAeoD/6vhADsTwDAjgz+7wkB2I8AgB0Y/LcRArA9/xcAbMzwv509g+05AYCNGGLrcBoA23ACABsw/NdjL2EbAgBWZmCtz57C+jwCgJUYUvvwSADW4QQAVmD478dewzqcAMCDRh5Ij94tn/l7gzoBAA8YaUDuNRCL3zOckQCAOx09CEcZfvYB5iQA4A5HDb3Rh519gXkIALjREUNutgFnj2B8AgBusOdgO8tAs2cwJgEAC+01yM46xOwfjMW/AwALGF6PK/4tBRjZ89ELAM49+H/24/s0pOF4HgHAH2w5rCqD/3fsLRzHIwD4hgG1rS33wCkDfE8AwG8Y/vsQAXAMAQA7M/x/ZU9gfz4DAP9hiztHQ24Zew/7cAIAOzCAlrNXsA8BAF94bnw+fqbwKwEAG3NHezt7BtsTAPCTte8UDbL7rb13TgHg/wkA2Ijh/zh7CNsRAPCvNe8QDa71rLmXTgHgfwQAAAQJAHhy9z86pwCwPgEAKzL8t2NvYV0CAACCBAB5ax0Ju0Pd3lp77DEACAAASBIAsAJ3//ux17AOAUCao+AuP3vqBAAABAkAeJAj6f3Zc3icAACAIAEAAEECgCwfAsOfAcoEADzAs+jj2Ht4jAAAgCABAABBAgAAggQAAAQJAAAIujy9vVyPXgQAsC8nAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCno9eAOd0/fg8eglwKpf316OXwMlcnt5erkcvgnMw9GEfYoA1CAAeZvDDMYQAj/AZAB5i+MNxXH88QgBwN28+cDzXIfcSANzFmw6Mw/XIPQQAN/NmA+NxXXIrAcBNvMnAuFyf3EIAAECQAGAxdxcwPtcpSwkAAAgSAAAQJABYxLEizMP1yhICAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQ9Hz0AoB9XN5fF/2668fnxisBRiAA4MSWDv3f/R4xAOclAOBk7hn6S15LDMC5+AwAnMiaw3/P1wb25wQATmCv4fzj6zgNgPk5AYDJHXFn7jQA5icAYGJHDmIRAHMTADCpEQbwCGsA7iMAYEIjDd6R1gIsJwBgMiMO3BHXBHxPAMBERh60I68N+JUAgEnMMGBnWCPwDwEAAEECACYw0531TGuFMgEAAEECAAY34x31jGuGGgEAAEECAACCBAAABAkAGNjMz9JnXjsUCAAACBIAABAkAAAgSAAAQJAAAIAgAQAAQQIAAIIEAAAECQAY2PXj8+gl3G3mtUOBAACAIAEAAEECAACCBAAMbsZn6TOuGWoEAAAECQCYwEx31DOtFcoEAAAECQCYxAx31jOsEfiHAICJjDxgR14b8CsBAJMZcdCOuCbgewIAJjTSwB1pLcByAgAmNcLgHWENwH0EAEzsyAFs+MPcBABM7ohBbPjD/J6PXgDwuB8D+fL+usvXAebnBABOZMsBbfjDuTgBgJP5eVA/eiJg6MN5CQA4sXtiwNCHBgEAEQY78DOfAQCAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAHAIpf316OXACzkemUJAQAAQQIAAIIEAIs5VoTxuU5ZSgAAnIThzy0EADfxBgNwDgKAm4kAGI/rklsJAO7izQbG4XrkHgKAu3nTgeO5DrnX5ent5Xr0Ipjb9ePz6CVAjsHPowQAqxECsA/DnzUIADYhBmBdhj5rEwAAEORDgAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAIAEAAEECAACCBAAABAkAAAgSAAAQJAAAIEgAAECQAACAoL8BFdWKPQW0r1oAAAAASUVORK5CYII=';

$app = isset($_GET['app']) ? (string) $_GET['app'] : '';

if ($app === 'icon') {
    $size = isset($_GET['size']) ? (string) $_GET['size'] : '192';
    $data = $size === '180' ? ICON_180_B64 : ($size === '512' ? ICON_512_B64 : ICON_192_B64);
    header('Content-Type: image/png');
    header('Cache-Control: public, max-age=604800, immutable');
    echo base64_decode($data);
    exit;
}

if ($app === 'manifest') {
    // The shop's link params are folded into start_url, so relaunching from the home-screen
    // icon (which reopens exactly this start_url) still knows which shop it's for.
    $params = array();
    foreach (array('shop', 'url', 'type', 'premium') as $key) {
        if (isset($_GET[$key]) && $_GET[$key] !== '') {
            $params[$key] = (string) $_GET[$key];
        }
    }
    $qs = http_build_query($params);
    $manifest = array(
        'name' => 'Shop Order',
        'short_name' => 'Order',
        'start_url' => $qs !== '' ? ('./?' . $qs) : './',
        'scope' => './',
        'display' => 'standalone',
        'background_color' => '#FFFBFE',
        'theme_color' => '#00695C',
        'icons' => array(
            array('src' => '?app=icon&size=192', 'sizes' => '192x192', 'type' => 'image/png'),
            array('src' => '?app=icon&size=512', 'sizes' => '512x512', 'type' => 'image/png'),
        )
    );
    header('Content-Type: application/manifest+json');
    echo json_encode($manifest);
    exit;
}

if ($app === 'sw') {
    header('Content-Type: application/javascript');
    echo <<<'SWJS'
const SHELL_CACHE = 'pos-customer-shell-v1';

self.addEventListener('install', (e) => {
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  self.clients.claim();
});

// Network-first: the catalog/orders/notifications endpoints always need fresh data. Only falls
// back to a cached copy of the app shell itself when there's no connection at all, so the app
// still opens offline (even if it can't show live items or place an order).
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  event.respondWith(
    fetch(event.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(SHELL_CACHE).then((c) => c.put(event.request, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(event.request))
  );
});

SWJS;
    exit;
}

header('Content-Type: text/html; charset=utf-8');
echo <<<'APPHTML'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Shop Order</title>
<meta name="theme-color" content="#00695C">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="Order">
<link rel="apple-touch-icon" href="?app=icon&size=180">
<link rel="icon" href="?app=icon&size=192">
<style>
  :root { --brand:#00695C; --bg:#FFFBFE; --card:#FFFFFF; --outline:#79747E; --err:#B3261E; }
  * { box-sizing: border-box; }
  body { margin:0; font-family: -apple-system, BlinkMacSystemFont, Roboto, Segoe UI, Arial, sans-serif; background:var(--bg); color:#1C1B1F; }
  header { position:sticky; top:0; z-index:10; background:var(--brand); color:#fff; padding:12px 14px; display:flex; align-items:center; justify-content:space-between; gap:8px; }
  header h1 { font-size:17px; margin:0; font-weight:600; flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .icon-btn { background:none; border:none; color:#fff; font-size:20px; padding:6px; cursor:pointer; position:relative; line-height:1; }
  .badge { position:absolute; top:0; right:0; background:#fff; color:var(--brand); border-radius:8px; font-size:10px; font-weight:700; padding:1px 4px; min-width:14px; text-align:center; }
  main { padding:10px 12px 96px; max-width:640px; margin:0 auto; }
  .updated { font-size:12px; color:var(--outline); padding:2px 2px 8px; }
  input[type=text], input[type=tel], textarea { width:100%; padding:10px 12px; border:1px solid #CAC4D0; border-radius:8px; font-size:15px; font-family:inherit; background:#fff; }
  .search { margin-bottom:10px; }
  .chips { display:flex; gap:8px; overflow-x:auto; padding-bottom:10px; }
  .chip { flex:0 0 auto; border:1px solid #CAC4D0; border-radius:16px; padding:6px 14px; font-size:13px; background:#fff; cursor:pointer; white-space:nowrap; }
  .chip.active { background:var(--brand); color:#fff; border-color:var(--brand); }
  .item { display:flex; align-items:center; gap:12px; background:var(--card); border-radius:12px; padding:12px; margin-bottom:8px; box-shadow:0 1px 2px rgba(0,0,0,.08); }
  .item img { width:52px; height:52px; border-radius:8px; object-fit:cover; flex:0 0 auto; }
  .item .info { flex:1; min-width:0; }
  .item .name { font-weight:600; font-size:14px; }
  .item .desc { font-size:12px; color:var(--outline); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .item .price { font-weight:700; font-size:14px; margin-top:2px; }
  .qty { display:flex; align-items:center; gap:8px; flex:0 0 auto; }
  .qty button { width:30px; height:30px; border-radius:50%; border:1px solid var(--brand); background:#fff; color:var(--brand); font-size:16px; cursor:pointer; }
  .qty button:disabled { opacity:.35; }
  .qty span { min-width:18px; text-align:center; font-weight:600; }
  .empty { text-align:center; color:var(--outline); padding:40px 20px; }
  .bottombar { position:fixed; left:0; right:0; bottom:0; background:#fff; box-shadow:0 -2px 10px rgba(0,0,0,.1); padding:10px 14px; display:none; align-items:center; justify-content:space-between; gap:10px; }
  .bottombar .total b { display:block; font-size:15px; }
  .bottombar .total span { font-size:11px; color:var(--outline); }
  .bottombar .actions { display:flex; gap:8px; }
  button.pill { border:none; border-radius:20px; padding:10px 16px; font-size:14px; font-weight:600; cursor:pointer; }
  button.primary { background:var(--brand); color:#fff; }
  button.outline { background:#fff; color:var(--brand); border:1px solid var(--brand); }
  button.pill:disabled { opacity:.5; }
  .overlay { position:fixed; inset:0; background:rgba(0,0,0,.5); display:none; align-items:flex-end; justify-content:center; z-index:50; }
  .overlay.show { display:flex; }
  .sheet { background:#fff; width:100%; max-width:560px; max-height:86vh; overflow-y:auto; border-radius:16px 16px 0 0; padding:18px; }
  .sheet h2 { margin:0 0 4px; font-size:18px; }
  .sheet .hint { font-size:12px; color:var(--outline); margin-bottom:12px; }
  .field { margin-top:10px; }
  .field label { font-size:12px; color:var(--outline); display:block; margin-bottom:4px; }
  .sheet-actions { display:flex; gap:10px; margin-top:16px; }
  .sheet-actions button { flex:1; padding:12px; }
  .hist-row, .notif-row { border-bottom:1px solid #eee; padding:10px 0; }
  .hist-row .top, .notif-row .top { display:flex; justify-content:space-between; align-items:center; }
  .hist-row .date, .notif-row .date { font-size:11px; color:var(--outline); }
  .hist-row .line { font-size:13px; }
  .status-label { font-weight:600; font-size:13px; }
  .msg { font-size:13px; margin-top:2px; }
  .recent-btn { display:block; width:100%; text-align:left; background:none; border:none; padding:10px 4px; font-size:14px; color:var(--brand); border-bottom:1px solid #eee; cursor:pointer; }
  .err { color:var(--err); font-size:12px; margin-top:6px; }
  .toast { position:fixed; left:50%; bottom:100px; transform:translateX(-50%); background:#333; color:#fff; padding:10px 16px; border-radius:8px; font-size:13px; z-index:99; display:none; max-width:80%; text-align:center; }
  .onboard { max-width:420px; margin:60px auto; padding:24px; text-align:center; }
  .onboard h1 { font-size:20px; }
  .onboard p { color:var(--outline); font-size:14px; }
  .divider { border:none; border-top:1px solid #eee; margin:14px 0; }
  .attach-btn { width:100%; padding:10px; border-radius:8px; border:1px solid var(--brand); background:#fff; color:var(--brand); font-size:14px; margin-top:10px; cursor:pointer; }
</style>
</head>
<body>

<div id="onboarding" class="onboard" style="display:none;">
  <h1>Connect to a shop</h1>
  <p>Open the link your shop shared with you, or paste it below.</p>
  <input type="text" id="onboardInput" placeholder="Paste your shop's link here">
  <button class="pill primary" style="margin-top:12px;width:100%;" onclick="onboardConnect()">Connect</button>
  <div class="err" id="onboardErr"></div>
</div>

<div id="app" style="display:none;">
  <header>
    <h1 id="shopTitle">Order</h1>
    <button class="icon-btn" id="btnChat" onclick="messageShop()" title="Message shop">&#128172;</button>
    <button class="icon-btn" onclick="openNotifications()" title="Notifications">&#128276;<span class="badge" id="notifBadge" style="display:none;">0</span></button>
    <button class="icon-btn" onclick="openHistory()" title="Order history">&#128337;</button>
    <button class="icon-btn" onclick="openSwitch()" title="Switch shop">&#8646;</button>
    <button class="icon-btn" onclick="doRefresh()" title="Refresh">&#8635;</button>
  </header>
  <main>
    <div class="updated" id="updatedAt"></div>
    <input type="text" class="search" id="search" placeholder="Search items…" oninput="render()">
    <div class="chips" id="chips"></div>
    <div id="itemList"></div>
  </main>
  <div class="bottombar" id="bottombar">
    <div class="total"><b id="totalAmt">₹0</b><span id="totalCount">0 item(s)</span></div>
    <div class="actions">
      <button class="pill outline" onclick="onShareClick()" id="shareBtn">Share</button>
      <button class="pill primary" onclick="onSaveClick()">Save</button>
    </div>
  </div>
</div>

<div class="overlay" id="saveOverlay"><div class="sheet">
  <h2>Your details</h2>
  <div class="hint">So the shop knows who ordered and can reach you.</div>
  <div class="field"><label>Your name</label><input type="text" id="custName"></div>
  <div class="field"><label>Mobile number</label><input type="tel" id="custPhone" inputmode="numeric"></div>
  <div class="field"><label>Address (optional)</label><input type="text" id="custAddress"></div>
  <div class="field"><label>Note (optional)</label><textarea id="custNote" rows="2"></textarea></div>
  <div id="attachWrap" style="display:none;">
    <button class="attach-btn" onclick="document.getElementById('attachInput').click()" id="attachBtn">Attach a photo (e.g. prescription)</button>
    <input type="file" accept="image/*" id="attachInput" style="display:none;" onchange="onAttachPicked(event)">
  </div>
  <div class="err" id="saveErr"></div>
  <div class="sheet-actions">
    <button class="pill outline" onclick="closeOverlay('saveOverlay')">Cancel</button>
    <button class="pill primary" id="saveConfirmBtn" onclick="confirmSave()">Save order</button>
  </div>
</div></div>

<div class="overlay" id="switchOverlay"><div class="sheet">
  <h2>Switch shop</h2>
  <div class="hint">Paste a different shop's link to switch — no reinstall needed.</div>
  <input type="text" id="switchInput" placeholder="Paste the new shop's link">
  <div class="err" id="switchErr"></div>
  <div id="recentList"></div>
  <div class="sheet-actions">
    <button class="pill outline" onclick="closeOverlay('switchOverlay')">Cancel</button>
    <button class="pill primary" onclick="confirmSwitch()">Switch</button>
  </div>
</div></div>

<div class="overlay" id="historyOverlay"><div class="sheet">
  <h2>Order history</h2>
  <div id="historyList"></div>
  <div class="sheet-actions"><button class="pill primary" style="flex:none;width:100%;" onclick="closeOverlay('historyOverlay')">Close</button></div>
</div></div>

<div class="overlay" id="notifOverlay"><div class="sheet">
  <h2>Notifications</h2>
  <div id="notifList"></div>
  <div class="sheet-actions"><button class="pill primary" style="flex:none;width:100%;" onclick="closeOverlay('notifOverlay')">Close</button></div>
</div></div>

<div class="toast" id="toast"></div>

<script>
'use strict';
var cfg = null;
var items = [];
var cart = {};
var selectedCategory = 'All';
var pendingAttachment = null;

function toast(msg){ var t=document.getElementById('toast'); t.textContent=msg; t.style.display='block'; clearTimeout(window.__toastT); window.__toastT=setTimeout(function(){t.style.display='none';}, 2600); }
function money(n){ return (Math.round((n||0)*100)/100).toFixed(2); }
function esc(s){ var d=document.createElement('div'); d.textContent = s==null?'':String(s); return d.innerHTML; }

function loadJSON(key, fallback){ try{ var v = localStorage.getItem(key); return v ? JSON.parse(v) : fallback; }catch(e){ return fallback; } }
function saveJSON(key, val){ try{ localStorage.setItem(key, JSON.stringify(val)); }catch(e){} }
function ns(){ return 'posc_' + cfg.shop + '_'; }

function paramsFromSearch(search){
  var p = new URLSearchParams(search);
  var shop = p.get('shop'), url = p.get('url');
  if(!shop || !url) return null;
  return { shop:shop, url:url, type:p.get('type')||'', premium:(p.get('premium')==='1'||p.get('premium')==='true') };
}

function rememberRecent(shopCfg){
  var list = loadJSON('posc_recent', []);
  list = list.filter(function(s){ return s.shop!==shopCfg.shop; });
  list.unshift(shopCfg);
  saveJSON('posc_recent', list.slice(0,5));
}
function removeRecent(shop){
  var list = loadJSON('posc_recent', []).filter(function(s){ return s.shop!==shop; });
  saveJSON('posc_recent', list);
}

function currentConfig(){
  var fromUrl = paramsFromSearch(location.search);
  if(fromUrl){
    var prev = loadJSON('posc_current', null);
    if(prev && prev.shop && prev.shop !== fromUrl.shop) rememberRecent(prev);
    removeRecent(fromUrl.shop);
    saveJSON('posc_current', fromUrl);
    return fromUrl;
  }
  return loadJSON('posc_current', null);
}

function parseShopLink(text){
  text = (text||'').trim();
  if(!text) return null;
  var url;
  try { url = new URL(text); } catch(e){
    try { url = new URL('https://x/?' + text); } catch(e2){ return null; }
  }
  var sp = url.searchParams;
  if(sp.get('referrer')){
    try { sp = new URLSearchParams(sp.get('referrer')); } catch(e){}
  }
  var shop = sp.get('shop'), apiUrl = sp.get('url');
  if(!shop || !apiUrl) return null;
  return { shop:shop, url:apiUrl, type:sp.get('type')||'', premium:(sp.get('premium')==='1'||sp.get('premium')==='true') };
}
function goToShop(c){
  var qs = new URLSearchParams({shop:c.shop, url:c.url, type:c.type||'', premium:c.premium?'1':'0'});
  location.href = location.pathname + '?' + qs.toString();
}

function sepFor(u){ return u.indexOf('?')>=0 ? '&' : '?'; }

function fetchCatalog(){
  return fetch(cfg.url + sepFor(cfg.url) + 'shop=' + encodeURIComponent(cfg.shop))
    .then(function(res){ if(!res.ok) throw new Error('HTTP '+res.status); return res.text(); })
    .then(function(text){
      var root; try{ root = JSON.parse(text); }catch(e){ throw new Error('Bad response'); }
      var list, shopName='', shopPhone='';
      if(Array.isArray(root)){ list = root; }
      else { list = root.items || []; shopName = root.shopName||''; shopPhone = root.shopPhone||''; }
      items = list.map(function(o){ return { id:String(o.id||''), name:o.name||'', category:o.category||'', price:parseFloat(o.price||0), unit:o.unit||'', imageUrl:o.imageUrl||'', description:o.description||'' }; }).filter(function(i){ return i.id && i.name; });
      if(shopName) saveJSON(ns()+'shopName', shopName);
      if(shopPhone) saveJSON(ns()+'shopPhone', shopPhone);
      saveJSON(ns()+'items', items);
      saveJSON(ns()+'lastFetch', Date.now());
    });
}

function submitOrder(selection, customer, note, attachmentImage, locationLink){
  var total = selection.reduce(function(s,l){ return s + l.price*l.qty; }, 0);
  var body = { shop: cfg.shop, customerName: customer.name, customerPhone: customer.phone, total: total,
    items: selection.map(function(l){ return {id:l.id, name:l.name, qty:l.qty, price:l.price}; }) };
  if(locationLink) body.location = locationLink;
  if(customer.address) body.customerAddress = customer.address;
  if(note) body.note = note;
  if(attachmentImage) body.attachmentImage = attachmentImage;
  return fetch(cfg.url + sepFor(cfg.url) + 'do=order', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body) })
    .then(function(res){ if(!res.ok) throw new Error('HTTP '+res.status); return res.json().catch(function(){ return {}; }); })
    .then(function(j){ return j.id || ''; });
}

function fetchNotifications(){
  var phone = (loadJSON(ns()+'customer', {}) || {}).phone;
  if(!phone) return Promise.resolve([]);
  return fetch(cfg.url + sepFor(cfg.url) + 'do=notifications&shop=' + encodeURIComponent(cfg.shop) + '&phone=' + encodeURIComponent(phone))
    .then(function(res){ return res.ok ? res.json().catch(function(){return [];}) : []; })
    .catch(function(){ return []; });
}

function getLocationLink(){
  return new Promise(function(resolve){
    if(!navigator.geolocation) return resolve(null);
    var done = false;
    var timer = setTimeout(function(){ if(!done){ done=true; resolve(null); } }, 8000);
    navigator.geolocation.getCurrentPosition(
      function(pos){ if(done) return; done=true; clearTimeout(timer); resolve('https://maps.google.com/?q='+pos.coords.latitude+','+pos.coords.longitude); },
      function(){ if(done) return; done=true; clearTimeout(timer); resolve(null); },
      { timeout: 8000 }
    );
  });
}

function compressImage(file, maxBytes){
  return new Promise(function(resolve, reject){
    var reader = new FileReader();
    reader.onload = function(){
      var img = new Image();
      img.onload = function(){
        var dim = 240;
        var scale = Math.min(1, dim / Math.max(img.width, img.height));
        var w = Math.max(1, Math.round(img.width*scale)), h = Math.max(1, Math.round(img.height*scale));
        var canvas = document.createElement('canvas');
        var ctx = canvas.getContext('2d');
        function encodeAt(w,h,q){ canvas.width=w; canvas.height=h; ctx.drawImage(img,0,0,w,h); return canvas.toDataURL('image/jpeg', q); }
        function sizeOf(uri){ return Math.round((uri.length - uri.indexOf(',') - 1) * 3/4); }
        var dataUri = encodeAt(w,h,0.82);
        if(sizeOf(dataUri) > maxBytes){
          var qualities = [0.7,0.55,0.4];
          for(var i=0;i<qualities.length;i++){ dataUri = encodeAt(w,h,qualities[i]); if(sizeOf(dataUri)<=maxBytes) break; }
        }
        var tries=0;
        while(sizeOf(dataUri) > maxBytes && tries < 6 && Math.min(w,h) > 40){
          w = Math.round(w*0.75); h = Math.round(h*0.75);
          dataUri = encodeAt(w,h,0.55);
          tries++;
        }
        resolve(dataUri);
      };
      img.onerror = function(){ reject(new Error('bad image')); };
      img.src = reader.result;
    };
    reader.onerror = function(){ reject(new Error('read failed')); };
    reader.readAsDataURL(file);
  });
}

// ---- UI ----
function catalogLabel(){
  var t = cfg.type;
  if(t==='Medical store') return 'Medicines';
  if(t==='Medical lab') return 'Home Collection';
  return 'Order';
}
function shareLabel(){
  var t = cfg.type;
  if(t==='Medical store') return 'Send order';
  if(t==='Medical lab') return 'Book collection';
  return 'Share order';
}

function render(){
  var shopName = loadJSON(ns()+'shopName','');
  document.getElementById('shopTitle').textContent = shopName || catalogLabel();
  var shopPhone = loadJSON(ns()+'shopPhone','');
  document.getElementById('btnChat').style.display = shopPhone ? '' : 'none';

  var cats = ['All'];
  items.forEach(function(i){ if(i.category && cats.indexOf(i.category)<0) cats.push(i.category); });
  var chipsEl = document.getElementById('chips');
  if(cats.length>1){
    chipsEl.style.display='flex';
    chipsEl.innerHTML = cats.map(function(c){ return '<div class="chip'+(c===selectedCategory?' active':'')+'" onclick="setCategory(\''+esc(c).replace(/'/g,"\\'")+'\')">'+esc(c)+'</div>'; }).join('');
  } else { chipsEl.style.display='none'; chipsEl.innerHTML=''; }

  var q = (document.getElementById('search').value||'').toLowerCase();
  var shown = items.filter(function(i){
    return (selectedCategory==='All' || i.category===selectedCategory) && (!q || i.name.toLowerCase().indexOf(q)>=0);
  });

  var listEl = document.getElementById('itemList');
  if(items.length===0){
    listEl.innerHTML = '<div class="empty">No items yet — tap refresh</div>';
  } else if(shown.length===0){
    listEl.innerHTML = '<div class="empty">No items match your search</div>';
  } else {
    listEl.innerHTML = shown.map(function(i){
      var qty = cart[i.id]||0;
      var img = i.imageUrl && i.imageUrl.indexOf('data:image')===0 ? '<img src="'+i.imageUrl+'">' : '';
      return '<div class="item">'+img+
        '<div class="info"><div class="name">'+esc(i.name)+'</div>'+
        (i.description?'<div class="desc">'+esc(i.description)+'</div>':'')+
        '<div class="price">\u20B9'+money(i.price)+(i.unit?' / '+esc(i.unit):'')+'</div></div>'+
        '<div class="qty"><button onclick="setQty(\''+i.id+'\','+(qty-1)+')" '+(qty<=0?'disabled':'')+'>&minus;</button><span>'+qty+'</span><button onclick="setQty(\''+i.id+'\','+(qty+1)+')">+</button></div>'+
        '</div>';
    }).join('');
  }

  var count=0, total=0;
  Object.keys(cart).forEach(function(id){
    var it = items.find(function(x){return x.id===id;});
    if(it && cart[id]>0){ count += cart[id]; total += it.price*cart[id]; }
  });
  var bar = document.getElementById('bottombar');
  if(count>0){
    bar.style.display='flex';
    document.getElementById('totalAmt').textContent = '\u20B9'+money(total);
    document.getElementById('totalCount').textContent = count+' item(s)';
  } else { bar.style.display='none'; }

  var updated = loadJSON(ns()+'lastFetch', 0);
  document.getElementById('updatedAt').textContent = updated ? ('Updated ' + new Date(updated).toLocaleString()) : '';

  updateNotifBadge();
}

function setCategory(c){ selectedCategory=c; render(); }
function setQty(id, qty){ if(qty<=0){ delete cart[id]; } else { cart[id]=qty; } render(); }
function clearCart(){ cart={}; render(); }

function messageShop(){
  var phone = (loadJSON(ns()+'shopPhone','')||'').replace(/\D/g,'');
  var msg = encodeURIComponent("Hi, I'd like to place an order.");
  window.open('https://wa.me/'+phone+'?text='+msg, '_blank');
}

function currentSelection(){
  return Object.keys(cart).filter(function(id){return cart[id]>0;}).map(function(id){
    var it = items.find(function(x){return x.id===id;});
    return it ? { id: it.id, name: it.name, price: it.price, qty: cart[id] } : null;
  }).filter(Boolean);
}

// Share is Save with an extra step: it goes through the exact same registration + compulsory
// location + server-save pipeline as Save, then additionally opens WhatsApp with the order text
// once the save succeeds. pendingShare tracks which button started the current submit.
var pendingShare = false;

function openOverlay(id){ document.getElementById(id).classList.add('show'); }
function closeOverlay(id){ document.getElementById(id).classList.remove('show'); }

function onSaveClick(){ pendingShare = false; openSave(); }

function onShareClick(){
  var sel = currentSelection();
  if(sel.length===0) return;
  pendingShare = true;
  var c = loadJSON(ns()+'customer', {name:'',phone:'',address:''});
  if(c.name && c.phone){
    doSubmitOrder({name:c.name, phone:c.phone, address:c.address||''}, '', null, false);
  } else {
    openSave();
  }
}

function openSave(){
  var sel = currentSelection();
  if(sel.length===0) return;
  var c = loadJSON(ns()+'customer', {name:'',phone:'',address:''});
  document.getElementById('custName').value = c.name||'';
  document.getElementById('custPhone').value = c.phone||'';
  document.getElementById('custAddress').value = c.address||'';
  document.getElementById('custNote').value = '';
  document.getElementById('saveErr').textContent = '';
  pendingAttachment = null;
  document.getElementById('attachWrap').style.display = cfg.premium ? '' : 'none';
  document.getElementById('attachBtn').textContent = 'Attach a photo (e.g. prescription)';
  document.getElementById('saveConfirmBtn').textContent = pendingShare ? 'Save & share' : 'Save order';
  openOverlay('saveOverlay');
}

function onAttachPicked(e){
  var file = e.target.files && e.target.files[0];
  if(!file) return;
  document.getElementById('attachBtn').textContent = 'Compressing\u2026';
  compressImage(file, 40*1024).then(function(dataUri){
    pendingAttachment = dataUri;
    document.getElementById('attachBtn').textContent = 'Photo attached \u2014 tap to change';
  }).catch(function(){
    document.getElementById('attachBtn').textContent = 'Could not read photo \u2014 tap to retry';
  });
}

function confirmSave(){
  var name = document.getElementById('custName').value.trim();
  var phone = document.getElementById('custPhone').value.trim();
  var address = document.getElementById('custAddress').value.trim();
  var note = document.getElementById('custNote').value.trim();
  var errEl = document.getElementById('saveErr');
  if(!name || !phone){ errEl.textContent = 'Name and mobile number are required.'; return; }
  saveJSON(ns()+'customer', {name:name, phone:phone, address:address});
  doSubmitOrder({name:name, phone:phone, address:address}, note, pendingAttachment, true);
}

// Location is compulsory: the shop needs to know where to deliver, so a missing/failed fix
// fails the whole order instead of silently going through without one. Calling
// getCurrentPosition() is itself what triggers the browser's location-permission prompt.
function doSubmitOrder(customer, note, attachment, fromDialog){
  var sel = currentSelection();
  if(sel.length===0){ pendingShare = false; if(fromDialog) closeOverlay('saveOverlay'); return; }
  var errEl = document.getElementById('saveErr');
  var btn = document.getElementById('saveConfirmBtn');
  if(fromDialog){ btn.disabled = true; btn.textContent = 'Saving\u2026'; }
  getLocationLink().then(function(loc){
    if(!loc){
      var msg = 'Could not get your location \u2014 turn on Location and try again';
      if(fromDialog){ errEl.textContent = msg; } else { toast(msg); }
      return null;
    }
    return submitOrder(sel, customer, note, attachment, loc).then(function(orderId){
      var total = sel.reduce(function(s,l){return s+l.price*l.qty;},0);
      var hist = loadJSON(ns()+'history', []);
      hist.unshift({ itemsJson: JSON.stringify(sel.map(function(l){return {serverId:l.id,name:l.name,qty:l.qty,price:l.price};})), total: total, placedAt: Date.now(), location: loc, serverId: orderId||'' });
      saveJSON(ns()+'history', hist.slice(0,50));
      if(pendingShare){
        var lines = sel.map(function(l){ return '- '+l.name+' x'+l.qty+' = \u20B9'+money(l.price*l.qty); });
        var text = 'Order from POS Billing app:\n'+lines.join('\n')+'\nTotal: \u20B9'+money(total);
        var shopPhone = (loadJSON(ns()+'shopPhone','')||'').replace(/\D/g,'');
        var target = shopPhone ? ('https://wa.me/'+shopPhone) : 'https://wa.me/';
        window.open(target+'?text='+encodeURIComponent(text), '_blank');
      }
      clearCart();
      if(fromDialog) closeOverlay('saveOverlay');
      toast('Order saved \u2014 the shop will contact you');
    });
  }).catch(function(e){
    var msg = 'Could not save order: ' + (e && e.message ? e.message : 'unknown error');
    if(fromDialog){ errEl.textContent = msg; } else { toast(msg); }
  }).finally(function(){
    pendingShare = false;
    if(fromDialog){ btn.disabled = false; btn.textContent = 'Save order'; }
  });
}

function openSwitch(){
  document.getElementById('switchInput').value = '';
  document.getElementById('switchErr').textContent = '';
  var recent = loadJSON('posc_recent', []);
  var el = document.getElementById('recentList');
  if(recent.length){
    el.innerHTML = '<hr class="divider"><div class="hint">Switch back to:</div>' + recent.map(function(s){
      return '<button class="recent-btn" onclick=\'goToShop('+JSON.stringify(s).replace(/'/g,"&#39;")+')\'>'+esc(s.name||s.shop)+'</button>';
    }).join('');
  } else { el.innerHTML=''; }
  openOverlay('switchOverlay');
}
function confirmSwitch(){
  var text = document.getElementById('switchInput').value;
  var c = parseShopLink(text);
  if(!c){ document.getElementById('switchErr').textContent = "That doesn't look like a shop link."; return; }
  goToShop(c);
}
function onboardConnect(){
  var text = document.getElementById('onboardInput').value;
  var c = parseShopLink(text);
  if(!c){ document.getElementById('onboardErr').textContent = "That doesn't look like a shop link."; return; }
  goToShop(c);
}

function openHistory(){
  var hist = loadJSON(ns()+'history', []);
  var el = document.getElementById('historyList');
  if(hist.length===0){ el.innerHTML = '<div class="empty">No past orders yet.</div>'; }
  else {
    el.innerHTML = hist.map(function(o, idx){
      var lines; try{ lines = JSON.parse(o.itemsJson); }catch(e){ lines=[]; }
      var lineHtml = lines.map(function(l){ return '<div class="line">'+esc(l.name)+' x'+l.qty+'</div>'; }).join('');
      return '<div class="hist-row"><div class="date">'+new Date(o.placedAt).toLocaleString()+'</div>'+lineHtml+
        '<div class="top" style="margin-top:6px;"><b>\u20B9'+money(o.total)+'</b><button class="pill outline" style="padding:6px 12px;" onclick="reorder('+idx+')">Re-order</button></div></div>';
    }).join('');
  }
  openOverlay('historyOverlay');
}
function reorder(idx){
  var hist = loadJSON(ns()+'history', []);
  var o = hist[idx]; if(!o) return;
  var lines; try{ lines = JSON.parse(o.itemsJson); }catch(e){ lines=[]; }
  var restored = {}; var any=false;
  lines.forEach(function(l){
    var it = items.find(function(x){return x.id===l.serverId;});
    if(it){ restored[it.id]=l.qty; any=true; }
  });
  if(!any){ toast('Those items are no longer available'); return; }
  cart = restored;
  closeOverlay('historyOverlay');
  render();
}

function updateNotifBadge(){
  var list = loadJSON(ns()+'notifications', []);
  var unread = list.filter(function(n){return !n.read;}).length;
  var b = document.getElementById('notifBadge');
  if(unread>0){ b.style.display=''; b.textContent = unread; } else { b.style.display='none'; }
}
function openNotifications(){
  fetchNotifications().then(function(fresh){
    if(fresh && fresh.length){
      var list = loadJSON(ns()+'notifications', []);
      fresh.forEach(function(n){ list.unshift({ orderId:n.orderId||'', status:n.status||'', message:n.message||'', receivedAt:Date.now(), read:false }); });
      saveJSON(ns()+'notifications', list.slice(0,100));
    }
    renderNotifications();
  }).catch(function(){ renderNotifications(); });
}
var STATUS_LABELS = { PENDING:'Pending', ACCEPTED:'Accepted', REJECTED:'Rejected', OUT_FOR_DELIVERY:'Out for delivery', DELIVERED:'Delivered', CANCELLED:'Cancelled' };
function renderNotifications(){
  var list = loadJSON(ns()+'notifications', []);
  var el = document.getElementById('notifList');
  if(list.length===0){ el.innerHTML = '<div class="empty">No notifications yet.</div>'; }
  else {
    el.innerHTML = list.map(function(n){
      var label = STATUS_LABELS[n.status] || '';
      return '<div class="notif-row">' + (label?'<div class="status-label">'+label+'</div>':'') +
        (n.message?'<div class="msg">'+esc(n.message)+'</div>':'') +
        '<div class="date">'+new Date(n.receivedAt).toLocaleString()+'</div></div>';
    }).join('');
    list.forEach(function(n){ n.read = true; });
    saveJSON(ns()+'notifications', list);
  }
  updateNotifBadge();
  openOverlay('notifOverlay');
}

function doRefresh(){
  fetchCatalog().then(function(){ toast('Updated — '+items.length+' item(s)'); render(); })
    .catch(function(e){ toast('Could not update: '+(e&&e.message?e.message:'error')); });
  fetchNotifications().then(function(fresh){
    if(fresh && fresh.length){
      var list = loadJSON(ns()+'notifications', []);
      fresh.forEach(function(n){ list.unshift({ orderId:n.orderId||'', status:n.status||'', message:n.message||'', receivedAt:Date.now(), read:false }); });
      saveJSON(ns()+'notifications', list.slice(0,100));
      updateNotifBadge();
    }
  });
}

function boot(){
  cfg = currentConfig();
  if(!cfg){
    document.getElementById('onboarding').style.display = 'block';
    return;
  }
  document.getElementById('app').style.display = 'block';
  var link = document.createElement('link');
  link.rel = 'manifest';
  link.href = '?app=manifest&shop='+encodeURIComponent(cfg.shop)+'&url='+encodeURIComponent(cfg.url)+'&type='+encodeURIComponent(cfg.type||'')+'&premium='+(cfg.premium?'1':'0');
  document.head.appendChild(link);

  items = loadJSON(ns()+'items', []);
  render();
  fetchCatalog().then(render).catch(function(e){ if(items.length===0) toast('Could not load catalog: '+(e&&e.message?e.message:'error')); });
  fetchNotifications().then(function(fresh){
    if(fresh && fresh.length){
      var list = loadJSON(ns()+'notifications', []);
      fresh.forEach(function(n){ list.unshift({ orderId:n.orderId||'', status:n.status||'', message:n.message||'', receivedAt:Date.now(), read:false }); });
      saveJSON(ns()+'notifications', list.slice(0,100));
      updateNotifBadge();
    }
  });

  if('serviceWorker' in navigator){
    navigator.serviceWorker.register('?app=sw').catch(function(){});
  }
}
boot();
</script>
</body>
</html>

APPHTML;
