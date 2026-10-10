import { readFile } from 'node:fs/promises';
const base = process.env.REGISTRY_API_URL || 'http://127.0.0.1:18081';
const secrets = JSON.parse(await readFile(new URL('../../.runtime/secrets/credentials.json', import.meta.url), 'utf8'));
const id = process.argv[2];
if (!id || !/^[A-Z0-9-]{3,50}$/.test(id)) throw new Error('Usage: npm start -- DOCUMENT-ID');
const response = await fetch(`${base}/api/documents/${id}`, { headers: { Authorization: `Basic ${Buffer.from(`reader:${secrets.reader}`).toString('base64')}` } });
console.log(`HTTP ${response.status}`);
if (response.ok) console.log('Document record is accessible to the reader account.');
else process.exitCode = 1;
