package com.gov.docregistry.fabric;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.Network;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Identity;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;

import java.nio.file.Files;
import java.io.StringReader;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

@Service
public class FabricGatewayService {
	private static final String CHANNEL = "govchannel";
	private static final String CHAINCODE = "docregistry";

	private Gateway gateway;
	private Contract contract;
	private ManagedChannel channel;

	private static String readAll(Path p) throws Exception { return Files.readString(p); }

	public synchronized void connect() throws Exception {
		if (gateway != null) return;

		// Load identity
		Path walletUser = Paths.get("/Users/sumanhm/Downloads/fabric/application/wallet/appUser");
		Path certPath;
		Path keyPath;
		if (Files.exists(walletUser)) {
			certPath = Files.list(walletUser.resolve("signcerts")).findFirst().orElseThrow();
			keyPath = Files.list(walletUser.resolve("keystore")).findFirst().orElseThrow();
		} else {
			Path mspBase = Paths.get("/Users/sumanhm/Downloads/fabric/fabric-samples/test-network/organizations/peerOrganizations/org1.example.com/users/User1@org1.example.com/msp");
			certPath = Files.list(mspBase.resolve("signcerts")).findFirst().orElseThrow();
			keyPath = Files.list(mspBase.resolve("keystore")).findFirst().orElseThrow();
		}
		X509Certificate cert = Identities.readX509Certificate(new StringReader(readAll(certPath)));
		Identity identity = new org.hyperledger.fabric.client.identity.X509Identity("Org1MSP", cert);
		PrivateKey pk = Identities.readPrivateKey(new StringReader(readAll(keyPath)));
		Signer signer = Signers.newPrivateKeySigner(pk);

		// Build gRPC channel with proper TLS configuration
		Path tlsCertPem = Paths.get("/Users/sumanhm/Downloads/fabric/fabric-samples/test-network/organizations/peerOrganizations/org1.example.com/tlsca/tlsca.org1.example.com-cert.pem");
		this.channel = NettyChannelBuilder.forAddress("localhost", 7051)
				.sslContext(GrpcSslContexts.forClient().trustManager(tlsCertPem.toFile()).build())
				.overrideAuthority("peer0.org1.example.com")
				.build();

		gateway = Gateway.newInstance()
				.identity(identity)
				.signer(signer)
				.connection(channel)
				.connect();

		Network network = gateway.getNetwork(CHANNEL);
		contract = network.getContract(CHAINCODE);
	}

	public synchronized void disconnect() {
		if (gateway != null) { gateway.close(); gateway = null; contract = null; }
		if (channel != null) { channel.shutdownNow(); channel = null; }
	}

	public String registerDocument(String docId, String hash, String issuer, String subject, String metadataJson) throws Exception {
		connect();
		byte[] result = contract.submitTransaction("registerDocument", docId, hash, issuer, subject, metadataJson);
		return new String(result);
	}
	public String getDocument(String docId) throws Exception { connect(); return new String(contract.evaluateTransaction("getDocument", docId)); }
	public String verifyDocument(String docId, String hash) throws Exception { connect(); return new String(contract.evaluateTransaction("verifyDocument", docId, hash)); }
	public String revokeDocument(String docId, String reason) throws Exception { connect(); return new String(contract.submitTransaction("revokeDocument", docId, reason)); }
}
