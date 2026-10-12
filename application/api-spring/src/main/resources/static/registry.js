'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let authorization = '', roles = [], currentId = '', generation = 0, recordVersion = 0;
  const active = new Set();
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
      } finally { $(id).setAttribute('aria-busy', 'false'); buttons.forEach(b => b.disabled = false); }
    });
  }
  function clearRecord() {
    recordVersion++; currentId = ''; $('record').hidden = true; $('record-details').replaceChildren();
    $('verification').hidden = true; $('history-section').hidden = true; $('history-result').textContent = '';
    $('verify').reset(); $('revoke').reset();
  }
  function signout() {
    stopTracking(); confirmedId = ''; generation++; active.forEach(c => c.abort()); authorization = ''; roles = []; clearRecord();
    document.querySelectorAll('form').forEach(f => f.reset());
    $('workspace').hidden = true; $('connection').hidden = false; $('notice').hidden = true;
    $('open-registered').hidden = true; $('upload-result').hidden = true; $('upload-result').textContent = '';
    $('roles').textContent = ''; modeChanged();
  }
  function modeChanged() {
    const basic = $('auth-mode').value === 'basic';
    $('basic-fields').hidden = !basic; $('token-field').hidden = basic;
    $('username').required = basic; $('password').required = basic; $('token').required = !basic;
    $('password').value = ''; $('token').value = '';
  }
  $('auth-mode').addEventListener('change', modeChanged); $('logout').addEventListener('click', signout);
  window.addEventListener('pagehide', signout);
  bind('login', async () => {
    const mode = $('auth-mode').value;
    if (mode === 'basic') {
      const bytes = new TextEncoder().encode(`${$('username').value}:${$('password').value}`);
      authorization = 'Basic ' + btoa(String.fromCharCode(...bytes));
    } else authorization = 'Bearer ' + $('token').value.trim();
    $('password').value = ''; $('token').value = '';
    try {
      const session = await (await request('/api/session')).json(); roles = session.roles;
      $('roles').textContent = `Access: ${roles.join(', ')}`;
      $('connection').hidden = true; $('workspace').hidden = false;
      $('issue-tab').hidden = !roles.includes('ISSUER'); $('owner-field').hidden = mode !== 'bearer';
      selectPanel(roles.some(r => ['READER', 'CITIZEN'].includes(r)) ? 'lookup' : roles.includes('ISSUER') ? 'issue' : 'lookup');
    } catch (error) { authorization = ''; throw error; }
  });
  function selectPanel(name) {
    document.querySelectorAll('.task').forEach(p => p.hidden = p.id !== name);
    document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.panel === name));
    $('notice').hidden = true;
  }
  document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => selectPanel(b.dataset.panel)));
  async function loadRecord(id) {
    clearRecord(); const version = recordVersion; const record = await (await request(`/api/documents/${encodeURIComponent(id)}`)).json();
    if (version !== recordVersion) return;
    currentId = id; $('record-id').textContent = id; $('record-state').textContent = record.revoked ? 'REVOKED' : 'ACTIVE';
    $('record-state').className = 'badge' + (record.revoked ? ' error' : '');
    for (const [label, value] of [['Issuing department', record.issuer], ['Issuer MSP', record.issuerMspId], ['Document digest', record.hash], ['Subject reference', record.subject], ['Revocation reason', record.revocationReason]]) {
      if (value === undefined || value === '') continue;
      const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = label; dd.textContent = String(value); $('record-details').append(dt, dd);
    }
    $('record').hidden = false; $('download').disabled = !!record.revoked;
    $('history').hidden = !roles.includes('READER'); $('revoke-section').hidden = !!record.revoked || !roles.includes('REVOKER');
  }
  bind('find', () => loadRecord($('lookup-id').value));
  bind('verify', async () => {
    const id = currentId, version = recordVersion;
    $('verification').hidden = true; const file = $('verify-file').files[0]; fileCheck(file);
    const data = new FormData(); data.append('file', file);
    const result = await (await request(`/api/documents/${encodeURIComponent(id)}/verify-file`, { method: 'POST', body: data })).json();
    if (version !== recordVersion) return;
    const descriptions = { VALID: 'VALID — File bytes match the registered digest and the document is not revoked.', TAMPERED_OR_MISMATCH: 'TAMPERED_OR_MISMATCH — File bytes differ from the registered digest.', REVOKED: 'REVOKED — The issuer has revoked this document.' };
    if (!(result.status in descriptions)) throw new Error('Unexpected verification response. Validity could not be established.');
    show('verification', descriptions[result.status], result.status === 'VALID' ? '' : 'error');
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
      $('history-result').textContent = JSON.stringify(result, null, 2); $('history-section').hidden = false;
    } catch (e) { if (ticket === generation && e.name !== 'AbortError') show('notice', e.message, 'error'); }
  });
  fetch('/actuator/health/readiness', { cache: 'no-store', credentials: 'omit' }).then(r => { $('health').textContent = r.ok ? 'Service ready' : 'Service unavailable'; }).catch(() => { $('health').textContent = 'Service unavailable'; });
})();
