<?php
/**
 * POS Billing — backup push/pull endpoint.
 *
 * PUSH (from the Android app's "Push backup to server" button):
 *   POST the backup .zip as the raw request body to this URL with
 *   ?org=<OrgID>&device=<DeviceID>. The file is stored at
 *     pos_backups/<OrgID>/<DeviceID>/backup.zip
 *   creating both folders automatically if they don't exist yet, and
 *   OVERWRITING that one file every time — nothing is kept or versioned, so
 *   storage never grows no matter how many times you push.
 *
 * PULL (from the app's "Pull backup from server" button):
 *   GET this URL with ?org=<OrgID>&device=<DeviceID> to download that same
 *   zip back. The app then runs its normal restore (Replace all / Merge) on
 *   whatever it downloads.
 *
 * Org ID and Device ID both come straight from the app's Settings screen — you
 * never need to type a filename or folder path anywhere yourself.
 *
 * --- Install ---
 * 1. Upload this single file to your web server, e.g.:
 *      https://yourdomain.com/pos_backup_sync.php
 * 2. Set $API_KEY below to a secret of your choosing. Leaving it blank disables
 *    the check — fine for quick testing, but NOT recommended once this is on a
 *    public server: anyone who finds the URL and guesses an Org/Device ID could
 *    push (overwrite) or pull (download) that backup.
 * 3. Make sure $STORAGE_DIR is writable by the web server. This script creates
 *    the org/device folders automatically as they're used, along with a
 *    .htaccess that blocks direct browser access to the raw zip files
 *    (Apache only — see the Nginx note below if you're on Nginx instead).
 * 4. In the app: Settings > Cloud backup sync, set:
 *      Push URL = https://yourdomain.com/pos_backup_sync.php?key=YOUR_KEY
 *      Pull URL = https://yourdomain.com/pos_backup_sync.php?key=YOUR_KEY
 *      Org ID / Device ID = anything that identifies this business/phone
 *    (the app appends &org=...&device=... itself). Use the SAME Org ID +
 *    Device ID on every phone you want to Push from and Pull the same backup
 *    to.
 *
 * --- Nginx note ---
 * If your server is Nginx (not Apache), the .htaccess this script writes has no
 * effect. Instead, add this to your site's server block to block direct access
 * to the storage folder:
 *   location /pos_backups/ { deny all; }
 */

// ---- configuration — edit these two lines ----
$API_KEY = '';                              // e.g. 'change-me-to-something-long'; blank = no check
$STORAGE_DIR = __DIR__ . '/pos_backups';    // where the org/device folders are kept

function pos_backup_fail($code, $msg) {
    http_response_code($code);
    header('Content-Type: text/plain');
    echo $msg;
    exit;
}

// ---- auth (optional — see $API_KEY above) ----
$givenKey = isset($_GET['key']) ? $_GET['key'] : '';
if ($API_KEY !== '' && $givenKey !== $API_KEY) {
    pos_backup_fail(401, 'Invalid or missing key');
}

// ---- org/device: letters, digits, underscore only — blocks path traversal ----
$org = isset($_GET['org']) ? (string) $_GET['org'] : '';
$device = isset($_GET['device']) ? (string) $_GET['device'] : '';
if (!preg_match('/^[A-Za-z0-9_]+$/', $org) || !preg_match('/^[A-Za-z0-9_]+$/', $device)) {
    pos_backup_fail(400, 'Invalid or missing org/device');
}

// ---- make sure pos_backups/<org>/<device>/ exists, creating it if needed ----
if (!is_dir($STORAGE_DIR)) {
    @mkdir($STORAGE_DIR, 0755, true);
}
$htaccessPath = $STORAGE_DIR . '/.htaccess';
if (!file_exists($htaccessPath)) {
    @file_put_contents($htaccessPath, "Require all denied\n");
}
$folder = $STORAGE_DIR . '/' . $org . '/' . $device;
if (!is_dir($folder) && !@mkdir($folder, 0755, true)) {
    pos_backup_fail(500, 'Could not create storage folder — check permissions on ' . $STORAGE_DIR);
}
$path = $folder . '/backup.zip';

$method = isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : '';

if ($method === 'POST' || $method === 'PUT') {
    // ---- PUSH: overwrite backup.zip in this org/device's folder ----
    $input = fopen('php://input', 'rb');
    if ($input === false) {
        pos_backup_fail(500, 'Could not read request body');
    }
    $tmpPath = $path . '.uploading';
    $out = fopen($tmpPath, 'wb');
    if ($out === false) {
        fclose($input);
        pos_backup_fail(500, 'Could not write file — check folder permissions');
    }
    $bytes = stream_copy_to_stream($input, $out);
    fclose($input);
    fclose($out);
    if ($bytes === false || $bytes === 0) {
        @unlink($tmpPath);
        pos_backup_fail(400, 'Empty upload');
    }
    // The app sends the exact file size as Content-Length. If fewer bytes actually
    // arrived (host post_max_size limit, execution-time limit, dropped connection),
    // stream_copy_to_stream() would otherwise silently "succeed" with a truncated,
    // unreadable zip — so check this before saving instead of trusting the count.
    $expectedLength = isset($_SERVER['CONTENT_LENGTH']) ? (int) $_SERVER['CONTENT_LENGTH'] : null;
    if ($expectedLength !== null && $bytes !== $expectedLength) {
        @unlink($tmpPath);
        pos_backup_fail(400, "Upload incomplete: expected $expectedLength bytes but only received $bytes. " .
            "Check this host's post_max_size / max_execution_time PHP settings, or retry on a better connection.");
    }
    // Write to a temp file then rename, so a Pull that arrives mid-upload never
    // sees a half-written zip.
    if (!@rename($tmpPath, $path)) {
        @unlink($tmpPath);
        pos_backup_fail(500, 'Could not save file');
    }
    header('Content-Type: text/plain');
    echo "OK: $bytes bytes saved for $org/$device";
    exit;
}

if ($method === 'GET') {
    // ---- PULL: serve backup.zip back from this org/device's folder ----
    if (!file_exists($path)) {
        pos_backup_fail(404, 'No backup found yet for this Org ID / Device ID');
    }
    header('Content-Type: application/zip');
    header('Content-Disposition: attachment; filename="posbill_' . $org . '_' . $device . '.zip"');
    header('Content-Length: ' . (string) filesize($path));
    readfile($path);
    exit;
}

pos_backup_fail(405, 'Method not allowed — use GET to pull or POST to push');
