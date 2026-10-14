'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let authorization = '', roles = [], currentId = '', generation = 0, recordVersion = 0;
  const active = new Set();
  let identitySession = false, searchVersion = 0, nextCursor = '';
  const recent = [];
  let verifiedFile = null, alertSummary = null;
  let summaryVersion = 0, batchVersion = 0, batchRows = [];
  const effectiveStatus = record => record.effectiveStatus || (record.revoked ? 'REVOKED' : record.metadata?.expiresAt && Date.parse(record.metadata.expiresAt) <= Date.now() ? 'EXPIRED' : 'ACTIVE');
  function recordFromLink() { return /^#document=([A-Z0-9-]{3,50})$/.exec(location.hash)?.[1] || ''; }
  let trackingVersion = 0, trackingTimer, confirmedId = '';
  function stopTracking() { trackingVersion++; clearTimeout(trackingTimer); $('tracking-note').textContent = ''; }
  const max = 10 * 1024 * 1024;
  function show(id, text, tone = '') {
    const el = $(id); el.textContent = text; el.className = `result ${tone}`; el.hidden = false;
  }
  async function request(path, options = {}) {
    const ticket = generation;
    const controller = new AbortController(); active.add(controller);
    const deadline = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(path, { ...options, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal,
        headers: { ...options.headers, ...(authorization ? { Authorization: authorization } : {}) } });
      if (response.status === 401 && !$('workspace').hidden && ticket === generation) {
        signout(); show('notice', 'Your session has expired or is no longer accepted. Connect again to continue.', 'warning');
        throw new DOMException('Authentication ended', 'AbortError');
      }
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(`${response.status}: ${error.message || 'Request could not be completed.'}`);
      }
      const body = await response.arrayBuffer();
      if (ticket !== generation) throw new DOMException('Signed out', 'AbortError');
      return new Response(body, { status: response.status, headers: response.headers });
    } catch (error) {
      if (controller.signal.aborted && ticket === generation) {
        throw new Error('Request timed out. For an upload, check registration status using the same document ID before retrying.');
      }
      throw error;
    } finally { clearTimeout(deadline); active.delete(controller); }
  }
  function fileCheck(file) {
    if (!file || !file.size) throw new Error('Choose a nonempty file.');
    if (file.size > max) throw new Error('File exceeds the 10 MiB limit.');
  }
  function bind(id, action) {
    $(id).addEventListener('submit', async event => {
      event.preventDefault();
      if ($(id).getAttribute('aria-busy') === 'true') return;
      $(id).setAttribute('aria-busy', 'true'); const ticket = generation;
      $('notice').hidden = true;
      const buttons = [...$(id).querySelectorAll('button')]; buttons.forEach(b => b.disabled = true);
      try { await action(); } catch (e) {
        if (ticket === generation && e.name !== 'AbortError') show('notice', e.message, 'error');
      } finally { $(id).setAttribute('aria-busy', 'false'); buttons.forEach(b => b.disabled = false); if (id === 'batch-verify') $('batch-cancel').disabled = true; }
    });
  }
  function clearRecord() {
    verifiedFile = null; $('receipt').hidden = true;
    recordVersion++; currentId = ''; $('record').hidden = true; $('record-details').replaceChildren();
    $('verification').hidden = true; $('history-section').hidden = true; $('history-result').textContent = '';
    $('verify').reset(); $('revoke').reset(); $('share-section').hidden = true; $('share-link').value = '';
    $('history-timeline').replaceChildren(); $('history-summary').textContent = '';
  }
  function signout(keepIdentity = false) {
    if (!keepIdentity) window.dispatchEvent(new Event('registry-identity-clear'));
    identitySession = false; recent.length = 0; searchVersion++; nextCursor = '';
    $('recent-documents').replaceChildren(); $('search-results').replaceChildren(); $('search-more').hidden = true;
    summaryVersion++; batchVersion++; batchRows = []; $('batch-rows').replaceChildren(); $('batch-progress').textContent = ''; $('batch-cancel').disabled = true;
    $('summary-counts').replaceChildren(); $('summary-activity').replaceChildren(); $('summary-note').textContent = '';
    alertSummary = null; $('expiry-alerts').replaceChildren(); $('expiry-alert-note').textContent = '';
    stopTracking(); confirmedId = ''; generation++; active.forEach(c => c.abort()); authorization = ''; roles = []; clearRecord();
    document.querySelectorAll('form').forEach(f => f.reset());
    $('workspace').hidden = true; $('connection').hidden = false; $('notice').hidden = true;
    $('open-registered').hidden = true; $('upload-result').hidden = true; $('upload-result').textContent = '';
    $('roles').textContent = ''; $('citizen-reference').value = ''; $('citizen-account').hidden = true; modeChanged();
  }
  function modeChanged() {
    const basic = $('auth-mode').value === 'basic';
    $('basic-fields').hidden = !basic; $('token-field').hidden = basic;
    $('username').required = basic; $('password').required = basic; $('token').required = !basic;
    $('password').value = ''; $('token').value = '';
  }
  $('copy-citizen-reference').addEventListener('click', async () => {
    const ticket = generation;
    try { await navigator.clipboard.writeText($('citizen-reference').value); if (ticket === generation) show('notice', 'Citizen reference copied.'); }
    catch { if (ticket === generation) show('notice', 'Select and copy the citizen reference above.'); }
  });
  $('auth-mode').addEventListener('change', modeChanged); $('logout').addEventListener('click', () => { const remote = identitySession; signout(remote); if (remote) window.dispatchEvent(new Event('registry-identity-logout')); });
  window.addEventListener('pagehide', () => signout());
  bind('login', async () => {
    const mode = $('auth-mode').value;
    if (mode === 'basic') {
      const bytes = new TextEncoder().encode(`${$('username').value}:${$('password').value}`);
      authorization = 'Basic ' + btoa(String.fromCharCode(...bytes));
    } else authorization = 'Bearer ' + $('token').value.trim();
    $('password').value = ''; $('token').value = '';
    try {
      await connected(mode === 'bearer');
    } catch (error) { authorization = ''; throw error; }
  });
  async function connected(bearer) {
    const session = await (await request('/api/session')).json(); roles = session.roles;
    $('roles').textContent = `Access: ${roles.join(', ')}`;
    $('citizen-reference').value = session.citizenReference || '';
    $('citizen-account').hidden = !session.citizenReference;
    $('connection').hidden = true; $('workspace').hidden = false;
    const reader = roles.some(r => ['READER', 'CITIZEN'].includes(r));
    $('issue-tab').hidden = !roles.includes('ISSUER'); $('owner-field').hidden = !bearer;
    $('dashboard-tab').hidden = !reader; $('batch-tab').hidden = !reader;
    if (reader) refreshSummary();
    const linked = recordFromLink();
    selectPanel(reader ? (linked ? 'lookup' : 'dashboard') : roles.includes('ISSUER') ? 'issue' : 'lookup');
    const ticket = generation;
    if (reader && linked) {
      $('lookup-id').value = linked;
      try { await loadRecord(linked); } catch (error) { if (ticket === generation) show('notice', error.message, 'error'); }
    } else if (reader) searchPage('').catch(error => { if (ticket === generation) show('notice', error.message, 'error'); });
  }
  window.addEventListener('registry-identity-token', async event => {
    authorization = 'Bearer ' + event.detail; identitySession = true;
    try { await connected(true); } catch (error) { signout(); show('notice', error.message, 'error'); }
  });
  window.addEventListener('registry-identity-error', event => show('notice', event.detail, 'error'));
  window.addEventListener('hashchange', async () => {
    const id = recordFromLink(); if (!authorization || !id) return;
    selectPanel('lookup'); $('lookup-id').value = id;
    try { await loadRecord(id); } catch (error) { show('notice', error.message, 'error'); }
  });
  async function searchPage(cursor) {
    const version = ++searchVersion;
    const params = new URLSearchParams({prefix:$('search-prefix').value, status:$('search-status').value, cursor});
    $('search-more').hidden = true;
    const result = await (await request('/api/documents?'+params)).json();
    if (version !== searchVersion) return;
    $('search-results').replaceChildren();
    for (const record of result.items) {
      const row = document.createElement('div'); row.className = 'document-row';
      const text = document.createElement('div'), title = document.createElement('strong'), detail = document.createElement('p');
      title.textContent = record.docId; detail.textContent = `${effectiveStatus(record)} · ${record.issuer} · ${record.createdAt || ''}`;
      const button = document.createElement('button'); button.className = 'secondary'; button.textContent = 'Open';
      button.addEventListener('click', () => openRecord(record.docId));
      text.append(title,detail); row.append(text,button); $('search-results').append(row);
    }
    nextCursor = result.nextCursor; $('search-more').hidden = !nextCursor;
    if (!result.items.length) $('search-results').textContent = nextCursor ? 'No matching records in this scanned page. Continue to the next page or narrow the prefix.' : 'No accessible documents match this search.';
  }
  async function refreshSummary() {
    const version = ++summaryVersion, ticket = generation;
    $('summary-note').textContent = 'Loading overview…';
    alertSummary = null; $('expiry-alerts').replaceChildren(); $('expiry-alert-note').textContent = '';
    $('summary-counts').replaceChildren(); $('summary-activity').replaceChildren();
    try {
      const summary = await (await request('/api/documents/summary')).json();
      if (version !== summaryVersion || ticket !== generation) return;
      $('summary-note').textContent = `${summary.complete ? 'All accessible records' : 'Partial overview: scan limit reached; totals and recent updates cover only the scanned records'}. Checked ${summary.asOf}. Refresh to update expiry status.`;
      alertSummary = summary; renderExpiryAlerts();
      for (const key of ['total','active','expired','revoked']) {
        const group = document.createElement('div'), label = document.createElement('dt'), value = document.createElement('dd');
        label.textContent = key[0].toUpperCase()+key.slice(1); value.textContent = summary.counts[key]; group.append(label,value); $('summary-counts').append(group);
      }
      for (const record of summary.recent) {
        const row = document.createElement('div'); row.className = 'document-row';
        const label = document.createElement('span'), button = document.createElement('button');
        label.textContent = `${record.docId} · ${record.revoked ? 'Revoked' : 'Issued'} · ${record.updatedAt} · ${effectiveStatus(record)}`;
        button.textContent = 'Open'; button.className = 'secondary'; button.addEventListener('click', () => openRecord(record.docId));
        row.append(label,button); $('summary-activity').append(row);
      }
      if (!summary.recent.length) $('summary-activity').textContent = 'No accessible document updates.';
    } catch (error) { if (version === summaryVersion && ticket === generation) $('summary-note').textContent = 'Overview unavailable. Use document search or refresh to retry.'; }
  }
  function renderExpiryAlerts() {
    $('expiry-alerts').replaceChildren();
    if (!alertSummary) return;
    const days = Number($('expiry-window').value), alerts = alertSummary.alerts;
    if (!alerts) { $('expiry-alert-note').textContent = 'Expiry alerts unavailable. Refresh the overview.'; return; }
    const end = Date.parse(alertSummary.asOf) + days * 86400000;
    const items = alerts.items.filter(record => Date.parse(record.metadata.expiresAt) <= end);
    const count = days === 7 ? alerts.within7Days : alerts.within30Days;
    $('expiry-alert-note').textContent = `${alertSummary.complete ? '' : 'Partial scan: '}${count} accessible active document(s) expire within ${days} days. Showing ${items.length}${count > items.length ? ' earliest' : ''}. Refresh overview to update.`;
    for (const record of items) {
      const row = document.createElement('div'), label = document.createElement('span'), button = document.createElement('button'); row.className = 'document-row';
      label.textContent = `${record.docId} · Expires ${record.metadata.expiresAt}`;
      button.textContent = 'Open'; button.className = 'secondary'; button.addEventListener('click', () => openRecord(record.docId));
      row.append(label,button); $('expiry-alerts').append(row);
    }
  }
  $('expiry-window').addEventListener('change', renderExpiryAlerts);
  bind('refresh-summary', refreshSummary);
  $('batch-files').addEventListener('change', () => {
    batchVersion++; batchRows = []; $('batch-rows').replaceChildren(); $('batch-progress').textContent = '';
    const files = [...$('batch-files').files];
    if (files.length > 20) { $('batch-files').value = ''; show('notice', 'Select at most 20 files.', 'error'); return; }
    for (const file of files) {
      const row = document.createElement('div'); row.className = 'batch-row';
      const label = document.createElement('label'), input = document.createElement('input'), result = document.createElement('p');
      label.textContent = file.name + ' — Document ID'; input.required = true; input.pattern = '[A-Z0-9-]{3,50}'; input.maxLength = 50;
      const suggested = file.name.replace(/\.[^.]+$/, ''); input.value = /^[A-Z0-9-]{3,50}$/.test(suggested) ? suggested : '';
      result.textContent = 'Not checked'; result.setAttribute('role','status'); label.append(input); row.append(label,result); $('batch-rows').append(row);
      batchRows.push({file,input,result});
    }
  });
  $('batch-cancel').addEventListener('click', () => { batchVersion++; $('batch-progress').textContent = 'Cancelled. Any in-progress request may finish, but no further files will be sent.'; $('batch-cancel').disabled = true; });
  bind('batch-verify', async () => {
    if (!batchRows.length) throw new Error('Select files to verify.');
    const version = ++batchVersion, ticket = generation, rows = batchRows.slice();
    for (const row of rows) fileCheck(row.file);
    rows.forEach(row => { row.result.textContent = 'Waiting'; row.input.disabled = true; });
    $('batch-files').disabled = true; $('batch-cancel').disabled = false;
    let completed = 0;
    try {
      for (const row of rows) {
        if (version !== batchVersion || ticket !== generation) break;
        row.result.textContent = 'Checking…';
        const data = new FormData(); data.append('file',row.file);
        try {
          const result = await (await request(`/api/documents/${encodeURIComponent(row.input.value)}/verify-file`, {method:'POST',body:data})).json();
          if (version !== batchVersion || ticket !== generation) break;
          if (!['VALID','EXPIRED','REVOKED','TAMPERED_OR_MISMATCH'].includes(result.status)) throw new Error('Unexpected response; validity could not be established.');
          row.result.textContent = result.status;
        } catch (error) {
          if (version !== batchVersion || ticket !== generation) break;
          row.result.textContent = `ERROR — ${error.message}`;
        }
        completed++; $('batch-progress').textContent = `${completed} of ${rows.length} files checked.`;
      }
    } finally {
      rows.forEach(row => { row.input.disabled = false; if (['Waiting','Checking…'].includes(row.result.textContent)) row.result.textContent = 'Not checked — cancelled'; });
      $('batch-files').disabled = false; $('batch-cancel').disabled = true;
    }
  });
  async function openRecord(id) {
    const ticket = generation; selectPanel('lookup'); $('lookup-id').value = id;
    try { await loadRecord(id); } catch (error) { if (ticket === generation) show('notice', error.message, 'error'); }
  }
  bind('search', () => searchPage(''));
  $('search-more').addEventListener('click', async () => {
    const cursor = nextCursor; if (!cursor) return;
    try { await searchPage(cursor); } catch (error) { show('notice', error.message, 'error'); }
  });
  for (const id of ['search-prefix','search-status']) $(id).addEventListener('input', () => { searchVersion++; nextCursor=''; $('search-more').hidden=true; $('search-results').replaceChildren(); });
  function selectPanel(name) {
    document.querySelectorAll('.task').forEach(p => p.hidden = p.id !== name);
    document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.panel === name));
    $('notice').hidden = true;
  }
  document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => selectPanel(b.dataset.panel)));
  async function loadRecord(id) {
    clearRecord(); const version = recordVersion; const record = await (await request(`/api/documents/${encodeURIComponent(id)}`)).json();
    if (version !== recordVersion) return;
    const index = recent.indexOf(id); if (index >= 0) recent.splice(index, 1); recent.unshift(id); recent.splice(6);
    $('recent-documents').replaceChildren();
    for (const entry of recent) { const button = document.createElement('button'); button.className='secondary'; button.textContent=entry; button.addEventListener('click', () => openRecord(entry)); $('recent-documents').append(button); }
    currentId = id; $('record-id').textContent = id; $('record-state').textContent = effectiveStatus(record);
    $('record-state').className = 'badge' + (effectiveStatus(record) !== 'ACTIVE' ? ' error' : '');
    for (const [label, value] of [['Issuing department', record.issuer], ['Issuer MSP', record.issuerMspId], ['Document digest', record.hash], ['Subject reference', record.subject], ['Expires at', record.metadata?.expiresAt], ['Revocation reason', record.revocationReason]]) {
      if (value === undefined || value === '') continue;
      const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = label; dd.textContent = String(value); $('record-details').append(dt, dd);
    }
    $('record').hidden = false; $('download').disabled = effectiveStatus(record) !== 'ACTIVE';
    $('history').hidden = !roles.includes('READER'); $('revoke-section').hidden = !!record.revoked || !roles.includes('REVOKER');
  }
  bind('find', () => loadRecord($('lookup-id').value));
  bind('verify', async () => {
    const id = currentId, version = recordVersion;
    verifiedFile = null; $('receipt').hidden = true;
    $('verification').hidden = true; const file = $('verify-file').files[0]; fileCheck(file);
    const data = new FormData(); data.append('file', file);
    const result = await (await request(`/api/documents/${encodeURIComponent(id)}/verify-file`, { method: 'POST', body: data })).json();
    if (version !== recordVersion || $('verify-file').files[0] !== file) return;
    const descriptions = { VALID: 'VALID — File bytes match the registered digest and the document is not revoked.', TAMPERED_OR_MISMATCH: 'TAMPERED_OR_MISMATCH — File bytes differ from the registered digest.', EXPIRED: 'EXPIRED — The document has reached its expiry time.', REVOKED: 'REVOKED — The issuer has revoked this document.' };
    if (!(result.status in descriptions)) throw new Error('Unexpected verification response. Validity could not be established.');
    show('verification', descriptions[result.status], result.status === 'VALID' ? '' : 'error');
    verifiedFile = file; $('receipt').hidden = false;
  });
  $('verify-file').addEventListener('change', () => { verifiedFile = null; $('receipt').hidden = true; $('verification').hidden = true; });
  bind('receipt', async () => {
    const id = currentId, version = recordVersion, file = verifiedFile;
    if (!file) throw new Error('Verify a file before requesting its receipt.');
    const data = new FormData(); data.append('file', file);
    const response = await request(`/api/documents/${encodeURIComponent(id)}/verification-receipt`, {method:'POST',body:data});
    const blob = await response.blob();
    if (version !== recordVersion || file !== verifiedFile) return;
    if (!response.headers.get('Content-Type')?.startsWith('application/pdf')) throw new Error('Receipt could not be generated.');
    const url = URL.createObjectURL(blob), anchor = document.createElement('a');
    anchor.href = url; anchor.download = id+'-verification.pdf'; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    show('notice', 'Receipt downloaded after a fresh verification. Its result may differ from the earlier check shown above.');
  });
  bind('revoke', async () => {
    const id = currentId;
    await request(`/api/documents/${encodeURIComponent(id)}/revoke`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason: $('reason').value }) });
    if (currentId === id) await loadRecord(id); show('notice', 'Document revoked. Its history remains available.');
  });
  function confirmed(id) {
    confirmedId = id;
    show('upload-result', `CONFIRMED — ${id} is registered on the ledger.`);
    $('open-registered').hidden = !roles.some(r => ['READER', 'CITIZEN'].includes(r));
  }
  async function registration(id, version, remaining = 15) {
    const state = await (await request(`/api/documents/${encodeURIComponent(id)}/registration`)).json();
    if (version !== trackingVersion) return;
    if (!['STAGING', 'PINNED', 'SUBMITTING', 'CONFIRMED', 'REJECTED'].includes(state.state)) {
      throw new Error('Unexpected registration status. Confirmation could not be established.');
    }
    if (state.state === 'CONFIRMED') { stopTracking(); confirmed(id); return; }
    if (state.state === 'REJECTED') {
      stopTracking(); show('upload-result', `REJECTED — ${id}. Review the record before retrying.`, 'error'); return;
    }
    show('upload-result', `${state.state} — ${id}. Ledger confirmation is pending.`, 'warning');
    if (remaining <= 0) {
      $('tracking-note').textContent = 'Automatic checks paused. You can check again. Keep this ID for any retry.';
      return;
    }
    $('tracking-note').textContent = 'Checking every 2 seconds, with up to 15 follow-up checks. You may sign out safely.';
    trackingTimer = setTimeout(async () => {
      if (version !== trackingVersion) return;
      try { await registration(id, version, remaining - 1); }
      catch (error) {
        if (version === trackingVersion) {
          stopTracking(); show('notice', error.message, 'error');
          $('tracking-note').textContent = 'Automatic checks stopped. Check again when the service is available.';
        }
      }
    }, 2000);
  }
  bind('upload', async () => {
    stopTracking(); confirmedId = ''; $('open-registered').hidden = true; $('upload-result').hidden = true;
    const data = new FormData($('upload')); fileCheck(data.get('file'));
    if (data.get('expiresAt')) data.set('expiresAt', new Date(data.get('expiresAt')).toISOString());
    if ($('owner-field').hidden) data.delete('ownerSubject');
    const id = data.get('docId'), version = trackingVersion;
    $('pending-id').value = id; $('lookup-id').value = id;
    const response = await request('/api/documents/upload', { method: 'POST', body: data });
    if (version !== trackingVersion) return;
    if (response.status === 202) {
      show('upload-result', `PENDING — ${id}. The upload is saved for recovery.`, 'warning');
      await registration(id, version);
    } else { confirmed(id); $('upload').reset(); }
  });
  bind('pending', async () => {
    stopTracking(); confirmedId = ''; $('open-registered').hidden = true; $('upload-result').hidden = true;
    await registration($('pending-id').value, trackingVersion);
  });
  $('pending-id').addEventListener('input', () => {
    stopTracking(); confirmedId = ''; $('open-registered').hidden = true; $('upload-result').hidden = true;
  });
  $('open-registered').addEventListener('click', async () => {
    if (!confirmedId) return;
    const id = confirmedId, ticket = generation; selectPanel('lookup'); $('lookup-id').value = id;
    try { await loadRecord(id); }
    catch (error) { if (ticket === generation && error.name !== 'AbortError') show('notice', error.message, 'error'); }
  });
  $('download').addEventListener('click', async () => {
    const ticket = generation; const id = currentId;
    try {
      const response = await request(`/api/documents/${encodeURIComponent(id)}/file`);
      const blob = await response.blob(); if (ticket !== generation) return;
      const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = id; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) { if (ticket === generation && e.name !== 'AbortError') show('notice', e.message, 'error'); }
  });
  $('history').addEventListener('click', async () => {
    const ticket = generation; const id = currentId;
    try {
      const result = await (await request(`/api/documents/${encodeURIComponent(id)}/history`)).json();
      if (ticket !== generation || id !== currentId) return;
      $('history-result').textContent = JSON.stringify(result, null, 2);
      $('history-summary').textContent = result.truncated ? 'Showing the first 100 events. Additional history exists.' : `History for ${id}`;
      $('history-timeline').replaceChildren();
      for (const event of result.events) {
        const item = document.createElement('li'), heading = document.createElement('strong'), detail = document.createElement('p'), transaction = document.createElement('p');
        const record = event.document;
        heading.textContent = event.deleted ? 'Record deleted' : record?.revoked ? 'Document revoked' : 'Document issued';
        detail.textContent = event.deleted ? 'Deletion recorded on the ledger.' : `${record?.updatedAt || record?.createdAt || 'Timestamp unavailable'}${record?.revoked ? ' · '+record.revocationReason : ''}`;
        transaction.textContent = 'Transaction: '+event.txId;
        item.append(heading,detail,transaction); $('history-timeline').append(item);
      }
      $('history-section').hidden = false;
    } catch (e) { if (ticket === generation && e.name !== 'AbortError') show('notice', e.message, 'error'); }
  });
  $('share').addEventListener('click', () => {
    const url = location.origin + '/#document=' + encodeURIComponent(currentId);
    $('share-link').value = url;
    const qr = qrcode(0,'M'); qr.addData(url); qr.make();
    const count = qr.getModuleCount(), cell = 6, quiet = 4, canvas = $('share-qr');
    canvas.width = canvas.height = (count + quiet * 2) * cell;
    const context = canvas.getContext('2d'); context.fillStyle='#fff'; context.fillRect(0,0,canvas.width,canvas.height); context.fillStyle='#000';
    for(let row=0;row<count;row++) for(let col=0;col<count;col++) if(qr.isDark(row,col)) context.fillRect((col+quiet)*cell,(row+quiet)*cell,cell,cell);
    $('share-section').hidden = false;
  });
  $('copy-link').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText($('share-link').value); show('notice','Verification link copied.'); }
    catch { $('share-link').select(); show('notice','Select and copy the verification link above.'); }
  });
  $('download-qr').addEventListener('click', () => {
    const id=currentId;
    $('share-qr').toBlob(blob => { const url=URL.createObjectURL(blob), anchor=document.createElement('a'); anchor.href=url; anchor.download=id+'-verification.png'; anchor.click(); setTimeout(()=>URL.revokeObjectURL(url),1000); },'image/png');
  });
  fetch('/actuator/health/readiness', { cache: 'no-store', credentials: 'omit' }).then(r => { $('health').textContent = r.ok ? 'Service ready' : 'Service unavailable'; }).catch(() => { $('health').textContent = 'Service unavailable'; });
})();
