"use strict";
const { Contract } = require("fabric-contract-api");
const fail = (code, message) => { throw new Error(`REGISTRY_${code}: ${message}`); };
const idPattern = /^[A-Z0-9-]{3,50}$/;
const hashPattern = /^sha256:[0-9a-f]{64}$/;
function text(value, max, required = false) {
  return typeof value === "string" && value.length <= max && (!required || value.trim().length > 0);
}
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map(k => [k, canonical(value[k])]));
  return value;
}
function getTxISOString(ctx) {
  const ts = ctx.stub.getTxTimestamp();
  return new Date(Number(BigInt(ts.seconds.toString()) * 1000n + BigInt(Math.floor(ts.nanos / 1e6)))).toISOString();
}
function identity(ctx) {
  const ci = ctx.clientIdentity;
  if (!ci || ci.getAttributeValue("registry.role") !== "issuer") fail("FORBIDDEN", "Issuer identity required");
  const department = ci.getAttributeValue("registry.department");
  if (!text(department, 128, true)) fail("FORBIDDEN", "Department attribute required");
  return { msp: ci.getMSPID(), department, id: ci.getID() };
}
function validateId(docId) {
  if (typeof docId !== "string" || !idPattern.test(docId)) fail("VALIDATION", "Invalid document ID");
}
function statusAt(record, now) {
  if (record.revoked === true) return 'REVOKED';
  return record.metadata?.expiresAt && record.metadata.expiresAt <= now ? 'EXPIRED' : 'ACTIVE';
}
class DocumentContract extends Contract {
  async initLedger() {}
  async ping() { return JSON.stringify({ status: "UP", schemaVersion: 2 }); }
  async registerDocument(ctx, docId, hash, issuer, subject, metadataJson) {
    issuer ??= ""; subject ??= ""; metadataJson ??= "{}";
    const caller = identity(ctx);
    validateId(docId);
    if (!hashPattern.test(hash || "")) fail("VALIDATION", "Invalid SHA-256 hash");
    if (!text(issuer, 128) || (issuer && issuer !== caller.department)) fail("FORBIDDEN", "Issuer does not match identity");
    if (!text(subject, 128)) fail("VALIDATION", "Subject exceeds limit");
    if (!text(metadataJson, 16384)) fail("VALIDATION", "Metadata exceeds limit");
    let metadata;
    try { metadata = JSON.parse(metadataJson); } catch { fail("VALIDATION", "Metadata must be JSON"); }
    if (!metadata || Array.isArray(metadata) || typeof metadata !== "object") fail("VALIDATION", "Metadata must be an object");
    if (Object.hasOwn(metadata, 'expiresAt') && (typeof metadata.expiresAt !== 'string'
        || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(metadata.expiresAt)
        || !Number.isFinite(Date.parse(metadata.expiresAt)) || new Date(metadata.expiresAt).toISOString() !== metadata.expiresAt))
      fail('VALIDATION', 'Expiry must be a valid UTC timestamp with milliseconds');
    if (await this.documentExists(ctx, docId)) fail("DUPLICATE", "Document already exists");
    const now = getTxISOString(ctx);
    const document = { docType: "document", schemaVersion: 2, docId, hash,
      issuer: caller.department, issuerMspId: caller.msp, registeredBy: caller.id,
      subject, metadata: canonical(metadata), revoked: false, revocationReason: "",
      createdAt: now, updatedAt: now, registrationTxId: ctx.stub.getTxID() };
    const serialized = JSON.stringify(document);
    await ctx.stub.putState(docId, Buffer.from(serialized));
    return serialized;
  }
  async getDocument(ctx, docId) {
    validateId(docId);
    const buffer = await ctx.stub.getState(docId);
    if (!buffer || buffer.length === 0) fail("NOT_FOUND", "Document not found");
    return buffer.toString("utf8");
  }
  async verifyDocument(ctx, docId, hash) {
    if (!hashPattern.test(hash || "")) fail("VALIDATION", "Invalid SHA-256 hash");
    const document = JSON.parse(await this.getDocument(ctx, docId));
    const matches = document.hash === hash;
    const revoked = document.revoked === true;
    const expired = !!document.metadata?.expiresAt && document.metadata.expiresAt <= getTxISOString(ctx);
    return JSON.stringify({ docId, matches, revoked, expired, revocationReason: document.revocationReason || "",
      valid: matches && !revoked && !expired, status: revoked ? "REVOKED" : expired ? "EXPIRED" : matches ? "VALID" : "TAMPERED_OR_MISMATCH" });
  }
  async revokeDocument(ctx, docId, reason) {
    const caller = identity(ctx);
    const document = JSON.parse(await this.getDocument(ctx, docId));
    if (document.issuerMspId !== caller.msp || document.issuer !== caller.department)
      fail("FORBIDDEN", "Only the issuing department can revoke; legacy records require reviewed migration");
    if (!text(reason, 500, true)) fail("VALIDATION", "A revocation reason is required");
    if (document.revoked) return JSON.stringify(document);
    document.revoked = true;
    document.revocationReason = reason;
    document.updatedAt = getTxISOString(ctx);
    document.revokedBy = caller.id;
    document.revocationTxId = ctx.stub.getTxID();
    const serialized = JSON.stringify(document);
    await ctx.stub.putState(docId, Buffer.from(serialized));
    return serialized;
  }
  async searchDocuments(ctx, prefix, status, owner, after) {
    const caller = identity(ctx);
    if (!/^[A-Z0-9-]{0,50}$/.test(prefix) || !['ALL', 'ACTIVE', 'REVOKED', 'EXPIRED'].includes(status)
        || (owner && !hashPattern.test(owner)) || !text(after, 512)) fail('VALIDATION', 'Invalid search');
    if (after && prefix && !after.startsWith(prefix)) fail('VALIDATION', 'Cursor does not match prefix');
    const iterator = await ctx.stub.getStateByRange(after ? after + '\u0000' : prefix, prefix ? prefix + '~' : '');
    const now = getTxISOString(ctx);
    const items = []; let scanned = 0, last = '', more = false;
    try {
      while (scanned < 250 && items.length < 20) {
        const next = await iterator.next();
        if (!next.value) break;
        scanned++; last = next.value.key;
        const record = JSON.parse(next.value.value.toString('utf8'));
        if (record.docType === 'document' && record.issuer === caller.department && record.issuerMspId === caller.msp
            && (!owner || record.metadata?.ownerSubjectHash === owner)
            && (status === 'ALL' || statusAt(record, now) === status)) items.push({...record, effectiveStatus: statusAt(record, now)});
        if (next.done) break;
      }
      if (scanned === 250 || items.length === 20) more = !!(await iterator.next()).value;
    } finally { await iterator.close(); }
    return JSON.stringify({ items, nextAfter: more ? last : '' });
  }
  async documentSummary(ctx, owner) {
    const caller = identity(ctx);
    if (owner && !hashPattern.test(owner)) fail('VALIDATION', 'Invalid owner');
    const asOf = getTxISOString(ctx), counts = {total: 0, active: 0, expired: 0, revoked: 0}, recent = [];
    const alerts = {within7Days: 0, within30Days: 0, items: []};
    const seven = new Date(Date.parse(asOf) + 7 * 86400000).toISOString();
    const thirty = new Date(Date.parse(asOf) + 30 * 86400000).toISOString();
    const iterator = await ctx.stub.getStateByRange('', '');
    let scanned = 0, complete = true;
    try {
      while (scanned < 5000) {
        const next = await iterator.next();
        if (!next.value) break;
        scanned++;
        const record = JSON.parse(next.value.value.toString('utf8'));
        if (record.docType === 'document' && record.issuer === caller.department && record.issuerMspId === caller.msp
            && (!owner || record.metadata?.ownerSubjectHash === owner)) {
          const effectiveStatus = statusAt(record, asOf);
          counts.total++; counts[effectiveStatus.toLowerCase()]++;
          const expiresAt = record.metadata?.expiresAt;
          if (effectiveStatus === 'ACTIVE' && expiresAt && expiresAt <= thirty) {
            alerts.within30Days++;
            if (expiresAt <= seven) alerts.within7Days++;
            alerts.items.push({...record, effectiveStatus});
            alerts.items.sort((a,b) => a.metadata.expiresAt.localeCompare(b.metadata.expiresAt) || a.docId.localeCompare(b.docId));
            alerts.items.splice(10);
          }
          recent.push({...record, effectiveStatus});
          recent.sort((a,b) => (b.updatedAt || '').localeCompare(a.updatedAt || '') || a.docId.localeCompare(b.docId));
          recent.splice(5);
        }
        if (next.done) break;
      }
      if (scanned === 5000) complete = !(await iterator.next()).value;
    } finally { await iterator.close(); }
    return JSON.stringify({counts, recent, alerts, complete, asOf});
  }
  async getDocumentHistory(ctx, docId) {
    await this.getDocument(ctx, docId);
    const iterator = await ctx.stub.getHistoryForKey(docId);
    const events = [];
    let truncated = false;
    try {
      while (true) {
        const item = await iterator.next();
        if (item.value) {
          if (events.length === 100) { truncated = true; break; }
          events.push({ txId: item.value.txId, deleted: item.value.isDelete,
            document: item.value.isDelete ? null : JSON.parse(item.value.value.toString("utf8")) });
        }
        if (item.done) break;
      }
    } finally { await iterator.close(); }
    return JSON.stringify({ docId, events, truncated });
  }
  async documentExists(ctx, docId) {
    validateId(docId);
    const buffer = await ctx.stub.getState(docId);
    return !!(buffer && buffer.length);
  }
}
module.exports.contracts = [DocumentContract];
