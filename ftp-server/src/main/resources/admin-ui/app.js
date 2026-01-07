/* FTP Admin UI
 *
 * Uses the admin HTTP API served by ftp-server on :9090 under /api/*.
 * Auth: Authorization: Bearer <token> (token is stored in server DB, you enter it in the UI).
 */

let currentToken = "";
let offline = false;

const el = {
  view: document.getElementById("view"),
  banner: document.getElementById("banner"),
  tokenInput: document.getElementById("tokenInput"),
  saveTokenBtn: document.getElementById("saveTokenBtn"),
  modal: document.getElementById("modal"),
  modalTitle: document.getElementById("modalTitle"),
  modalBody: document.getElementById("modalBody"),
  modalActions: document.getElementById("modalActions"),
  navLinks: [...document.querySelectorAll(".navlink")]
};

function getToken() {
  return (currentToken || "").trim();
}

function setToken(t) {
  currentToken = (t || "").trim();
}

function showBanner(type, msg) {
  el.banner.hidden = false;
  el.banner.className = "banner " + (type || "");
  el.banner.textContent = msg || "";
}

function clearBanner() {
  el.banner.hidden = true;
  el.banner.className = "banner";
  el.banner.textContent = "";
}

function ensureOfflineOverlay() {
  let ov = document.getElementById("offlineOverlay");
  if (ov) return ov;
  ov = document.createElement("div");
  ov.id = "offlineOverlay";
  ov.className = "offlineOverlay";
  ov.innerHTML = `
    <div class="offlineCard">
      <div class="offlineTitle">Server is offline</div>
      <div class="offlineText" id="offlineText">The server is not reachable. Start the server again to continue.</div>
      <div class="toolbar" style="justify-content:flex-end;">
        <button id="offlineRetry" class="btn btn-primary" type="button">Retry</button>
      </div>
    </div>
  `;
  document.body.appendChild(ov);
  ov.querySelector("#offlineRetry").addEventListener("click", async () => {
    await pingServer(true);
  });
  return ov;
}

function setOfflineState(isOffline, reason) {
  offline = !!isOffline;
  const ov = ensureOfflineOverlay();
  const text = ov.querySelector("#offlineText");
  if (text) text.textContent = reason || "The server is not reachable. Start the server again to continue.";
  ov.classList.toggle("show", offline);
}

async function pingServer(showOverlayOnFail) {
  try {
    await apiFetchNoAuth("/api/admin-token");
    if (offline) {
      setOfflineState(false);
      // After server returns, we must request token again for this session
      await bootstrapAuth();
    }
    return true;
  } catch (e) {
    if (showOverlayOnFail) setOfflineState(true, "The server is offline. Start it again to continue.");
    return false;
  }
}

function fmtBytes(n) {
  const v = Number(n || 0);
  if (v < 1024) return `${v} B`;
  const kb = v / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1)} MB`;
  const gb = mb / 1024;
  return `${gb.toFixed(2)} GB`;
}

async function apiFetch(path, opts = {}) {
  if (offline) {
    throw new Error("Server is offline");
  }
  const headers = new Headers(opts.headers || {});
  const t = getToken();
  if (t) headers.set("Authorization", `Bearer ${t}`);
  if (opts.json !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  let resp;
  try {
    resp = await fetch(path, {
      method: opts.method || "GET",
      headers,
      body: opts.json !== undefined ? JSON.stringify(opts.json) : opts.body
    });
  } catch (e) {
    setOfflineState(true, "The server is offline. Start it again to continue.");
    throw new Error("Server is offline");
  }

  const ct = resp.headers.get("content-type") || "";
  const isJson = ct.includes("application/json");
  const text = isJson ? null : await resp.text().catch(() => "");
  const data = isJson ? await resp.json().catch(() => null) : null;

  if (!resp.ok) {
    const bodyMsg = isJson ? (data ? JSON.stringify(data) : "") : (text || "");
    const suffix = bodyMsg ? `: ${bodyMsg}` : "";
    if (resp.status === 401) {
      // Token invalid / missing → force re-enter
      setToken("");
      el.tokenInput.value = "";
      await ensureAdminTokenPrompt();
    }
    throw new Error(`HTTP ${resp.status}${suffix}`);
  }
  return isJson ? data : text;
}

async function apiFetchNoAuth(path, opts = {}) {
  const headers = new Headers(opts.headers || {});
  if (opts.json !== undefined) headers.set("Content-Type", "application/json");
  let resp;
  try {
    resp = await fetch(path, {
      method: opts.method || "GET",
      headers,
      body: opts.json !== undefined ? JSON.stringify(opts.json) : opts.body
    });
  } catch (e) {
    setOfflineState(true, "The server is offline. Start it again to continue.");
    throw new Error("Server is offline");
  }
  const ct = resp.headers.get("content-type") || "";
  const isJson = ct.includes("application/json");
  const text = isJson ? null : await resp.text().catch(() => "");
  const data = isJson ? await resp.json().catch(() => null) : null;
  if (!resp.ok) {
    const bodyMsg = isJson ? (data ? JSON.stringify(data) : "") : (text || "");
    const suffix = bodyMsg ? `: ${bodyMsg}` : "";
    throw new Error(`HTTP ${resp.status}${suffix}`);
  }
  return isJson ? data : text;
}

async function ensureAdminTokenPrompt() {
  // Avoid stacking modals
  try { if (el.modal.open) return; } catch (_) {}

  let tokenSet = true;
  try {
    const st = await apiFetchNoAuth("/api/admin-token");
    tokenSet = !!(st && st.tokenSet);
  } catch (_) {
    tokenSet = true;
  }

  let suggestedFtpRoot = "";
  if (!tokenSet) {
    try {
      const bi = await apiFetchNoAuth("/api/bootstrap");
      suggestedFtpRoot = (bi && bi.suggestedFtpRoot) ? String(bi.suggestedFtpRoot) : "";
    } catch (_) {
      suggestedFtpRoot = "";
    }
  }

  openModal({
    title: tokenSet ? "Enter admin token" : "Set admin token",
    bodyHtml: `
      <div>
        <label class="label" for="adminTokenPrompt">${tokenSet ? "Admin token" : "New admin token"}</label>
        <input id="adminTokenPrompt" class="field" type="text" autocomplete="off" placeholder="token" />
        <div class="muted" style="margin-top:6px; color:#999;">
          ${tokenSet ? "Enter the current token to access the admin UI." : "First-time setup: choose a token. You will need it next time."}
        </div>
      </div>
      ${tokenSet ? "" : `
      <div style="margin-top:14px;">
        <label class="label" for="ftpRootPrompt">FTP root path (ftp-root)</label>
        <input id="ftpRootPrompt" class="field" type="text" autocomplete="off"
               placeholder="e.g. D:\\\\ftp-root"
               value="${escapeHtml(suggestedFtpRoot)}" />
        <div class="muted" style="margin-top:6px; color:#999;">
          This will be applied after server restart. The folder must be writable.
        </div>
      </div>
      `}
    `,
    actionsHtml: `
      <button id="adminTokenApply" class="btn btn-primary" type="button">Continue</button>
    `,
    onOpen: () => {
      const input = document.getElementById("adminTokenPrompt");
      input.focus();
      document.getElementById("adminTokenApply").addEventListener("click", async () => {
        clearBanner();
        const tok = (input.value || "").trim();
        if (!tok) {
          showBanner("warn", "Token is required");
          return;
        }
        try {
          if (!tokenSet) {
            const ftpRootEl = document.getElementById("ftpRootPrompt");
            const ftpRoot = (ftpRootEl ? ftpRootEl.value : "").trim();
            if (!ftpRoot) {
              showBanner("warn", "FTP root path is required");
              return;
            }
            const res = await apiFetchNoAuth("/api/bootstrap", { method: "PUT", json: { token: tok, ftpRoot } });
            // res.restartRequired = true
            showBanner("warn", "Setup saved. Restart the server to apply the new ftp-root path.");
          }
          setToken(tok);
          el.tokenInput.value = tok;
          closeModal();
          location.hash = "#/users";
          route();
        } catch (e) {
          showBanner("err", `Failed to apply token: ${e.message}`);
        }
      });
    }
  });
}

function escapeHtml(s) {
  return String(s || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function route() {
  const hash = location.hash || "#/users";
  const r = hash.replace(/^#\//, "");
  const routeName = (r.split("?")[0] || "users").trim();
  for (const a of el.navLinks) {
    a.classList.toggle("active", a.dataset.route === routeName);
  }
  clearBanner();
  switch (routeName) {
    case "users": return renderUsers();
    case "limits": return renderLimits();
    case "permissions": return renderPermissions();
    case "stats": return renderStats();
    default:
      location.hash = "#/users";
      return;
  }
}

async function bootstrapAuth() {
  // Always request token on entry (do not persist between page loads).
  setToken("");
  el.tokenInput.value = "";
  let tokenSet = true;
  try {
    const st = await apiFetchNoAuth("/api/admin-token");
    tokenSet = !!(st && st.tokenSet);
  } catch (_) {
    tokenSet = true;
  }
  // If token is not set in DB, this will be "Set admin token", otherwise "Enter admin token".
  await ensureAdminTokenPrompt();
}

function setView(html) {
  el.view.innerHTML = html;
}

function qs(sel) {
  return el.view.querySelector(sel);
}

function qsa(sel) {
  return [...el.view.querySelectorAll(sel)];
}

function openModal({ title, bodyHtml, actionsHtml, onOpen }) {
  el.modalTitle.textContent = title || "Dialog";
  el.modalBody.innerHTML = bodyHtml || "";
  el.modalActions.innerHTML = actionsHtml || "";
  el.modal.showModal();
  if (onOpen) onOpen();
}

function closeModal() {
  try { el.modal.close(); } catch (_) {}
}

/* =========================
 * USERS
 * ========================= */

async function renderUsers() {
  setView(`
    <section class="card">
      <div class="cardhead">
        <div>
          <div class="cardtitle">Users</div>
          <div class="cardsub">Create, enable/disable, set per-user rate limit</div>
        </div>
        <div class="toolbar">
          <button id="createUserBtn" class="btn btn-primary" type="button">Create user</button>
          <button id="refreshUsersBtn" class="btn" type="button">Refresh</button>
        </div>
      </div>
      <div class="cardbody">
        <div class="block" id="usersHintBlock">
          <div class="hint" id="usersHint">Loading...</div>
        </div>
        <div class="block tablewrap">
          <table>
            <thead>
              <tr>
                <th style="width: 50%;">Username</th>
                <th>Enabled</th>
                <th style="width: 1%;">Actions</th>
              </tr>
            </thead>
            <tbody id="usersTbody"></tbody>
          </table>
        </div>
      </div>
    </section>
  `);

  qs("#createUserBtn").addEventListener("click", openCreateUser);
  qs("#refreshUsersBtn").addEventListener("click", loadUsers);
  await loadUsers();
}

async function loadUsers() {
  const hint = qs("#usersHint");
  const hintBlock = qs("#usersHintBlock");
  const tbody = qs("#usersTbody");
  hint.textContent = "Loading...";
  hintBlock.hidden = false;
  tbody.innerHTML = "";
  try {
    const users = await apiFetch("/api/users");
    hint.textContent = users.length ? "" : "No users";
    hintBlock.hidden = !hint.textContent;
    tbody.innerHTML = users.map(u => `
      <tr data-username="${escapeHtmlAttr(u.username)}">
        <td>${escapeHtml(u.username)}</td>
        <td>${u.enabled ? `true` : `false`}</td>
        <td class="cell-actions">
          <button class="btn btn-secondary" data-action="edit" type="button">Edit</button>
          <button class="btn btn-danger" data-action="delete" type="button">Delete</button>
        </td>
      </tr>
    `).join("");

    qsa("button[data-action='edit']").forEach(btn => btn.addEventListener("click", () => {
      const tr = btn.closest("tr");
      openEditUser(tr.dataset.username);
    }));
    qsa("button[data-action='delete']").forEach(btn => btn.addEventListener("click", () => {
      const tr = btn.closest("tr");
      deleteUser(tr.dataset.username);
    }));
  } catch (e) {
    hint.textContent = "No data";
    hintBlock.hidden = !hint.textContent;
    showBanner("err", `Failed to load users: ${e.message}`);
  }
}
                                   
function openCreateUser() {
  openModal({
    title: "Create user",
    bodyHtml: `
      <div class="grid2">
        <div>
          <label class="label" for="newUsername">Username</label>
          <input id="newUsername" class="field" type="text" placeholder="username" />
        </div>
        <div>
          <label class="label" for="newPassword">Password</label>
          <input id="newPassword" class="field" type="password" placeholder="password" />
        </div>
      </div>
    `,
    actionsHtml: `
      <button class="btn" type="button" onclick="closeModal()">Cancel</button>
      <button id="createUserConfirm" class="btn btn-primary" type="button">Create</button>
    `,
    onOpen: () => {
      const u = document.getElementById("newUsername");
      const p = document.getElementById("newPassword");
      document.getElementById("createUserConfirm").addEventListener("click", async () => {
        clearBanner();
        const username = (u.value || "").trim();
        const password = (p.value || "").trim();
        if (!username || !password) {
          showBanner("warn", "Username and password are required");
          return;
        }
        try {
          await apiFetch("/api/users", { method: "POST", json: { username, password } });
          closeModal();
          showBanner("ok", `User "${username}" created`);
          await loadUsers();
        } catch (e) {
          showBanner("err", `Failed to create user: ${e.message}`);
        }
      });
    }
  });
}

async function openEditUser(username) {
  openModal({
    title: `Edit user: ${username}`,
    bodyHtml: `
      <div class="grid2">
        <div>
          <label class="label" for="editEnabled">Enabled</label>
          <select id="editEnabled" class="field">
            <option value="true">true</option>
            <option value="false">false</option>
          </select>
        </div>
        <div>
          <label class="label" for="editRateLimit">Rate limit (bytes/sec, optional)</label>
          <input id="editRateLimit" class="field" type="number" min="0" placeholder="null = use server limit" />
          <div class="muted" style="margin-top:6px; color: #999;">Leave empty to use server limit</div>
        </div>
      </div>
    `,
    actionsHtml: `
      <button class="btn" type="button" onclick="closeModal()">Cancel</button>
      <button id="saveUserConfirm" class="btn btn-primary" type="button">Save</button>
    `,
    onOpen: async () => {
      const enabledSel = document.getElementById("editEnabled");
      const rateInp = document.getElementById("editRateLimit");
      const saveBtn = document.getElementById("saveUserConfirm");

      saveBtn.disabled = true;
      try {
        const detail = await apiFetch(`/api/users/${encodeURIComponent(username)}`);
        enabledSel.value = String(!!detail.enabled);
        rateInp.value = detail.rateLimit === null || detail.rateLimit === undefined ? "" : String(detail.rateLimit);
      } catch (e) {
        showBanner("warn", `Could not load user details: ${e.message}`);
      } finally {
        saveBtn.disabled = false;
      }

      saveBtn.addEventListener("click", async () => {
        clearBanner();
        const enabled = enabledSel.value === "true";
        const rateRaw = (rateInp.value || "").trim();
        const rateLimit = rateRaw === "" ? null : Number(rateRaw);
        if (rateLimit !== null && (!Number.isFinite(rateLimit) || rateLimit < 0)) {
          showBanner("warn", "Rate limit must be a non-negative number or empty");
          return;
        }
        try {
          await apiFetch(`/api/users/${encodeURIComponent(username)}`, {
            method: "PUT",
            json: rateLimit === null ? { enabled } : { enabled, rateLimit }
          });
          closeModal();
          showBanner("ok", `User "${username}" updated`);
          await loadUsers();
        } catch (e) {
          showBanner("err", `Failed to update user: ${e.message}`);
        }
      });
    }
  });
}

async function deleteUser(username) {
  clearBanner();
  if (!confirm(`Delete user "${username}"?`)) return;
  try {
    await apiFetch(`/api/users/${encodeURIComponent(username)}`, { method: "DELETE" });
    showBanner("ok", `User "${username}" deleted`);
    await loadUsers();
  } catch (e) {
    showBanner("err", `Failed to delete user: ${e.message}`);
  }
}

/* =========================
 * LIMITS
 * ========================= */

async function renderLimits() {
  setView(`
    <section class="card">
      <div class="cardhead">
        <div>
          <div class="cardtitle">Server limits</div>
          <div class="cardsub">Global max connections and rate limits</div>
        </div>
        <div class="toolbar">
          <button id="refreshLimitsBtn" class="btn" type="button">Refresh</button>
          <button id="saveLimitsBtn" class="btn btn-primary" type="button">Apply</button>
        </div>
      </div>
      <div class="cardbody">
        <div class="block">
          <div class="grid2">
            <div>
              <label class="label" for="maxConn">Global max connections</label>
              <input id="maxConn" class="field" type="number" min="1" />
            </div>
            <div>
              <label class="label" for="rateLimit">Global rate limit (bytes/sec)</label>
              <input id="rateLimit" class="field" type="number" min="0" />
              <div id="rateHint" class="hint" style="margin-top:6px;"></div>
            </div>
          </div>
        </div>
      </div>
    </section>
  `);

  qs("#refreshLimitsBtn").addEventListener("click", loadLimits);
  qs("#saveLimitsBtn").addEventListener("click", saveLimits);
  await loadLimits();
}

async function loadLimits() {
  clearBanner();
  const maxConn = qs("#maxConn");
  const rateLimit = qs("#rateLimit");
  const rateHint = qs("#rateHint");
  rateHint.textContent = "Loading...";
  try {
    const l = await apiFetch("/api/limits");
    maxConn.value = String(l.globalMaxConnections ?? "");
    rateLimit.value = String(l.globalRateLimit ?? "");
    rateHint.textContent = `~ ${fmtBytes(l.globalRateLimit)}/s`;
  } catch (e) {
    rateHint.textContent = "";
    showBanner("err", `Failed to load limits: ${e.message}`);
  }
}

async function saveLimits() {
  clearBanner();
  const maxConn = Number(qs("#maxConn").value);
  const rateLimit = Number(qs("#rateLimit").value);
  if (!Number.isFinite(maxConn) || maxConn < 1) {
    showBanner("warn", "Global max connections must be >= 1");
    return;
  }
  if (!Number.isFinite(rateLimit) || rateLimit < 0) {
    showBanner("warn", "Global rate limit must be >= 0");
    return;
  }
  try {
    await apiFetch("/api/limits", {
      method: "PUT",
      json: { 
        globalMaxConnections: maxConn, 
        globalRateLimit: rateLimit
      }
    });
    showBanner("ok", "Limits applied");
    await loadLimits();
  } catch (e) {
    showBanner("err", `Failed to apply limits: ${e.message}`);
  }
}

/* =========================
 * PERMISSIONS
 * ========================= */

async function renderPermissions() {
  setView(`
    <section class="card">
      <div class="cardhead">
        <div>
          <div class="cardtitle">Permissions</div>
          <div class="cardsub">Global permissions and shared folders (user-to-user)</div>
        </div>
        <div class="toolbar">
          <button id="permRefreshBtn" class="btn" type="button">Refresh</button>
          <button id="permSaveBtn" class="btn btn-primary" type="button">Save global</button>
        </div>
      </div>
      <div class="cardbody">
        <div class="block">
          <div class="sectiontitle">Global permissions</div>
          <div class="sectiondesc">Applies to all folders for the selected user.</div>
          <div class="sectionbody">
            <label class="label" for="permUser">User</label>
            <select id="permUser" class="field" style="width: 320px;"></select>
          </div>
          <div class="sectionbody">
            <div class="checks">
              <label class="check"><input type="checkbox" id="gR" /> R</label>
              <label class="check"><input type="checkbox" id="gW" /> W</label>
              <label class="check"><input type="checkbox" id="gE" /> E</label>
            </div>
          </div>
        </div>

        <div class="block">
          <div class="sectiontitle">Shared folders (user-to-user)</div>
          <div class="sectiondesc">Folders that other users shared with the selected user.</div>
          <div class="sectionbody toolbar" style="justify-content:flex-start;">
            <button id="sharedRefreshBtn" class="btn" type="button">Refresh shared</button>
          </div>
          <div class="sectionbody">
            <div class="hint" id="sharedHint">Select user to load shared folders</div>
          </div>
        </div>

        <div class="block tablewrap">
          <table>
            <thead>
              <tr>
                <th style="width: 18%;">Owner</th>
                <th style="width: 20%;">Name</th>
                <th style="width: 42%;">Path</th>
                <th>W</th>
                <th>E</th>
                <th style="width: 1%;">Actions</th>
              </tr>
            </thead>
            <tbody id="sharedTbody"></tbody>
          </table>
        </div>
      </div>
    </section>
  `);

  qs("#permRefreshBtn").addEventListener("click", loadPermissionsForSelected);
  qs("#permSaveBtn").addEventListener("click", () => {
    if (confirm("Confirm and save global permissions?")) saveGlobalPermissions();
  });
  qs("#permUser").addEventListener("change", loadPermissionsForSelected);

  qs("#sharedRefreshBtn").addEventListener("click", loadSharedFoldersForSelected);

  await loadUsersIntoPermSelect();
}

async function loadUsersIntoPermSelect() {
  const sel = qs("#permUser");
  sel.innerHTML = `<option value="">-- select --</option>`;
  try {
    const users = await apiFetch("/api/users");
    for (const u of users) {
      const opt = document.createElement("option");
      opt.value = u.username;
      opt.textContent = u.username;
      sel.appendChild(opt);
    }
  } catch (e) {
    showBanner("err", `Failed to load users: ${e.message}`);
  }
}

async function loadPermissionsForSelected() {
  clearBanner();
  const username = qs("#permUser").value;
  const sharedHint = qs("#sharedHint");
  const sharedTbody = qs("#sharedTbody");
  if (!username) {
    qs("#gR").checked = false;
    qs("#gW").checked = false;
    qs("#gE").checked = false;

    sharedHint.textContent = "Select user to load shared folders";
    sharedTbody.innerHTML = "";
    return;
  }

  try {
    const gp = await apiFetch(`/api/user-permissions?user=${encodeURIComponent(username)}`)
      .catch(() => ({ read: false, write: false, execute: false }));

    qs("#gR").checked = !!gp.read;
    qs("#gW").checked = !!gp.write;
    qs("#gE").checked = !!gp.execute;
  } catch (e) {
    showBanner("err", `Failed to load global permissions: ${e.message}`);
  }

  // Load shared folders separately (not blocking folder perms UI)
  loadSharedFoldersForSelected();
}

async function saveGlobalPermissions() {
  clearBanner();
  const username = qs("#permUser").value;
  if (!username) {
    showBanner("warn", "Select user first");
    return;
  }

  const gp = {
    username,
    read: qs("#gR").checked,
    write: qs("#gW").checked,
    execute: qs("#gE").checked
  };

  try {
    await apiFetch("/api/user-permissions", { method: "POST", json: gp });
    showBanner("ok", "Global permissions saved");
    await loadPermissionsForSelected();
  } catch (e) {
    showBanner("err", `Failed to save global permissions: ${e.message}`);
  }
}

/* =========================
 * SHARED FOLDERS (user-to-user)
 * ========================= */

async function loadSharedFoldersForSelected() {
  clearBanner();
  const username = qs("#permUser")?.value;
  const hint = qs("#sharedHint");
  const tbody = qs("#sharedTbody");
  if (!hint || !tbody) return;

  if (!username) {
    hint.textContent = "Select user to load shared folders";
    tbody.innerHTML = "";
    return;
  }

  hint.textContent = "Loading...";
  tbody.innerHTML = "";

  try {
    const shared = await apiFetch(`/api/shared-folders?username=${encodeURIComponent(username)}`);
    hint.textContent = shared.length ? "" : "No shared folders";
    tbody.innerHTML = shared.map((s, idx) => `
      <tr data-idx="${idx}">
        <td>${escapeHtml(s.ownerUsername || "")}</td>
        <td>${escapeHtml(s.folderName || "")}</td>
        <td>${escapeHtml(s.folderPath || "")}</td>
        <td style="text-align:center;">${s.write ? "true" : "false"}</td>
        <td style="text-align:center;">${s.execute ? "true" : "false"}</td>
        <td class="cell-actions">
          <button class="btn btn-danger" data-action="delete-share" type="button">Delete</button>
        </td>
      </tr>
    `).join("");

    tbody._data = shared;
    qsa("button[data-action='delete-share']").forEach(btn => btn.addEventListener("click", () => {
      const tr = btn.closest("tr");
      const i = Number(tr?.dataset.idx);
      if (!Number.isFinite(i)) return;
      const row = tbody._data[i];
      deleteSharedFolder(row?.folderPath);
    }));
  } catch (e) {
    hint.textContent = "No data";
    showBanner("err", `Failed to load shared folders: ${e.message}`);
  }
}

async function deleteSharedFolder(folderPath) {
  if (!folderPath) return;
  clearBanner();
  if (!confirm(`Delete shared folder entries for path "${folderPath}"?`)) return;
  try {
    await apiFetch(`/api/shared-folders/delete?folderPath=${encodeURIComponent(folderPath)}`, { method: "DELETE" });
    showBanner("ok", "Shared folder deleted");
    await loadSharedFoldersForSelected();
  } catch (e) {
    showBanner("err", `Failed to delete shared folder: ${e.message}`);
  }
}

/* =========================
 * STATS
 * ========================= */

async function renderStats() {
  setView(`
    <section class="card">
      <div class="cardhead">
        <div>
          <div class="cardtitle">Stats</div>
          <div class="cardsub">Live connections and traffic</div>
        </div>
        <div class="toolbar">
          <button id="refreshStatsBtn" class="btn" type="button">Refresh</button>
        </div>
      </div>
      <div class="cardbody">
        <div class="block">
          <div class="hint" id="statsHint">Loading...</div>
          <div class="hint" id="statsConnectedNow"></div>
        </div>
        <div class="block tablewrap">
          <table>
            <thead>
              <tr>
                <th style="width: 24%;">Username</th>
                <th>Connections now</th>
                <th>Uploaded</th>
                <th>Downloaded</th>
                <th style="width: 28%;">Last login</th>
              </tr>
            </thead>
            <tbody id="statsTbody"></tbody>
          </table>
        </div>
      </div>
    </section>
  `);
  qs("#refreshStatsBtn").addEventListener("click", loadStats);
  await loadStats();
}

async function loadStats() {
  clearBanner();
  const hint = qs("#statsHint");
  const connectedNow = qs("#statsConnectedNow");
  const tbody = qs("#statsTbody");
  hint.textContent = "Loading...";
  connectedNow.textContent = "";
  tbody.innerHTML = "";
  try {
    const live = await apiFetch("/api/stats/live");
    const users = live?.users || [];
    const connectedUsers = Number(live?.connectedUsers ?? 0);
    const totalConnections = Number(live?.totalConnections ?? 0);

    hint.textContent = users.length ? "" : "No data";
    connectedNow.textContent = `Connected now: ${connectedUsers} users (${totalConnections} connections)`;

    tbody.innerHTML = users.map(s => `
      <tr>
        <td>
          <span class="nick">
            <span class="dot ${s.connected ? "dot-green" : "dot-red"}"></span>
            <span>${escapeHtml(s.username)}</span>
          </span>
        </td>
        <td>${Number(s.connections || 0)}</td>
        <td>${fmtBytes(s.bytesUploaded || 0)}</td>
        <td>${fmtBytes(s.bytesDownloaded || 0)}</td>
        <td>${escapeHtml(s.lastLogin || "")}</td>
      </tr>
    `).join("");
  } catch (e) {
    hint.textContent = "No data";
    connectedNow.textContent = "";
    showBanner("err", `Failed to load stats: ${e.message}`);
  }
}

/* =========================
 * Helpers
 * ========================= */

function escapeHtml(s) {
  return String(s ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function escapeHtmlAttr(s) {
  return escapeHtml(s).replaceAll("`", "&#96;");
}

/* =========================
 * Boot
 * ========================= */

function init() {
  el.tokenInput.value = getToken();

  el.saveTokenBtn.addEventListener("click", async () => {
    clearBanner();
    const tok = (el.tokenInput.value || "").trim();
    if (!tok) {
      showBanner("warn", "Token is required");
      return;
    }
    try {
      // Save token on server (DB). After this, old tokens will stop working.
      await apiFetchNoAuth("/api/admin-token", { method: "PUT", json: { token: tok } });
      setToken(tok);
      showBanner("ok", "Token saved (server DB updated)");
      location.hash = "#/users";
      route();
    } catch (e) {
      showBanner("err", `Failed to save token: ${e.message}`);
    }
  });

  window.addEventListener("hashchange", route);
  if (!location.hash) location.hash = "#/users";

  // On entry: require token (and if not initialized yet, set it once)
  bootstrapAuth().finally(() => route());

  // Keep checking server health; if it goes down, show offline overlay and block actions.
  setInterval(() => pingServer(true), 2000);
}

init();


