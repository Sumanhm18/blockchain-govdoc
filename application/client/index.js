import path from 'path';
import { fileURLToPath } from 'url';
import fs from 'fs';
import { Wallets, Gateway } from 'fabric-network';
import FabricCAServices from 'fabric-ca-client';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const gatewayProfilePath = path.resolve(__dirname, '../gateway/connection-org1.json');
const walletPath = path.resolve(__dirname, '../wallet');
const channelName = 'govchannel';
const chaincodeName = 'docregistry';

async function buildCAClient(ccp, caHostName) {
	const caInfo = ccp.certificateAuthorities[caHostName];
	let trustedRoots;
	if (caInfo.tlsCACerts && caInfo.tlsCACerts.pem) {
		trustedRoots = Array.isArray(caInfo.tlsCACerts.pem)
			? caInfo.tlsCACerts.pem.join('\n')
			: caInfo.tlsCACerts.pem;
	} else if (caInfo.tlsCACerts && caInfo.tlsCACerts.path) {
		trustedRoots = fs.readFileSync(caInfo.tlsCACerts.path);
	}
	return new FabricCAServices(caInfo.url, { trustedRoots, verify: false }, caInfo.caName);
}

async function enrollAdmin(caClient, wallet) {
	const identity = await wallet.get('admin');
	if (identity) return;
	const enrollment = await caClient.enroll({ enrollmentID: 'admin', enrollmentSecret: 'adminpw' });
	const x509Identity = {
		credentials: {
			certificate: enrollment.certificate,
			privateKey: enrollment.key.toBytes(),
		},
		mspId: 'Org1MSP',
		type: 'X.509',
	};
	await wallet.put('admin', x509Identity);
	console.log('Enrolled admin user "admin" and imported into the wallet');
}

async function registerAndEnrollUser(caClient, wallet, adminUserId, userId) {
	const userIdentity = await wallet.get(userId);
	if (userIdentity) return;
	const adminIdentity = await wallet.get(adminUserId);
	if (!adminIdentity) throw new Error('Admin identity not found in wallet');
	const provider = wallet.getProviderRegistry().getProvider(adminIdentity.type);
	const adminUser = await provider.getUserContext(adminIdentity, adminUserId);
	const secret = await caClient.register({ affiliation: 'org1.department1', enrollmentID: userId, role: 'client' }, adminUser);
	const enrollment = await caClient.enroll({ enrollmentID: userId, enrollmentSecret: secret });
	const x509Identity = {
		credentials: {
			certificate: enrollment.certificate,
			privateKey: enrollment.key.toBytes(),
		},
		mspId: 'Org1MSP',
		type: 'X.509',
	};
	await wallet.put(userId, x509Identity);
	console.log(`Registered and enrolled user ${userId} and imported into the wallet`);
}

async function main() {
	if (!fs.existsSync(gatewayProfilePath)) {
		throw new Error('Connection profile not found. Run ./scripts/network.sh up first.');
	}
	const ccp = JSON.parse(fs.readFileSync(gatewayProfilePath, 'utf8'));
	const wallet = await Wallets.newFileSystemWallet(walletPath);
	const caClient = await buildCAClient(ccp, 'ca.org1.example.com');

	await enrollAdmin(caClient, wallet);
	await registerAndEnrollUser(caClient, wallet, 'admin', 'appUser');

	const gateway = new Gateway();
	try {
		await gateway.connect(ccp, { wallet, identity: 'appUser', discovery: { enabled: true, asLocalhost: true } });
		const network = await gateway.getNetwork(channelName);
		const contract = network.getContract(chaincodeName);

		const docId = 'DOC-' + Math.floor(Math.random() * 100000);
		const hash = 'sha256:examplehash123';
		const issuer = 'Ministry of Records';
		const subject = 'Citizen ABC';
		const metadata = JSON.stringify({ type: 'BirthCertificate', issuedOn: new Date().toISOString() });

		console.log('Registering document...');
		let result = await contract.submitTransaction('registerDocument', docId, hash, issuer, subject, metadata);
		console.log('registerDocument result:', result.toString());

		console.log('Fetching document...');
		result = await contract.evaluateTransaction('getDocument', docId);
		console.log('getDocument result:', result.toString());

		console.log('Verifying document (should match)...');
		result = await contract.evaluateTransaction('verifyDocument', docId, hash);
		console.log('verifyDocument result:', result.toString());

		console.log('Revoking document...');
		result = await contract.submitTransaction('revokeDocument', docId, 'expired or superseded');
		console.log('revokeDocument result:', result.toString());
	} finally {
		gateway.disconnect();
	}
}

main().catch((e) => {
	console.error(e);
	process.exit(1);
});
