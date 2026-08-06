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
 *       "attachments": [ "data:image/jpeg;base64,...", ... ] (optional, premium shops only —
 *           one or more compressed photos, e.g. several pages of a prescription),
 *       "items": [ { "id": "...", "name": "...", "qty": 0, "price": 0.0 }, ... ],
 *       "total": 0.0 }
 *   Appended to pos_online_catalog/<DeviceID>/orders.json (an array) — this
 *   file only grows via this endpoint; it's trimmed by the fetch below.
 *   To keep storage down on a low-resource host, any attachments still sitting
 *   in that file (not yet fetched by the shop owner) older than 7 days are cleared
 *   — the rest of the order is kept, only the photos are dropped. Once the shop owner's
 *   app fetches an order (see FETCH ORDERS below), it's gone from this server entirely —
 *   the shop's own copy from then on lives only on their phone.
 *   Responds with { "ok": true, "id": "<order id>" } — the app keeps that id so a
 *   later status notification about this order can be matched back to it.
 *
 * FETCH ORDERS (from the shop owner's app, "Orders" button):
 *   GET this URL with ?do=orders&shop=<DeviceID> — returns every order
 *   placed since the last fetch, THEN clears them from the server (the app
 *   keeps its own permanent copy locally, so this file never grows forever).
 *
 * SEND STATUS (from the shop owner's app, changing an order's status or sending a message):
 *   POST a JSON body to this URL with ?do=status added:
 *     { "shop": "<DeviceID>", "customerPhone": "...", "orderId": "..." (optional),
 *       "status": "ACCEPTED" (optional, one of OnlineOrderStatus's names),
 *       "message": "..." (optional, free text) }
 *   Appended to pos_online_catalog/<DeviceID>/notifications.json — trimmed by the
 *   fetch below, same as orders.
 *
 * FETCH NOTIFICATIONS (from a customer's app, polling for order updates):
 *   GET this URL with ?do=notifications&shop=<DeviceID>&phone=<their phone> —
 *   returns every status/message sent to that phone number since the last
 *   fetch, THEN clears just those from the server (other customers' pending
 *   notifications are left untouched).
 *
 * SEND MESSAGE (from a customer's app, replying to a notification — the customer->shop mirror
 *   of SEND STATUS above):
 *   POST a JSON body to this URL with ?do=message added:
 *     { "shop": "<DeviceID>", "customerPhone": "...", "customerName": "..." (optional),
 *       "orderId": "..." (optional), "message": "..." }
 *   Appended to pos_online_catalog/<DeviceID>/messages_from_customers.json — trimmed by
 *   the fetch below, same as orders/notifications.
 *
 * FETCH CUSTOMER MESSAGES (from the shop owner's app, the Messages screen):
 *   GET this URL with ?do=customerMessages&shop=<DeviceID> — returns every message
 *   customers have sent since the last fetch, THEN clears them from the server (the
 *   app keeps its own permanent copy locally in the shop_messages table).
 *
 * NEARBY SHOPS DIRECTORY (from a customer's app, "Browse shops > Nearby"):
 *   GET this URL with ?do=directory — returns every shop THAT HAS UPLOADED A
 *   CATALOG ON THIS SAME SERVER (i.e. sharing this one deployment/domain, not
 *   every shop that has ever used this app anywhere) which also uploaded its
 *   own shopLat/shopLng (Settings > Online ordering > Capture shop location,
 *   shop owner side): [ { "shop", "shopName", "shopCategory", "shopAddress",
 *   "shopLat", "shopLng", "itemCount" }, ... ]. A shop with no captured
 *   location is skipped — there's nothing to sort it by distance with. The
 *   app computes distance from the customer's own current location itself;
 *   this just lists the candidates.
 *
 * REGISTER PUSH TOKEN (from either app, silently on open — see $SERVICE_ACCOUNT_PATH below):
 *   POST a JSON body to this URL with ?do=registerToken added:
 *     { "shop": "<DeviceID>", "role": "shop" | "customer",
 *       "phone": "..." (required when role=customer), "token": "<FCM token>" }
 *   Saved to pos_online_catalog/<DeviceID>/tokens.json, one shop-owner token and one
 *   token per customer phone number. Whenever a new order, status update, or chat
 *   message is queued above, the matching token (if any, and if push is configured)
 *   gets an instant silent push telling that app to fetch right away — same content
 *   it would've picked up on its next 30-min poll either way, so this endpoint being
 *   unused, failing, or never set up doesn't break anything, it's just slower.
 *
 * SEND PROMOTION (from the shop owner's app, Messages > Send promotion — sales offers etc.):
 *   POST a JSON body to this URL with ?do=broadcast added:
 *     { "shop": "<DeviceID>", "message": "..." }
 *   Queued into every registered customer's notifications.json (i.e. every phone number
 *   that has ever registered a push token for this shop — see REGISTER PUSH TOKEN above),
 *   the exact same way a single-customer status update is, so it shows up in the customer
 *   app's normal notification list and gets pushed instantly the same way. Responds with
 *   { "ok": true, "count": <how many customers it was queued for> }.
 *
 * All eleven directions use the SAME url — the app decides which one to call
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
 * 6. (Optional, for instant push instead of a 30-min wait) In Firebase Console > Project
 *    settings > Service accounts, click "Generate new private key" and upload the
 *    downloaded JSON file to this server yourself — ideally outside the public web
 *    root, or protected the same way $STORAGE_DIR is (a .htaccess with "Require all
 *    denied"). Then set $SERVICE_ACCOUNT_PATH in pos_config.php (see below) to that
 *    file's full server path. This file is a real credential (it can send unlimited
 *    pushes on your Firebase project) — never commit it to git, never put it in a
 *    public folder.
 * 7. Your real $API_KEY / $SERVICE_ACCOUNT_PATH values live in pos_config.php, a
 *    second file next to this one that you create ONCE and this file never touches —
 *    so re-uploading pos_online_catalog.php after a future app update (to pick up a
 *    bug fix) never resets them back to blank. Create pos_config.php with:
 *      <?php
 *      $API_KEY = 'your-key';
 *      $SERVICE_ACCOUNT_PATH = '/full/server/path/to/service-account.json';
 *    Both are optional — omit either line to leave that one at its default (blank).
 *
 * --- Nginx note ---
 * Same as pos_backup_sync.php — see that file for the storage-folder note.
 */

// ---- configuration — these are just defaults; put your real values in pos_config.php
// (see install step 7 above), a file this script never overwrites. ----
$API_KEY = '';                                     // e.g. 'change-me-to-something-long'; blank = no check
$STORAGE_DIR = __DIR__ . '/pos_online_catalog';    // where each shop's catalog.json is kept
// Push notifications (instant instead of the app's 30-min fallback poll): the full server path
// of the Firebase service-account JSON key (Firebase Console > Project settings > Service
// accounts > Generate new private key), uploaded to this server by you, ideally outside the
// public web root (or protected by a .htaccess deny-all, same as $STORAGE_DIR gets
// automatically). Blank = push disabled; the app still works, just falls back to its 30-min
// poll for everything. Set this in pos_config.php, not here — see install step 7 above.
$SERVICE_ACCOUNT_PATH = '';
if (is_readable(__DIR__ . '/pos_config.php')) {
    require __DIR__ . '/pos_config.php';
}

function pos_catalog_fail($code, $msg) {
    http_response_code($code);
    header('Content-Type: text/plain');
    echo $msg;
    exit;
}

// ---- FCM push (best-effort — a failure here never breaks the request it's attached to) ----

/** Every push failure used to vanish silently (every call below is wrapped in @ and nothing
 *  checked the result), which made "push isn't arriving" undiagnosable from outside. This appends
 *  one line per failure to fcm.log next to the service account file, so the real reason (bad
 *  key, wrong project, expired/unregistered token, etc.) is visible instead of guessed at. */
function pos_fcm_log($serviceAccountPath, $msg) {
    if ($serviceAccountPath === '') return;
    @file_put_contents(dirname($serviceAccountPath) . '/fcm.log', date('c') . ' ' . $msg . "\n", FILE_APPEND);
}

/** Cached OAuth2 access token for the FCM HTTP v1 API, so a JWT isn't signed on every request —
 *  Google's tokens are valid ~1h; refreshed a minute early. Cache file sits next to the service
 *  account key itself, not inside a per-shop folder. */
function pos_fcm_access_token($serviceAccountPath) {
    if ($serviceAccountPath === '' || !is_readable($serviceAccountPath)) {
        if ($serviceAccountPath !== '') pos_fcm_log($serviceAccountPath, 'access_token: file not readable at ' . $serviceAccountPath);
        return null;
    }
    $cachePath = dirname($serviceAccountPath) . '/.fcm_token_cache.json';
    $cached = @json_decode(@file_get_contents($cachePath), true);
    if (is_array($cached) && isset($cached['access_token'], $cached['expires_at']) && $cached['expires_at'] > time() + 60) {
        return $cached['access_token'];
    }

    $sa = @json_decode(@file_get_contents($serviceAccountPath), true);
    if (!is_array($sa) || empty($sa['client_email']) || empty($sa['private_key'])) {
        pos_fcm_log($serviceAccountPath, 'access_token: service account JSON is invalid or missing client_email/private_key');
        return null;
    }

    $now = time();
    $header = array('alg' => 'RS256', 'typ' => 'JWT');
    $claims = array(
        'iss' => $sa['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud' => 'https://oauth2.googleapis.com/token',
        'iat' => $now,
        'exp' => $now + 3600
    );
    $b64 = function ($data) {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    };
    $unsigned = $b64(json_encode($header)) . '.' . $b64(json_encode($claims));
    $signature = '';
    $ok = @openssl_sign($unsigned, $signature, $sa['private_key'], 'sha256WithRSAEncryption');
    if (!$ok) {
        pos_fcm_log($serviceAccountPath, 'access_token: openssl_sign failed — private_key in the service account file is malformed');
        return null;
    }
    $jwt = $unsigned . '.' . $b64($signature);

    $body = http_build_query(array(
        'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        'assertion' => $jwt
    ));
    $context = stream_context_create(array('http' => array(
        'method' => 'POST',
        'header' => "Content-Type: application/x-www-form-urlencoded\r\n",
        'content' => $body,
        'timeout' => 8,
        'ignore_errors' => true
    )));
    $resp = @file_get_contents('https://oauth2.googleapis.com/token', false, $context);
    $data = $resp !== false ? @json_decode($resp, true) : null;
    if (!is_array($data) || empty($data['access_token'])) {
        pos_fcm_log(
            $serviceAccountPath,
            'access_token: OAuth token request failed — ' . ($resp === false ? 'no response (network/DNS/firewall?)' : 'response: ' . substr($resp, 0, 300))
        );
        return null;
    }
    @file_put_contents($cachePath, json_encode(array(
        'access_token' => $data['access_token'],
        'expires_at' => $now + (isset($data['expires_in']) ? (int) $data['expires_in'] : 3600)
    )));
    return $data['access_token'];
}

/** Sends one silent data-only push (no title/body — the app just treats it as "go check now",
 *  same content it would've fetched on its next 30-min poll). Never throws; every caller ignores
 *  the return value. */
function pos_fcm_send($serviceAccountPath, $token, array $data) {
    if ($serviceAccountPath === '' || $token === '') {
        return false;
    }
    $accessToken = pos_fcm_access_token($serviceAccountPath);
    if ($accessToken === null) {
        return false;
    }
    $sa = @json_decode(@file_get_contents($serviceAccountPath), true);
    $projectId = is_array($sa) && !empty($sa['project_id']) ? $sa['project_id'] : '';
    if ($projectId === '') {
        pos_fcm_log($serviceAccountPath, 'send: could not read project_id from the service account file');
        return false;
    }
    $stringData = array();
    foreach ($data as $k => $v) {
        $stringData[$k] = (string) $v;
    }
    // "high" Android priority is what lets this arrive promptly while the phone is locked and
    // the app is fully closed (same as WhatsApp) — the default "normal" priority gets deferred by
    // Doze/App Standby until the device next wakes up on its own, sometimes minutes to hours later.
    $payload = json_encode(array('message' => array(
        'token' => $token,
        'android' => array('priority' => 'high'),
        'data' => $stringData
    )));
    $context = stream_context_create(array('http' => array(
        'method' => 'POST',
        'header' => "Content-Type: application/json\r\nAuthorization: Bearer $accessToken\r\n",
        'content' => $payload,
        'timeout' => 8,
        'ignore_errors' => true
    )));
    $resp = @file_get_contents("https://fcm.googleapis.com/v1/projects/$projectId/messages:send", false, $context);
    // The old code discarded this response entirely, so an FCM-side rejection (unregistered
    // token, wrong project, FCM API not enabled on the Google Cloud project, etc.) was completely
    // invisible. $http_response_header is set by file_get_contents() itself after the call above.
    $status = 0;
    if (isset($http_response_header[0]) && preg_match('/\s(\d{3})\s/', $http_response_header[0], $m)) {
        $status = (int) $m[1];
    }
    if ($status < 200 || $status >= 300) {
        pos_fcm_log($serviceAccountPath, 'send: FCM rejected the push (HTTP ' . $status . ') — ' . ($resp === false ? 'no body' : substr($resp, 0, 300)));
        return false;
    }
    return true;
}

/** Reads a shop's registered push tokens (see do=registerToken below). Returns
 *  array('shop_token' => "...", 'customers' => array(phone => token)) — either half may be empty. */
function pos_fcm_tokens($storageDir, $shop) {
    $path = $storageDir . '/' . $shop . '/tokens.json';
    $data = @json_decode(@file_get_contents($path), true);
    if (!is_array($data)) {
        $data = array();
    }
    if (!isset($data['shop_token'])) $data['shop_token'] = '';
    if (!isset($data['customers']) || !is_array($data['customers'])) $data['customers'] = array();
    return $data;
}

/** Best-effort: pushes to the shop owner's device (new order, new customer message). */
function pos_fcm_notify_shop($serviceAccountPath, $storageDir, $shop, $type) {
    if ($serviceAccountPath === '') return;
    $tokens = pos_fcm_tokens($storageDir, $shop);
    if ($tokens['shop_token'] !== '') {
        pos_fcm_send($serviceAccountPath, $tokens['shop_token'], array('type' => $type));
    }
}

/** Best-effort: pushes to one customer's device (order status change, shop reply). */
function pos_fcm_notify_customer($serviceAccountPath, $storageDir, $shop, $phone, $type) {
    if ($serviceAccountPath === '') return;
    $tokens = pos_fcm_tokens($storageDir, $shop);
    if (isset($tokens['customers'][$phone]) && $tokens['customers'][$phone] !== '') {
        pos_fcm_send($serviceAccountPath, $tokens['customers'][$phone], array('type' => $type));
    }
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
        'attachments' => isset($body['attachments']) && is_array($body['attachments']) ? array_values($body['attachments']) : array(),
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
    // Server is low on storage: attachments sitting here more than 7 days (the shop owner
    // hasn't fetched them yet) are dropped, keeping the rest of that order intact.
    $attachmentCutoff = time() - 7 * 24 * 60 * 60;
    foreach ($existing as &$old) {
        if (!empty($old['attachments']) && !empty($old['receivedAt'])) {
            $receivedTs = strtotime($old['receivedAt']);
            if ($receivedTs !== false && $receivedTs < $attachmentCutoff) {
                $old['attachments'] = array();
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
    pos_fcm_notify_shop($SERVICE_ACCOUNT_PATH, $STORAGE_DIR, $shop, 'new_order');
    header('Content-Type: application/json');
    echo json_encode(array('ok' => true, 'id' => $order['id']));
    exit;
}

if (($method === 'POST' || $method === 'PUT') && $do === 'status') {
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') {
        pos_catalog_fail(400, 'Empty status update');
    }
    $body = json_decode($raw, true);
    if (!is_array($body)) {
        pos_catalog_fail(400, 'Body is not valid JSON');
    }
    $shop = isset($body['shop']) ? (string) $body['shop'] : '';
    if (!preg_match('/^[A-Za-z0-9_]+$/', $shop)) {
        pos_catalog_fail(400, 'Invalid or missing "shop" in body');
    }
    $phone = isset($body['customerPhone']) ? (string) $body['customerPhone'] : '';
    if ($phone === '') {
        pos_catalog_fail(400, 'Missing "customerPhone" in body');
    }
    $folder = $STORAGE_DIR . '/' . $shop;
    if (!is_dir($folder) && !@mkdir($folder, 0755, true)) {
        pos_catalog_fail(500, 'Could not create storage folder — check permissions on ' . $STORAGE_DIR);
    }
    $notification = array(
        'customerPhone' => $phone,
        'orderId' => isset($body['orderId']) ? (string) $body['orderId'] : '',
        'status' => isset($body['status']) ? (string) $body['status'] : '',
        'message' => isset($body['message']) ? (string) $body['message'] : '',
        'sentAt' => date('c')
    );
    $path = $folder . '/notifications.json';
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        pos_catalog_fail(500, 'Could not open notifications file — check folder permissions');
    }
    flock($fh, LOCK_EX);
    $existing = json_decode(stream_get_contents($fh), true);
    if (!is_array($existing)) {
        $existing = array();
    }
    $existing[] = $notification;
    ftruncate($fh, 0);
    rewind($fh);
    fwrite($fh, json_encode($existing));
    fflush($fh);
    flock($fh, LOCK_UN);
    fclose($fh);
    pos_fcm_notify_customer($SERVICE_ACCOUNT_PATH, $STORAGE_DIR, $shop, $phone, 'notification');
    header('Content-Type: text/plain');
    echo 'OK: notification queued for ' . $phone;
    exit;
}

if (($method === 'POST' || $method === 'PUT') && $do === 'message') {
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') {
        pos_catalog_fail(400, 'Empty message');
    }
    $body = json_decode($raw, true);
    if (!is_array($body)) {
        pos_catalog_fail(400, 'Body is not valid JSON');
    }
    $shop = isset($body['shop']) ? (string) $body['shop'] : '';
    if (!preg_match('/^[A-Za-z0-9_]+$/', $shop)) {
        pos_catalog_fail(400, 'Invalid or missing "shop" in body');
    }
    $phone = isset($body['customerPhone']) ? (string) $body['customerPhone'] : '';
    $text = isset($body['message']) ? (string) $body['message'] : '';
    if ($phone === '' || $text === '') {
        pos_catalog_fail(400, 'Missing "customerPhone" or "message" in body');
    }
    $folder = $STORAGE_DIR . '/' . $shop;
    if (!is_dir($folder) && !@mkdir($folder, 0755, true)) {
        pos_catalog_fail(500, 'Could not create storage folder — check permissions on ' . $STORAGE_DIR);
    }
    $message = array(
        'customerPhone' => $phone,
        'customerName' => isset($body['customerName']) ? (string) $body['customerName'] : '',
        'orderId' => isset($body['orderId']) ? (string) $body['orderId'] : '',
        'message' => $text,
        'sentAt' => date('c')
    );
    $path = $folder . '/messages_from_customers.json';
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        pos_catalog_fail(500, 'Could not open messages file — check folder permissions');
    }
    flock($fh, LOCK_EX);
    $existing = json_decode(stream_get_contents($fh), true);
    if (!is_array($existing)) {
        $existing = array();
    }
    $existing[] = $message;
    ftruncate($fh, 0);
    rewind($fh);
    fwrite($fh, json_encode($existing));
    fflush($fh);
    flock($fh, LOCK_UN);
    fclose($fh);
    pos_fcm_notify_shop($SERVICE_ACCOUNT_PATH, $STORAGE_DIR, $shop, 'new_message');
    header('Content-Type: application/json');
    echo json_encode(array('ok' => true));
    exit;
}

if (($method === 'POST' || $method === 'PUT') && $do === 'registerToken') {
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') {
        pos_catalog_fail(400, 'Empty body');
    }
    $body = json_decode($raw, true);
    if (!is_array($body)) {
        pos_catalog_fail(400, 'Body is not valid JSON');
    }
    $shop = isset($body['shop']) ? (string) $body['shop'] : '';
    if (!preg_match('/^[A-Za-z0-9_]+$/', $shop)) {
        pos_catalog_fail(400, 'Invalid or missing "shop" in body');
    }
    $role = isset($body['role']) ? (string) $body['role'] : '';
    $token = isset($body['token']) ? (string) $body['token'] : '';
    $phone = isset($body['phone']) ? (string) $body['phone'] : '';
    if ($token === '' || ($role !== 'shop' && $role !== 'customer')) {
        pos_catalog_fail(400, 'Missing "token" or invalid "role" (must be "shop" or "customer")');
    }
    if ($role === 'customer' && $phone === '') {
        pos_catalog_fail(400, 'Missing "phone" for role=customer');
    }
    $folder = $STORAGE_DIR . '/' . $shop;
    if (!is_dir($folder) && !@mkdir($folder, 0755, true)) {
        pos_catalog_fail(500, 'Could not create storage folder — check permissions on ' . $STORAGE_DIR);
    }
    $path = $folder . '/tokens.json';
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        pos_catalog_fail(500, 'Could not open tokens file — check folder permissions');
    }
    flock($fh, LOCK_EX);
    $data = json_decode(stream_get_contents($fh), true);
    if (!is_array($data)) {
        $data = array();
    }
    if (!isset($data['customers']) || !is_array($data['customers'])) {
        $data['customers'] = array();
    }
    if ($role === 'shop') {
        $data['shop_token'] = $token;
    } else {
        $data['customers'][$phone] = $token;
    }
    ftruncate($fh, 0);
    rewind($fh);
    fwrite($fh, json_encode($data));
    fflush($fh);
    flock($fh, LOCK_UN);
    fclose($fh);
    header('Content-Type: application/json');
    echo json_encode(array('ok' => true));
    exit;
}

if (($method === 'POST' || $method === 'PUT') && $do === 'broadcast') {
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') {
        pos_catalog_fail(400, 'Empty body');
    }
    $body = json_decode($raw, true);
    if (!is_array($body)) {
        pos_catalog_fail(400, 'Body is not valid JSON');
    }
    $shop = isset($body['shop']) ? (string) $body['shop'] : '';
    if (!preg_match('/^[A-Za-z0-9_]+$/', $shop)) {
        pos_catalog_fail(400, 'Invalid or missing "shop" in body');
    }
    $text = isset($body['message']) ? (string) $body['message'] : '';
    if ($text === '') {
        pos_catalog_fail(400, 'Missing "message" in body');
    }
    // Every phone this shop has ever registered a push token for (see do=registerToken) — the
    // closest thing to a customer list this server keeps, since it otherwise never retains a
    // customer registry (orders/messages are cleared once fetched). A customer only lands here
    // once they've saved their name/phone at least once in the app, not just placed one order.
    $tokens = pos_fcm_tokens($STORAGE_DIR, $shop);
    $phones = array_keys($tokens['customers']);

    $folder = $STORAGE_DIR . '/' . $shop;
    if (!is_dir($folder) && !@mkdir($folder, 0755, true)) {
        pos_catalog_fail(500, 'Could not create storage folder — check permissions on ' . $STORAGE_DIR);
    }
    $path = $folder . '/notifications.json';
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        pos_catalog_fail(500, 'Could not open notifications file — check folder permissions');
    }
    flock($fh, LOCK_EX);
    $existing = json_decode(stream_get_contents($fh), true);
    if (!is_array($existing)) {
        $existing = array();
    }
    $sentAt = date('c');
    foreach ($phones as $phone) {
        $existing[] = array(
            'customerPhone' => $phone,
            'orderId' => '',
            'status' => '',
            'message' => $text,
            'sentAt' => $sentAt
        );
    }
    ftruncate($fh, 0);
    rewind($fh);
    fwrite($fh, json_encode($existing));
    fflush($fh);
    flock($fh, LOCK_UN);
    fclose($fh);

    foreach ($phones as $phone) {
        pos_fcm_notify_customer($SERVICE_ACCOUNT_PATH, $STORAGE_DIR, $shop, $phone, 'notification');
    }

    header('Content-Type: application/json');
    echo json_encode(array('ok' => true, 'count' => count($phones)));
    exit;
}

if ($method === 'GET' && $do === 'notifications') {
    $shop = pos_catalog_shop_from_get();
    $phone = isset($_GET['phone']) ? (string) $_GET['phone'] : '';
    $path = $STORAGE_DIR . '/' . $shop . '/notifications.json';
    if ($phone === '' || !file_exists($path)) {
        header('Content-Type: application/json');
        echo '[]';
        exit;
    }
    // Only this phone's notifications are removed — everyone else's stay queued.
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        pos_catalog_fail(500, 'Could not open notifications file — check folder permissions');
    }
    flock($fh, LOCK_EX);
    $all = json_decode(stream_get_contents($fh), true);
    if (!is_array($all)) {
        $all = array();
    }
    $mine = array();
    $rest = array();
    foreach ($all as $n) {
        if (isset($n['customerPhone']) && $n['customerPhone'] === $phone) {
            $mine[] = $n;
        } else {
            $rest[] = $n;
        }
    }
    ftruncate($fh, 0);
    rewind($fh);
    fwrite($fh, json_encode($rest));
    fflush($fh);
    flock($fh, LOCK_UN);
    fclose($fh);
    header('Content-Type: application/json');
    echo json_encode($mine);
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

if ($method === 'GET' && $do === 'customerMessages') {
    $shop = pos_catalog_shop_from_get();
    $path = $STORAGE_DIR . '/' . $shop . '/messages_from_customers.json';
    if (!file_exists($path)) {
        header('Content-Type: application/json');
        echo '[]';
        exit;
    }
    // Read then clear, locked, same "server is a short-lived queue" convention as do=orders —
    // the shop owner's app keeps its own permanent copy in the shop_messages table.
    $fh = fopen($path, 'c+');
    if ($fh === false) {
        pos_catalog_fail(500, 'Could not open messages file — check folder permissions');
    }
    flock($fh, LOCK_EX);
    $raw = stream_get_contents($fh);
    ftruncate($fh, 0);
    fflush($fh);
    flock($fh, LOCK_UN);
    fclose($fh);
    $messages = json_decode($raw, true);
    header('Content-Type: application/json');
    echo is_array($messages) ? json_encode($messages) : '[]';
    exit;
}

if ($method === 'GET' && $do === 'directory') {
    $entries = array();
    $dirs = @scandir($STORAGE_DIR);
    if (is_array($dirs)) {
        foreach ($dirs as $shop) {
            if ($shop === '.' || $shop === '..') {
                continue;
            }
            $catalogPath = $STORAGE_DIR . '/' . $shop . '/catalog.json';
            if (!file_exists($catalogPath)) {
                continue;
            }
            $data = json_decode(@file_get_contents($catalogPath), true);
            if (!is_array($data) || !isset($data['shopLat']) || !isset($data['shopLng'])) {
                continue;
            }
            $lat = (float) $data['shopLat'];
            $lng = (float) $data['shopLng'];
            if ($lat === 0.0 && $lng === 0.0) {
                continue;
            }
            $entries[] = array(
                'shop' => $shop,
                'shopName' => isset($data['shopName']) ? (string) $data['shopName'] : '',
                'shopCategory' => isset($data['shopCategory']) ? (string) $data['shopCategory'] : '',
                'shopAddress' => isset($data['shopAddress']) ? (string) $data['shopAddress'] : '',
                'shopLat' => $lat,
                'shopLng' => $lng,
                'itemCount' => isset($data['items']) && is_array($data['items']) ? count($data['items']) : 0
            );
        }
    }
    header('Content-Type: application/json');
    echo json_encode($entries);
    exit;
}

// Every recognised "do" value above ends in exit; if one was given but none of those blocks
// matched, this server doesn't know it — most likely an app newer than this file (e.g. it was
// updated to send do=registerToken before this file got re-uploaded). Fail loudly instead of
// falling through to the catalog-upload handler below, which would otherwise silently overwrite
// this shop's real catalog.json with whatever unrelated body that unknown "do" call sent.
if ($do !== '') {
    pos_catalog_fail(400, 'Unknown do=' . $do . ' — this server is running an older pos_online_catalog.php than the app; upload the latest version from the app\'s repo.');
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
    // A real catalog upload always carries an "items" array (even an empty one — see
    // OnlineCatalogUpload.kt). Requiring it here is a second guard against anything that isn't
    // actually a catalog upload landing here and overwriting a shop's real catalog.json.
    if (!isset($body['items']) || !is_array($body['items'])) {
        pos_catalog_fail(400, 'Missing "items" in body — this endpoint only accepts catalog uploads without a "do" param');
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
