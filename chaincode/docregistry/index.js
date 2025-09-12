"use strict";

const { Contract } = require("fabric-contract-api");

function getTxISOString(ctx) {
	const ts = ctx.stub.getTxTimestamp();
	// ts is a protobuf Timestamp with seconds and nanos
	const millis = ts.seconds.low * 1000 + Math.floor(ts.nanos / 1e6);
	return new Date(millis).toISOString();
}

class DocumentContract extends Contract {
	async initLedger(ctx) {
		return;
	}

	async registerDocument(ctx, docId, hash, issuer, subject, metadataJson) {
		if (!docId || !hash) {
			throw new Error("docId and hash are required");
		}
		const exists = await this.documentExists(ctx, docId);
		if (exists) {
			throw new Error(`Document ${docId} already exists`);
		}
		let metadata = {};
		if (metadataJson) {
			try {
				metadata = JSON.parse(metadataJson);
			} catch (e) {
				throw new Error("metadataJson must be valid JSON");
			}
		}
		const nowIso = getTxISOString(ctx);
		const document = {
			docType: "document",
			docId,
			hash,
			issuer: issuer || "",
			subject: subject || "",
			metadata,
			revoked: false,
			revocationReason: "",
			createdAt: nowIso,
			updatedAt: nowIso
		};
		await ctx.stub.putState(docId, Buffer.from(JSON.stringify(document)));
		return JSON.stringify(document);
	}

	async getDocument(ctx, docId) {
		const buffer = await ctx.stub.getState(docId);
		if (!buffer || buffer.length === 0) {
			throw new Error(`Document ${docId} does not exist`);
		}
		return buffer.toString();
	}

	async verifyDocument(ctx, docId, hash) {
		const documentStr = await this.getDocument(ctx, docId);
		const document = JSON.parse(documentStr);
		const matches = document.hash === hash;
		const result = {
			docId,
			matches,
			revoked: document.revoked === true,
			revocationReason: document.revocationReason || ""
		};
		return JSON.stringify(result);
	}

	async revokeDocument(ctx, docId, reason) {
		const documentStr = await this.getDocument(ctx, docId);
		const document = JSON.parse(documentStr);
		if (document.revoked) {
			return JSON.stringify(document);
		}
		document.revoked = true;
		document.revocationReason = reason || "unspecified";
		document.updatedAt = getTxISOString(ctx);
		await ctx.stub.putState(docId, Buffer.from(JSON.stringify(document)));
		return JSON.stringify(document);
	}

	async documentExists(ctx, docId) {
		const buffer = await ctx.stub.getState(docId);
		return buffer && buffer.length > 0;
	}
}

module.exports.contracts = [DocumentContract];
