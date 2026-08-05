<?php
/**
 * POS Billing — online catalog endpoint (customer ordering).
 *
 * The "shop code" is just that shop's Android Device ID (Settings > Online ordering shows it,
 * pre-filled from the same id the license/activation screen already uses) — nothing extra to
 * hand out or keep straight, and no registry needed on this server: the folder is created the
 * first time that device uploads.
 *
 * UPLOAD (from the shop owner's app, Masters > Online Items > Upload):
 *   POST a JSON body to this URL:
 *     { "shop": "<DeviceID>", "shopName": "...", "shopPhone": "...",
 *       "items": [ { "id": "...", "name": "...", "category": "...",
 *                    "price": 0.0, "unit": "..." }, ... ] }
 *   Saved as-is to pos_online_catalog/<DeviceID>/catalog.json, overwriting
 *   whatever was there before — nothing is kept or versioned.
 *
 * FETCH (from a customer's app, opened via that shop's Play Store link):
 *   GET this URL with ?shop=<DeviceID> to get that same JSON back.
 *
 * PLACE ORDER (from the customer's app, "Save" on the order screen):
 *   POST a JSON body to this URL with ?do=order added:
 *     { "shop": "<DeviceID>", "customerName": "...", "customerPhone": "...",
 *       "customerAddress": "..." (optional),
 *       "location": "https://maps.google.com/?q=lat,lng" (optional),
 *       "note": "..." (optional, free text — e.g. a prescription description),
 *       "attachmentImage": "data:image/jpeg;base64,..." (optional, premium shops only —
 *           a compressed photo, e.g. a prescription),
 *       "items": [ { "id": "...", "name": "...", "qty": 0, "price": 0.0 }, ... ],
 *       "total": 0.0 }
 *   Appended to pos_online_catalog/<DeviceID>/orders.json (an array) — this
 *   file only grows via this endpoint; it's trimmed by the fetch below.
 *   To keep storage down on a low-resource host, any attachmentImage still sitting
 *   in that file (not yet fetched by the shop owner) older than 7 days is cleared
 *   — the rest of the order is kept, only the photo is dropped.
 *
 * FETCH ORDERS (from the shop owner's app, "Orders" button):
 *   GET this URL with ?do=orders&shop=<DeviceID> — returns every order
 *   placed since the last fetch, THEN clears them from the server (the app
 *   keeps its own permanent copy locally, so this file never grows forever).
 *
 * All four directions use the SAME url — the app decides which one to call
 * based on GET/POST and the "do" param, depending on which screen it's on.
 *
 * --- Install ---
 * 1. Upload this single file next to pos_backup_sync.php on your server, e.g.:
 *      https://yourdomain.com/pos_online_catalog.php
 * 2. Set $API_KEY below to the same secret you used for pos_backup_sync.php
 *    (or a different one). Leaving it blank disables the check.
 * 3. Make sure $STORAGE_DIR is writable by the web server.
 * 4. In the shop owner's app: Settings > Online ordering, set:
 *      Online catalog URL = https://yourdomain.com/pos_online_catalog.php?key=YOUR_KEY
 *      (Shop code is already filled in — their Device ID — leave it as-is.)
 * 5. For each customer you approach, hand out (or QR-code) a Play Store link
 *    with a &referrer= param carrying, url-encoded:
 *      mode=customer&shop=<DeviceID>&url=https://yourdomain.com/pos_online_catalog.php?key=YOUR_KEY
 *    (and optionally &type=Restaurant / Medical store / Medical lab, for wording only).
 *    The Customer Link Builder tool builds this exact link (and its QR code) for you.
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

$do = isset($_GET['do']) ? (string) $_GET['do'] : '';

if (($method === 'POST' || $method === 'PUT') && $do === 'order') {
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') {
        pos_catalog_fail(400, 'Empty order');
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
    $order = array(
        'id' => uniqid('', true),
        'receivedAt' => date('c'),
        'customerName' => isset($body['customerName']) ? (string) $body['customerName'] : '',
        'customerPhone' => isset($body['customerPhone']) ? (string) $body['customerPhone'] : '',
        'customerAddress' => isset($body['customerAddress']) ? (string) $body['customerAddress'] : '',
        'location' => isset($body['location']) ? (string) $body['location'] : '',
        'note' => isset($body['note']) ? (string) $body['note'] : '',
        'attachmentImage' => isset($body['attachmentImage']) ? (string) $body['attachmentImage'] : '',
        'items' => isset($body['items']) && is_array($body['items']) ? $body['items'] : array(),
        'total' => isset($body['total']) ? (float) $body['total'] : 0.0
    );
    $path = $folder . '/orders.json';
    // Locked read-modify-write, so two orders arriving at the same moment don't overwrite
    // each other (the risk plain file_put_contents has, unlike the single-writer catalog upload).
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        pos_catalog_fail(500, 'Could not open orders file — check folder permissions');
    }
    flock($fh, LOCK_EX);
    $existingRaw = stream_get_contents($fh);
    $existing = json_decode($existingRaw, true);
    if (!is_array($existing)) {
        $existing = array();
    }
    // Server is low on storage: an attachment sitting here more than 7 days (the shop owner
    // hasn't fetched it yet) is dropped, keeping the rest of that order intact.
    $attachmentCutoff = time() - 7 * 24 * 60 * 60;
    foreach ($existing as &$old) {
        if (!empty($old['attachmentImage']) && !empty($old['receivedAt'])) {
            $receivedTs = strtotime($old['receivedAt']);
            if ($receivedTs !== false && $receivedTs < $attachmentCutoff) {
                $old['attachmentImage'] = '';
            }
        }
    }
    unset($old);
    $existing[] = $order;
    ftruncate($fh, 0);
    rewind($fh);
    fwrite($fh, json_encode($existing));
    fflush($fh);
    flock($fh, LOCK_UN);
    fclose($fh);
    header('Content-Type: text/plain');
    echo 'OK: order saved for ' . $shop;
    exit;
}

if ($method === 'GET' && $do === 'orders') {
    $shop = pos_catalog_shop_from_get();
    $path = $STORAGE_DIR . '/' . $shop . '/orders.json';
    if (!file_exists($path)) {
        header('Content-Type: application/json');
        echo '[]';
        exit;
    }
    // Read then clear, locked, so orders are handed out exactly once — the app keeps its
    // own permanent local copy from here, this file is only ever a short-lived queue.
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        pos_catalog_fail(500, 'Could not open orders file — check folder permissions');
    }
    flock($fh, LOCK_EX);
    $raw = stream_get_contents($fh);
    ftruncate($fh, 0);
    fflush($fh);
    flock($fh, LOCK_UN);
    fclose($fh);
    $orders = json_decode($raw, true);
    header('Content-Type: application/json');
    echo is_array($orders) ? json_encode($orders) : '[]';
    exit;
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
