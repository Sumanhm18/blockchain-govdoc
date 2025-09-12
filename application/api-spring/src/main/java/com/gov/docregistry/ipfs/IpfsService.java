package com.gov.docregistry.ipfs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import okhttp3.*;

@Service
public class IpfsService {
	private final OkHttpClient http = new OkHttpClient();
	private final String apiBase;

	public IpfsService(@Value("${ipfs.apiBase:http://127.0.0.1:5001}") String apiBase) {
		this.apiBase = apiBase;
	}

	public String addBytes(byte[] data, String fileName) throws Exception {
		MediaType octet = MediaType.parse("application/octet-stream");
		RequestBody file = RequestBody.create(data, octet);
		MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("file", fileName, file)
				.build();
		Request req = new Request.Builder()
				.url(apiBase + "/api/v0/add?pin=true")
				.post(body)
				.build();
		try (Response resp = http.newCall(req).execute()) {
			if (!resp.isSuccessful()) throw new RuntimeException("IPFS add failed: " + resp.code());
			String line = resp.body().string();
			// Response like: {"Name":"file","Hash":"<cid>",...}
			int i = line.indexOf("\"Hash\":\"");
			if (i < 0) throw new RuntimeException("No CID in response: " + line);
			int s = i + 8;
			int e = line.indexOf('"', s);
			return line.substring(s, e);
		}
	}

	public String getVersion() throws Exception {
		Request req = new Request.Builder()
				.url(apiBase + "/api/v0/version")
				.post(RequestBody.create("", MediaType.parse("application/json")))
				.build();
		try (Response resp = http.newCall(req).execute()) {
			if (!resp.isSuccessful()) throw new RuntimeException("IPFS version failed: " + resp.code());
			return resp.body().string();
		}
	}
}
