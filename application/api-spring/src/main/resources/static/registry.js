'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let authorization = '', roles = [], currentId = '', generation = 0, recordVersion = 0;
  const active = new Set();
  const max = 10 * 1024 * 1024;
  function show(id, text, tone = '') {
    const el = $(id); el.textContent = text; el.className = `result ${tone}`; el.hidden = false;
  }
  async function request(path, options = {}) {
    const ticket = generation;
    const controller = new AbortController(); active.add(controller);
    try {
      const response = await fetch(path, { ...options, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal,
        headers: { ...options.headers, ...(authorization ? { Authorization: authorization } : {}) } });
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(`${response.status}: ${error.message || 'Request could not be completed.'}`);
      }
      const body = await response.arrayBuffer();
      if (ticket !== generation) throw new DOMException('Signed out', 'AbortError');
      return new Response(body, { status: response.status, headers: response.headers });
    } finally { active.delete(controller); }
  }
  function fileCheck(file) {
    if (!file || !file.size) throw new Error('Choose a nonempty file.');
    if (file.size > max) throw new Error('File exceeds the 10 MiB limit.');
  }
  function bind(id, action) {
    $(id).addEventListener('submit', async event => {
      event.preventDefault(); const ticket = generation;
      $('notice').hidden = true;
      const buttons = [...$(id).querySelectorAll('button')]; buttons.forEach(b => b.disabled = true);
      try { await action(); } catch (e) {
        if (ticket === generation && e.name !== 'AbortError') show('notice', e.message, 'error');
      } finally { buttons.forEach(b => b.disabled = false); }
    });
  }
  function clearRecord() {
    recordVersion++; currentId = ''; $('record').hidden = true; $('record-details').replaceChildren();
    $('verification').hidden = true; $('history-section').hidden = true; $('history-result').textContent = '';
    $('verify').reset(); $('revoke').reset();
  }
  function signout() {
    generation++; active.forEach(c => c.abort()); authorization = ''; roles = []; clearRecord();
    document.querySelectorAll('form').forEach(f => f.reset());
    $('workspace').hidden = true; $('connection').hidden = false; $('notice').hidden = true;
    $('pending').hidden = true; $('upload-result').hidden = true; $('upload-result').textContent = '';
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
  async function registration(id) {
    const state = await (await request(`/api/documents/${encodeURIComponent(id)}/registration`)).json();
    const confirmed = state.state === 'CONFIRMED';
    show('upload-result', confirmed ? `CONFIRMED — ${id} is registered on the ledger. Use Find & verify to retrieve it.` : `${state.state} — ${id}. ${state.state === 'REJECTED' ? 'Registration was rejected. Review the record before retrying.' : 'Ledger confirmation is pending. Check again before retrying this same ID.'}`, confirmed ? '' : 'warning');
    $('pending').hidden = confirmed || state.state === 'REJECTED';
  }
  bind('upload', async () => {
    $('upload-result').hidden = true; $('pending').hidden = true;
    const data = new FormData($('upload')); fileCheck(data.get('file'));
    if ($('owner-field').hidden) data.delete('ownerSubject');
    const id = data.get('docId'); const response = await request('/api/documents/upload', { method: 'POST', body: data });
    $('pending-id').value = id; $('lookup-id').value = id;
    if (response.status === 202) { show('upload-result', `PENDING — ${id}. The upload is saved for recovery. Check registration status before retrying this same ID.`, 'warning'); $('pending').hidden = false; }
    else { show('upload-result', `CONFIRMED — ${id} is registered on the ledger. Use Find & verify to retrieve it.`); $('upload').reset(); }
  });
  bind('pending', () => registration($('pending-id').value));
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
