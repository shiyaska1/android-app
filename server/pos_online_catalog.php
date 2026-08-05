<?php
/**
 * POS Billing — online catalog endpoint (customer ordering).
 *
 * UPLOAD (from the shop owner's app, Masters > Online Items > Upload):
 *   POST a JSON body to this URL:
 *     { "shop": "<ShopCode>", "shopName": "...", "shopPhone": "...",
 *       "items": [ { "id": "...", "name": "...", "category": "...",
 *                    "price": 0.0, "unit": "..." }, ... ] }
 *   Saved as-is to pos_online_catalog/<ShopCode>/catalog.json, overwriting
 *   whatever was there before — nothing is kept or versioned.
 *
 * FETCH (from a customer's app, opened via that shop's Play Store link):
 *   GET this URL with ?shop=<ShopCode> to get that same JSON back.
 *
 * Both directions use the SAME url — the app decides GET vs POST depending
 * on whether it's a shop-owner install or a customer install.
 *
 * --- Install ---
 * 1. Upload this single file next to pos_backup_sync.php on your server, e.g.:
 *      https://yourdomain.com/pos_online_catalog.php
 * 2. Set $API_KEY below to the same secret you used for pos_backup_sync.php
 *    (or a different one). Leaving it blank disables the check.
 * 3. Make sure $STORAGE_DIR is writable by the web server.
 * 4. In the shop owner's app: Settings > Online ordering, set:
 *      Shop code = anything unique to this shop (letters/digits/underscore)
 *      Online catalog URL = https://yourdomain.com/pos_online_catalog.php?key=YOUR_KEY
 * 5. For each customer you approach, hand out (or QR-code) a Play Store link
 *    with a &referrer= param carrying, url-encoded:
 *      mode=customer&shop=<ShopCode>&url=https://yourdomain.com/pos_online_catalog.php?key=YOUR_KEY
 *    (and optionally &type=Restaurant / Medical store / Medical lab, for wording only).
 *
 * --- Nginx note ---
 * Same as pos_backup_sync.php — see that file for the storage-folder note.
 */

// ---- configuration — edit these two lines ----
$API_KEY = '';                                     // e.g. 'change-me-to-something-long'; blank = no check
$STORAGE_DIR = __DIR__ . '/pos_online_catalog';    // where each shop's catalog.json is kept

function pos_catalog_fail($code, $msg) {
    http_response_code($code);
    header('Content-Type: text/plain');
    echo $msg;
    exit;
}

// ---- auth (optional — see $API_KEY above) ----
$givenKey = isset($_GET['key']) ? $_GET['key'] : '';
if ($API_KEY !== '' && $givenKey !== $API_KEY) {
    pos_catalog_fail(401, 'Invalid or missing key');
}

if (!is_dir($STORAGE_DIR)) {
    @mkdir($STORAGE_DIR, 0755, true);
}
$htaccessPath = $STORAGE_DIR . '/.htaccess';
if (!file_exists($htaccessPath)) {
    @file_put_contents($htaccessPath, "Require all denied\n");
}

$method = isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : '';

// ---- shop code: letters, digits, underscore only — blocks path traversal ----
// POST carries it in the JSON body (the upload screen doesn't add it to the URL);
// GET carries it as a query param (the fetch call does).
function pos_catalog_shop_from_get() {
    $shop = isset($_GET['shop']) ? (string) $_GET['shop'] : '';
    if (!preg_match('/^[A-Za-z0-9_]+$/', $shop)) {
        pos_catalog_fail(400, 'Invalid or missing shop code');
    }
    return $shop;
}

if ($method === 'POST' || $method === 'PUT') {
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') {
        pos_catalog_fail(400, 'Empty upload');
    }
    $body = json_decode($raw, true);
    if (!is_array($body)) {
        pos_catalog_fail(400, 'Body is not valid JSON');
    }
    $shop = isset($body['shop']) ? (string) $body['shop'] : '';
    if (!preg_match('/^[A-Za-z0-9_]+$/', $shop)) {
        pos_catalog_fail(400, 'Invalid or missing "shop" in body');
    }
    $folder = $STORAGE_DIR . '/' . $shop;
    if (!is_dir($folder) && !@mkdir($folder, 0755, true)) {
        pos_catalog_fail(500, 'Could not create storage folder — check permissions on ' . $STORAGE_DIR);
    }
    $path = $folder . '/catalog.json';
    $tmpPath = $path . '.uploading';
    if (@file_put_contents($tmpPath, $raw) === false) {
        pos_catalog_fail(500, 'Could not write file — check folder permissions');
    }
    // Write to a temp file then rename, so a Fetch that arrives mid-upload never
    // sees a half-written catalog.
    if (!@rename($tmpPath, $path)) {
        @unlink($tmpPath);
        pos_catalog_fail(500, 'Could not save file');
    }
    $itemCount = isset($body['items']) && is_array($body['items']) ? count($body['items']) : 0;
    header('Content-Type: text/plain');
    echo "OK: $itemCount item(s) saved for $shop";
    exit;
}

if ($method === 'GET') {
    $shop = pos_catalog_shop_from_get();
    $path = $STORAGE_DIR . '/' . $shop . '/catalog.json';
    if (!file_exists($path)) {
        pos_catalog_fail(404, 'No catalog uploaded yet for this shop code');
    }
    header('Content-Type: application/json');
    header('Content-Length: ' . (string) filesize($path));
    readfile($path);
    exit;
}

pos_catalog_fail(405, 'Method not allowed — use GET to fetch or POST to upload');
