'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const Contract = require('../index').contracts[0];
const HASH = 'sha256:' + 'a'.repeat(64);
function context(role = 'issuer', department = 'Org1', msp = 'Org1MSP') {
  const state = new Map(); let seconds = 1700000000; let writes = 0;
  return { state, tick: () => seconds++, writes: () => writes,
    clientIdentity: { getAttributeValue: key => key === 'registry.role' ? role : department, getMSPID: () => msp, getID: () => 'test-service-identity' },
    stub: { getState: async id => state.get(id) || Buffer.alloc(0), putState: async (id, bytes) => { writes++; state.set(id, bytes); },
      getTxTimestamp: () => ({ seconds: { toString: () => String(seconds) }, nanos: 0 }), getTxID: () => 'test-tx-id' } };
}
async function issue(c, ctx, id = 'DOC-001', metadata = '{}') { return JSON.parse(await c.registerDocument(ctx, id, HASH, 'Org1', 'opaque-reference', metadata)); }
test('registration returns stored record and binds issuer to certificate', async () => {
  const c = new Contract(), ctx = context(); const d = await issue(c, ctx);
  assert.equal(d.issuerMspId, 'Org1MSP'); assert.equal(d.revoked, false); assert.equal(d.registrationTxId, 'test-tx-id');
  assert.deepEqual(JSON.parse(await c.getDocument(ctx, 'DOC-001')), d);
});
test('duplicate ID cannot overwrite a document', async () => {
  const c = new Contract(), ctx = context(); await issue(c, ctx);
  await assert.rejects(() => issue(c, ctx), /REGISTRY_DUPLICATE:/); assert.equal(ctx.writes(), 1);
});
test('direct unauthorized registration and revocation are refused', async () => {
  const c = new Contract(), ctx = context('reader');
  await assert.rejects(() => issue(c, ctx), /REGISTRY_FORBIDDEN:/);
  await assert.rejects(() => c.revokeDocument(ctx, 'DOC-001', 'reason'), /REGISTRY_FORBIDDEN:/);
});
test('issuer cannot impersonate another department', async () => {
  const c = new Contract(); await assert.rejects(() => c.registerDocument(context(), 'DOC-001', HASH, 'Other', '', '{}'), /REGISTRY_FORBIDDEN:/);
});
test('another organization or department cannot revoke', async () => {
  const c = new Contract(), ctx = context(); await issue(c, ctx);
  for (const other of [context('issuer', 'Org1', 'Org2MSP'), context('issuer', 'Other')]) {
    other.stub.getState = ctx.stub.getState;
    await assert.rejects(() => c.revokeDocument(other, 'DOC-001', 'reason'), /REGISTRY_FORBIDDEN:/);
  }
});
test('active matching, mismatch, and revoked verification outcomes', async () => {
  const c = new Contract(), ctx = context(); await issue(c, ctx);
  assert.equal(JSON.parse(await c.verifyDocument(ctx, 'DOC-001', HASH)).status, 'VALID');
  assert.equal(JSON.parse(await c.verifyDocument(ctx, 'DOC-001', 'sha256:' + 'b'.repeat(64))).status, 'TAMPERED_OR_MISMATCH');
  await c.revokeDocument(ctx, 'DOC-001', 'expired');
  for (const hash of [HASH, 'sha256:' + 'b'.repeat(64)]) {
    const result = JSON.parse(await c.verifyDocument(ctx, 'DOC-001', hash)); assert.equal(result.valid, false); assert.equal(result.status, 'REVOKED');
  }
});
test('revocation preserves original registration; repeat preserves reason and timestamp', async () => {
  const c = new Contract(), ctx = context(); const old = await issue(c, ctx); ctx.tick();
  const revoked = JSON.parse(await c.revokeDocument(ctx, 'DOC-001', 'expired')); ctx.tick();
  assert.equal(revoked.createdAt, old.createdAt); assert.notEqual(revoked.updatedAt, old.updatedAt);
  assert.deepEqual(JSON.parse(await c.revokeDocument(ctx, 'DOC-001', 'new reason')), revoked); assert.equal(ctx.writes(), 2);
});
test('nonexistent get, verify and revoke have stable error codes', async () => {
  const c = new Contract(), ctx = context();
  for (const fn of [() => c.getDocument(ctx, 'MISSING'), () => c.verifyDocument(ctx, 'MISSING', HASH), () => c.revokeDocument(ctx, 'MISSING', 'reason')])
    await assert.rejects(fn, /REGISTRY_NOT_FOUND:/);
});
test('invalid identifiers, hashes, subject, and metadata are refused before writes', async () => {
  const c = new Contract(), ctx = context();
  for (const id of ['', '   ', 'ab', 'x'.repeat(51)]) await assert.rejects(() => issue(c, ctx, id), /REGISTRY_VALIDATION:/);
  for (const hash of ['', 'abc', 'sha256:' + 'A'.repeat(64)]) await assert.rejects(() => c.registerDocument(ctx, 'DOC-001', hash, '', '', '{}'), /REGISTRY_VALIDATION:/);
  for (const meta of ['null', '[]', '5', '{', 'x'.repeat(16385)]) await assert.rejects(() => issue(c, ctx, 'DOC-001', meta), /REGISTRY_VALIDATION:/);
  await assert.rejects(() => c.registerDocument(ctx, 'DOC-001', HASH, '', 'x'.repeat(129), '{}'), /REGISTRY_VALIDATION:/);
  assert.equal(ctx.writes(), 0);
});
test('empty and overlong revocation reasons rejected', async () => {
  const c = new Contract(), ctx = context(); await issue(c, ctx);
  for (const reason of ['', '  ', 'x'.repeat(501)]) await assert.rejects(() => c.revokeDocument(ctx, 'DOC-001', reason), /REGISTRY_VALIDATION:/);
});
test('metadata serialization deterministic across key order', async () => {
  const c = new Contract(); const a = await issue(c, context(), 'DOC-001', '{"b":{"z":1,"a":2},"a":3}');
  const b = await issue(c, context(), 'DOC-001', '{"a":3,"b":{"a":2,"z":1}}'); assert.deepEqual(a, b);
});
test('full timestamp seconds support years beyond 2038', async () => {
  const c = new Contract(), ctx = context(); ctx.stub.getTxTimestamp = () => ({ seconds: { low: -2147483648, toString: () => '2147483648' }, nanos: 0 });
  assert.match((await issue(c, ctx)).createdAt, /^2038-/);
});
test('legacy records remain readable but cannot be claimed by a new issuer', async () => {
  const c = new Contract(), ctx = context(); ctx.state.set('DOC-OLD', Buffer.from(JSON.stringify({ docId: 'DOC-OLD', issuer: 'Org1', hash: HASH })));
  assert.equal(JSON.parse(await c.getDocument(ctx, 'DOC-OLD')).docId, 'DOC-OLD');
  await assert.rejects(() => c.revokeDocument(ctx, 'DOC-OLD', 'reason'), /REGISTRY_FORBIDDEN:/);
});
test('history closes its iterator and returns transaction references', async () => {
  const c = new Contract(), ctx = context(); await issue(c, ctx); let closed = false;
  ctx.stub.getHistoryForKey = async () => ({ next: async () => ({ done: true, value: { txId: 'tx1', isDelete: false, value: ctx.state.get('DOC-001') } }), close: async () => { closed = true; } });
  const history = JSON.parse(await c.getDocumentHistory(ctx, 'DOC-001')); assert.equal(history.events[0].txId, 'tx1'); assert.equal(closed, true);
});

// Fabric derives transaction metadata from Function.length; default parameters truncate it.
test("registration exposes all five arguments to the Fabric dispatcher", () => {
  const Contract = require("../index").contracts[0];
  assert.equal(Contract.prototype.registerDocument.length, 6);
});
