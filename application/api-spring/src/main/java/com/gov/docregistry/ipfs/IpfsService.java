package com.gov.docregistry.ipfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gov.docregistry.web.RegistryException;
import java.time.Duration;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IpfsService {
  private final OkHttpClient http;
  private final HttpUrl base;
  private final ObjectMapper mapper;
  private final int maxBytes;

  public IpfsService(
      ObjectMapper mapper,
      @Value("${ipfs.apiBase:http://127.0.0.1:5001}") String apiBase,
      @Value("${registry.upload.max-bytes:10485760}") int maxBytes) {
    this.mapper = mapper;
    this.base = HttpUrl.get(apiBase);
    this.maxBytes = maxBytes;
    this.http =
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(35))
            .retryOnConnectionFailure(false)
            .build();
  }

  public String addBytes(byte[] data, String fileName) {
    RequestBody body =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "encrypted-document.bin",
                RequestBody.create(data, MediaType.get("application/octet-stream")))
            .build();
    Request request =
        new Request.Builder()
            .url(
                base.newBuilder()
                    .encodedPath("/api/v0/add")
                    .addQueryParameter("pin", "true")
                    .addQueryParameter("cid-version", "1")
                    .build())
            .post(body)
            .build();
    try (Response response = http.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null)
        throw RegistryException.unavailable();
      byte[] bytes = response.body().byteStream().readNBytes(65537);
      if (bytes.length > 65536) throw RegistryException.unavailable();
      String cid = mapper.readTree(bytes).path("Hash").asText();
      validateCid(cid);
      return cid;
    } catch (Exception e) {
      throw RegistryException.unavailable();
    }
  }

  public byte[] getBytes(String cid) {
    validateCid(cid);
    Request request =
        new Request.Builder()
            .url(base.newBuilder().encodedPath("/api/v0/cat").addQueryParameter("arg", cid).build())
            .post(RequestBody.create(new byte[0], null))
            .build();
    try (Response response = http.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null)
        throw RegistryException.unavailable();
      byte[] bytes = response.body().byteStream().readNBytes(maxBytes + 65);
      if (bytes.length > maxBytes + 64) throw RegistryException.unavailable();
      return bytes;
    } catch (Exception e) {
      throw RegistryException.unavailable();
    }
  }

  public void ping() {
    Request request =
        new Request.Builder()
            .url(base.newBuilder().encodedPath("/api/v0/version").build())
            .post(RequestBody.create(new byte[0], null))
            .build();
    try (Response response = http.newCall(request).execute()) {
      if (!response.isSuccessful()) throw RegistryException.unavailable();
    } catch (Exception e) {
      throw RegistryException.unavailable();
    }
  }

  private static void validateCid(String cid) {
    if (cid == null || !cid.matches("[a-zA-Z0-9]{20,120}"))
      throw RegistryException.validation("Invalid content identifier");
  }
}
