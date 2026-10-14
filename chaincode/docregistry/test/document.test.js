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

test('search filters department, citizen owner, status, and bounds pages', async () => {
  const c=new Contract(), ctx=context(); let closed=0;
  ctx.stub.getStateByRange=async(start,end)=>{
    const entries=[...ctx.state.entries()].filter(([key])=>key>=start&&(!end||key<end)).sort(([a],[b])=>a.localeCompare(b));let index=0;
    return {next:async()=>index<entries.length?{done:false,value:{key:entries[index][0],value:entries[index++][1]}}:{done:true},close:async()=>closed++};
  };
  for(let i=0;i<25;i++) await issue(c,ctx,'SEARCH-'+String(i).padStart(3,'0'),JSON.stringify({ownerSubjectHash:HASH}));
  await issue(c,ctx,'SEARCH-OTHER',JSON.stringify({ownerSubjectHash:'sha256:'+'b'.repeat(64)}));
  const foreign=JSON.parse(ctx.state.get('SEARCH-OTHER'));foreign.issuerMspId='OtherMSP';ctx.state.set('SEARCH-OTHER',Buffer.from(JSON.stringify(foreign)));
  await c.revokeDocument(ctx,'SEARCH-000','reason');
  const first=JSON.parse(await c.searchDocuments(ctx,'SEARCH-','ACTIVE',HASH,''));
  assert.equal(first.items.length,20);assert.equal(first.items[0].docId,'SEARCH-001');
  const second=JSON.parse(await c.searchDocuments(ctx,'SEARCH-','ACTIVE',HASH,first.nextAfter));
  assert.equal(second.items.length,4);assert.equal(second.nextAfter,'');
  assert.equal(JSON.parse(await c.searchDocuments(ctx,'SEARCH-','REVOKED',HASH,'')).items.length,1);
  assert.equal(JSON.parse(await c.searchDocuments(ctx,'SEARCH-','ALL','sha256:'+'c'.repeat(64),'')).items.length,0);
  await assert.rejects(()=>c.searchDocuments(context('reader'),'','ALL','',''),/FORBIDDEN/);
  assert.equal(closed,4);assert.equal(Contract.prototype.searchDocuments.length,5);
});

test('search stops scanning at 250 records even when none are visible', async () => {
  const c=new Contract(),ctx=context();let reads=0,closed=false;
  ctx.stub.getStateByRange=async()=>({next:async()=>({done:false,value:{key:'HIDDEN-'+String(++reads),value:Buffer.from(JSON.stringify({docType:'document',issuer:'Other',issuerMspId:'OtherMSP'}))}}),close:async()=>{closed=true;}});
  const result=JSON.parse(await c.searchDocuments(ctx,'','ALL','',''));
  assert.equal(result.items.length,0);assert.equal(result.nextAfter,'HIDDEN-250');assert.equal(reads,251);assert.equal(closed,true);
});

function range(ctx) {
  ctx.stub.getStateByRange=async(start,end)=>{
    const entries=[...ctx.state.entries()].filter(([key])=>key>=start&&(!end||key<end)).sort(([a],[b])=>a.localeCompare(b));let i=0;
    return {next:async()=>i<entries.length?{done:false,value:{key:entries[i][0],value:entries[i++][1]}}:{done:true},close:async()=>{}};
  };
}
test('expiry boundary is deterministic and revocation takes precedence', async()=>{
  const c=new Contract(),ctx=context();
  await issue(c,ctx,'EXP-001',JSON.stringify({expiresAt:'2023-11-14T22:13:21.000Z'}));
  assert.equal(JSON.parse(await c.verifyDocument(ctx,'EXP-001',HASH)).status,'VALID');
  ctx.tick();
  for(const hash of [HASH,'sha256:'+'b'.repeat(64)]) {
    const result=JSON.parse(await c.verifyDocument(ctx,'EXP-001',hash));
    assert.equal(result.status,'EXPIRED');assert.equal(result.valid,false);assert.equal(result.expired,true);
  }
  assert.equal(ctx.writes(),1);
  await c.revokeDocument(ctx,'EXP-001','revoked after expiry');
  assert.equal(JSON.parse(await c.verifyDocument(ctx,'EXP-001',HASH)).status,'REVOKED');
});
test('invalid expiry is rejected without writes',async()=>{
  const c=new Contract(),ctx=context();
  for(const expiresAt of ['',null,42,'2026-02-30T00:00:00.000Z','2026-01-01','2026-01-01T00:00:00+05:30'])
    await assert.rejects(()=>issue(c,ctx,'EXP-001',JSON.stringify({expiresAt})),/VALIDATION/);
  assert.equal(ctx.writes(),0);
});
test('summary and expiry search respect ownership and department',async()=>{
  const c=new Contract(),ctx=context();range(ctx);
  await issue(c,ctx,'SUM-A',JSON.stringify({ownerSubjectHash:HASH}));ctx.tick();
  await issue(c,ctx,'SUM-E',JSON.stringify({ownerSubjectHash:HASH,expiresAt:'2020-01-01T00:00:00.000Z'}));ctx.tick();
  await issue(c,ctx,'SUM-R',JSON.stringify({ownerSubjectHash:HASH}));await c.revokeDocument(ctx,'SUM-R','reason');
  await issue(c,ctx,'SUM-OTHER','{}');
  const foreign=JSON.parse(ctx.state.get('SUM-OTHER'));foreign.issuer='Other';ctx.state.set('SUM-OTHER',Buffer.from(JSON.stringify(foreign)));
  const result=JSON.parse(await c.documentSummary(ctx,HASH));
  assert.deepEqual(result.counts,{total:3,active:1,expired:1,revoked:1});assert.equal(result.complete,true);
  assert.equal(result.recent[0].docId,'SUM-R');assert.equal(result.recent.some(x=>x.docId==='SUM-OTHER'),false);
  const expired=JSON.parse(await c.searchDocuments(ctx,'SUM-','EXPIRED',HASH,''));assert.equal(expired.items.length,1);assert.equal(expired.items[0].effectiveStatus,'EXPIRED');
  assert.equal(JSON.parse(await c.searchDocuments(ctx,'SUM-','ACTIVE',HASH,'')).items.length,1);
  await assert.rejects(()=>c.documentSummary(context('reader'),''),/FORBIDDEN/);
  assert.equal(Contract.prototype.documentSummary.length,2);
});
test('summary labels capped scans incomplete and closes iterator',async()=>{
  const c=new Contract(),ctx=context();let reads=0,closed=false;
  ctx.stub.getStateByRange=async()=>({next:async()=>({value:{key:String(++reads),value:Buffer.from('{"docType":"document","issuer":"Other"}')}}),close:async()=>{closed=true;}});
  const result=JSON.parse(await c.documentSummary(ctx,''));assert.equal(result.complete,false);assert.equal(result.counts.total,0);assert.equal(reads,5001);assert.equal(closed,true);
});

test('expiry alerts include exact 7/30 day boundaries and exclude hidden, expired or revoked records',async()=>{
  const c=new Contract(),ctx=context();range(ctx);
  const now=1700000000000, expiry=days=>new Date(now+days*86400000).toISOString();
  for(const [id,days] of [['ALERT-7',7],['ALERT-30',30],['ALERT-LATER',31],['ALERT-PAST',0],['ALERT-REVOKED',2]])
    await issue(c,ctx,id,JSON.stringify({expiresAt:expiry(days),ownerSubjectHash:HASH}));
  await c.revokeDocument(ctx,'ALERT-REVOKED','synthetic');
  await issue(c,ctx,'ALERT-OTHER',JSON.stringify({expiresAt:expiry(1),ownerSubjectHash:'sha256:'+'b'.repeat(64)}));
  await issue(c,ctx,'ALERT-FOREIGN',JSON.stringify({expiresAt:expiry(1),ownerSubjectHash:HASH}));
  const foreign=JSON.parse(ctx.state.get('ALERT-FOREIGN'));foreign.issuerMspId='OtherMSP';ctx.state.set('ALERT-FOREIGN',Buffer.from(JSON.stringify(foreign)));
  const {alerts}=JSON.parse(await c.documentSummary(ctx,HASH));
  assert.equal(alerts.within7Days,1);assert.equal(alerts.within30Days,2);assert.deepEqual(alerts.items.map(x=>x.docId),['ALERT-7','ALERT-30']);
});
test('expiry alert list is capped at ten earliest records while counts remain exact for completed scans',async()=>{
  const c=new Contract(),ctx=context();range(ctx);
  for(let day=15;day>0;day--)await issue(c,ctx,'ALERT-'+String(day).padStart(2,'0'),JSON.stringify({expiresAt:new Date(1700000000000+day*86400000).toISOString()}));
  const {alerts,complete}=JSON.parse(await c.documentSummary(ctx,''));assert.equal(complete,true);assert.equal(alerts.within7Days,7);assert.equal(alerts.within30Days,15);assert.equal(alerts.items.length,10);assert.equal(alerts.items[0].docId,'ALERT-01');assert.equal(alerts.items[9].docId,'ALERT-10');
});
