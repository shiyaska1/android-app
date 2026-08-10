<?php
/**
 * Indian Jobs — admin portal for the job-post image list.
 *
 * Lets you manage the list of job-post image URLs that the Android app
 * displays. Existing images show as a checkable grid (select one-by-one or
 * "select all") with a Delete button; a single box lets you paste one new
 * image URL with a live preview before you add it to the top of the list.
 * Clicking "Save" writes the list out to job_urls.json.
 *
 * You never need to create that file yourself — this script creates it
 * automatically the first time you click Save, as long as this folder is
 * writable by the web server (the default on most hosting).
 *
 * --- Install ---
 * 1. Upload ONLY this one file to your web server.
 * 2. Change $ADMIN_USER / $ADMIN_PASS below before this goes anywhere
 *    public — the admin/dev@123 defaults are placeholders only.
 * 3. Visit https://yourdomain.com/job_admin.php, log in, and add images.
 * 4. Point the Android app's source URL (pencil icon) at:
 *      https://yourdomain.com/job_admin.php?action=list
 *    (GET, no login needed — the app fetches from there, never the JSON
 *    file directly).
 */

// ---- Configuration -------------------------------------------------------
$ADMIN_USER = 'admin';
$ADMIN_PASS = 'dev@123';
$DATA_FILE = __DIR__ . '/job_urls.json';
// ---------------------------------------------------------------------------

function read_urls(string $file): array {
    if (!file_exists($file)) {
        return [];
    }
    $decoded = json_decode(file_get_contents($file), true);
    return is_array($decoded) ? array_values(array_filter($decoded, fn($u) => is_string($u) && trim($u) !== '')) : [];
}

function write_urls(string $file, array $urls): void {
    // Creates the file automatically if it doesn't exist yet.
    file_put_contents($file, json_encode(array_values($urls), JSON_PRETTY_PRINT));
}

// ---- Public read endpoint: GET job_admin.php?action=list ------------------
// No login needed — this is what the Android app fetches. Plain text,
// comma-separated (the stored job_urls.json is turned into that here).
if (($_GET['action'] ?? '') === 'list') {
    header('Content-Type: text/plain; charset=utf-8');
    header('Cache-Control: no-store');
    echo implode(',', read_urls($DATA_FILE));
    exit;
}

session_start();

// ---- Login handling --------------------------------------------------------
if (isset($_POST['action']) && $_POST['action'] === 'login') {
    $user = $_POST['username'] ?? '';
    $pass = $_POST['password'] ?? '';
    if (hash_equals($ADMIN_USER, $user) && hash_equals($ADMIN_PASS, $pass)) {
        $_SESSION['job_admin_logged_in'] = true;
    } else {
        $loginError = 'Incorrect username or password.';
    }
}

if (isset($_GET['logout'])) {
    unset($_SESSION['job_admin_logged_in']);
    header('Location: job_admin.php');
    exit;
}

$isLoggedIn = !empty($_SESSION['job_admin_logged_in']);

// ---- Save handling (only when logged in) -----------------------------------
$saveMessage = '';
if ($isLoggedIn && isset($_POST['action']) && $_POST['action'] === 'save') {
    $csv = trim($_POST['urls_csv'] ?? '');
    $urls = [];
    foreach (explode(',', $csv) as $u) {
        $u = trim($u);
        if ($u !== '') {
            $urls[] = $u;
        }
    }
    write_urls($DATA_FILE, $urls);
    $saveMessage = 'Saved ' . count($urls) . ' image URL(s).';
}

$existingUrls = $isLoggedIn ? read_urls($DATA_FILE) : [];
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Indian Jobs — Admin</title>
<style>
  body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background: #f5f7fa; margin: 0; padding: 24px; color: #222; }
  .card { max-width: 480px; margin: 60px auto; background: #fff; border-radius: 10px; padding: 28px; box-shadow: 0 1px 4px rgba(0,0,0,0.1); }
  h1 { font-size: 20px; margin: 0 0 16px; }
  label { display: block; margin: 12px 0 4px; font-size: 14px; font-weight: 600; }
  input[type=text], input[type=password] { width: 100%; box-sizing: border-box; padding: 10px; border: 1px solid #ccc; border-radius: 6px; font-size: 15px; }
  button { cursor: pointer; border: none; border-radius: 6px; padding: 10px 18px; font-size: 15px; font-weight: 600; }
  .btn-primary { background: #1565c0; color: #fff; }
  .btn-danger { background: #c62828; color: #fff; }
  .btn-secondary { background: #e0e0e0; color: #222; }
  .error { color: #c62828; font-size: 14px; margin-top: 8px; }
  .topbar { max-width: 1000px; margin: 0 auto 20px; display: flex; justify-content: space-between; align-items: center; }
  .topbar a { color: #1565c0; text-decoration: none; font-size: 14px; }
  .toolbar { max-width: 1000px; margin: 0 auto 16px; background: #fff; border-radius: 10px; padding: 16px; box-shadow: 0 1px 4px rgba(0,0,0,0.1); display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; }
  .toolbar .field { flex: 1; min-width: 240px; }
  .preview { width: 90px; height: 120px; object-fit: cover; border-radius: 6px; border: 1px solid #ddd; background: #eee; display: none; }
  .grid-wrap { max-width: 1000px; margin: 0 auto; background: #fff; border-radius: 10px; padding: 16px; box-shadow: 0 1px 4px rgba(0,0,0,0.1); }
  .grid-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 12px; }
  .tile { border: 1px solid #eee; border-radius: 8px; padding: 8px; text-align: center; }
  .tile img { width: 100%; height: 150px; object-fit: cover; border-radius: 6px; background: #eee; }
  .tile .url-text { font-size: 10px; word-break: break-all; color: #777; margin-top: 4px; max-height: 32px; overflow: hidden; }
  .tile input[type=checkbox] { transform: scale(1.3); margin-top: 6px; }
  .msg { max-width: 1000px; margin: 0 auto 16px; background: #e8f5e9; color: #2e7d32; padding: 10px 16px; border-radius: 8px; font-size: 14px; }
  .empty { color: #888; padding: 24px; text-align: center; }
</style>
</head>
<body>

<?php if (!$isLoggedIn): ?>

  <div class="card">
    <h1>Indian Jobs — Admin login</h1>
    <form method="post">
      <input type="hidden" name="action" value="login">
      <label>Username</label>
      <input type="text" name="username" autocomplete="username" required>
      <label>Password</label>
      <input type="password" name="password" autocomplete="current-password" required>
      <button type="submit" class="btn-primary" style="margin-top:18px;">Log in</button>
      <?php if (!empty($loginError)): ?><div class="error"><?= htmlspecialchars($loginError) ?></div><?php endif; ?>
    </form>
  </div>

<?php else: ?>

  <div class="topbar">
    <strong>Indian Jobs — Admin</strong>
    <a href="job_admin.php?logout=1">Log out</a>
  </div>

  <?php if ($saveMessage): ?><div class="msg"><?= htmlspecialchars($saveMessage) ?></div><?php endif; ?>

  <div class="toolbar">
    <div class="field">
      <label>New job-post image URL</label>
      <input type="text" id="newUrlInput" placeholder="https://example.com/job123.jpg" oninput="previewNewUrl()">
    </div>
    <img id="newUrlPreview" class="preview" alt="Preview">
    <button type="button" class="btn-primary" onclick="addNewUrl()">Add to list</button>
    <button type="button" class="btn-secondary" onclick="clearNewUrl()">Clear</button>
  </div>

  <div class="grid-wrap">
    <div class="grid-header">
      <label style="margin:0;"><input type="checkbox" id="selectAll" onchange="toggleSelectAll()"> Select all</label>
      <button type="button" class="btn-danger" onclick="deleteSelected()">Delete selected</button>
    </div>
    <div id="grid" class="grid"></div>
    <div id="emptyMsg" class="empty" style="display:none;">No job-post images yet. Paste a URL above and click Save.</div>
  </div>

  <div style="max-width:1000px;margin:16px auto;text-align:right;">
    <button type="button" class="btn-primary" onclick="saveAll()">Save</button>
  </div>

  <form id="uploadForm" method="post" style="display:none;">
    <input type="hidden" name="action" value="save">
    <input type="hidden" name="urls_csv" id="urlsCsv">
  </form>

  <script>
    var existingUrls = <?= json_encode($existingUrls) ?>;

    function renderGrid() {
      var grid = document.getElementById('grid');
      var emptyMsg = document.getElementById('emptyMsg');
      grid.innerHTML = '';
      if (existingUrls.length === 0) {
        emptyMsg.style.display = 'block';
      } else {
        emptyMsg.style.display = 'none';
        existingUrls.forEach(function (url, index) {
          var tile = document.createElement('div');
          tile.className = 'tile';

          var img = document.createElement('img');
          img.src = url;
          img.alt = 'Job post';

          var urlText = document.createElement('div');
          urlText.className = 'url-text';
          urlText.textContent = url;

          var checkbox = document.createElement('input');
          checkbox.type = 'checkbox';
          checkbox.className = 'tile-checkbox';
          checkbox.dataset.index = index;

          tile.appendChild(img);
          tile.appendChild(urlText);
          tile.appendChild(document.createElement('br'));
          tile.appendChild(checkbox);
          grid.appendChild(tile);
        });
      }
      document.getElementById('selectAll').checked = false;
    }

    function toggleSelectAll() {
      var checked = document.getElementById('selectAll').checked;
      document.querySelectorAll('.tile-checkbox').forEach(function (cb) { cb.checked = checked; });
    }

    function deleteSelected() {
      var toRemove = [];
      document.querySelectorAll('.tile-checkbox').forEach(function (cb) {
        if (cb.checked) toRemove.push(parseInt(cb.dataset.index, 10));
      });
      if (toRemove.length === 0) return;
      existingUrls = existingUrls.filter(function (_, i) { return toRemove.indexOf(i) === -1; });
      renderGrid();
    }

    function previewNewUrl() {
      var val = document.getElementById('newUrlInput').value.trim();
      var preview = document.getElementById('newUrlPreview');
      if (val !== '') {
        preview.src = val;
        preview.style.display = 'inline-block';
      } else {
        preview.style.display = 'none';
      }
    }

    function clearNewUrl() {
      document.getElementById('newUrlInput').value = '';
      previewNewUrl();
    }

    // Adds the pasted URL to the top of the list (newest job first), then
    // clears the box so the next URL can be pasted in and added the same way.
    function addNewUrl() {
      var input = document.getElementById('newUrlInput');
      var val = input.value.trim();
      if (val === '') return;
      existingUrls.unshift(val);
      renderGrid();
      input.value = '';
      previewNewUrl();
    }

    function saveAll() {
      // Safety net: if a URL is still sitting in the box (Add wasn't clicked),
      // add it to the top before saving so nothing typed gets lost.
      addNewUrl();
      document.getElementById('urlsCsv').value = existingUrls.join(',');
      document.getElementById('uploadForm').submit();
    }

    renderGrid();
  </script>

<?php endif; ?>

</body>
</html>
