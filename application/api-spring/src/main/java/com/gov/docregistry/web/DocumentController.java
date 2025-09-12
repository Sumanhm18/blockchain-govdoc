package com.gov.docregistry.web;

import com.gov.docregistry.fabric.FabricGatewayService;
import com.gov.docregistry.ipfs.IpfsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
	private final FabricGatewayService fabric;
	private final IpfsService ipfs;

	public DocumentController(FabricGatewayService fabric, IpfsService ipfs) {
		this.fabric = fabric;
		this.ipfs = ipfs;
	}

	@PostMapping
	public ResponseEntity<String> register(@RequestBody Map<String, Object> body) throws Exception {
		String docId = (String) body.get("docId");
		String hash = (String) body.get("hash");
		String issuer = (String) body.getOrDefault("issuer", "");
		String subject = (String) body.getOrDefault("subject", "");
		String metadataJson = (String) body.getOrDefault("metadataJson", "{}");

		if (docId == null || docId.isBlank() || hash == null || hash.isBlank()) {
			return ResponseEntity.badRequest().body("{\"error\":\"docId and hash are required\"}");
		}
		return ResponseEntity.ok(fabric.registerDocument(docId, hash, issuer, subject, metadataJson));
	}

	@PostMapping("/upload")
	public ResponseEntity<String> upload(@RequestPart("file") MultipartFile file,
	                                    @RequestParam("docId") String docId,
	                                    @RequestParam(value = "issuer", required = false, defaultValue = "") String issuer,
	                                    @RequestParam(value = "subject", required = false, defaultValue = "") String subject,
	                                    @RequestParam(value = "type", required = false, defaultValue = "") String type) throws Exception {
		if (docId == null || docId.isBlank()) {
			return ResponseEntity.badRequest().body("{\"error\":\"docId is required\"}");
		}
		if (file == null || file.isEmpty()) {
			return ResponseEntity.badRequest().body("{\"error\":\"file is required\"}");
		}
		byte[] bytes = file.getBytes();
		String cid = ipfs.addBytes(bytes, file.getOriginalFilename());
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		String sha256Hex = HexFormat.of().formatHex(md.digest(bytes));
		String metadataJson = String.format("{\"type\":\"%s\",\"ipfsCid\":\"%s\",\"fileName\":\"%s\"}",
				type, cid, file.getOriginalFilename());
		String onchain = fabric.registerDocument(docId, "sha256:" + sha256Hex, issuer, subject, metadataJson);
		return ResponseEntity.ok(onchain);
	}

	@GetMapping("/{docId}")
	public ResponseEntity<String> get(@PathVariable String docId) throws Exception {
		if (docId == null || docId.isBlank()) {
			return ResponseEntity.badRequest().body("{\"error\":\"docId is required\"}");
		}
		return ResponseEntity.ok(fabric.getDocument(docId));
	}

	@PostMapping("/{docId}/verify")
	public ResponseEntity<String> verify(@PathVariable String docId, @RequestBody Map<String, Object> body) throws Exception {
		String hash = (String) body.get("hash");
		if (docId == null || docId.isBlank() || hash == null || hash.isBlank()) {
			return ResponseEntity.badRequest().body("{\"error\":\"docId and hash are required\"}");
		}
		return ResponseEntity.ok(fabric.verifyDocument(docId, hash));
	}

	@PostMapping("/{docId}/revoke")
	public ResponseEntity<String> revoke(@PathVariable String docId, @RequestBody Map<String, Object> body) throws Exception {
		String reason = (String) body.getOrDefault("reason", "unspecified");
		if (docId == null || docId.isBlank()) {
			return ResponseEntity.badRequest().body("{\"error\":\"docId is required\"}");
		}
		return ResponseEntity.ok(fabric.revokeDocument(docId, reason));
	}
}
