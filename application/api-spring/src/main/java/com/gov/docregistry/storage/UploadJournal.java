package com.gov.docregistry.storage;

import com.gov.docregistry.web.RegistryException;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UploadJournal {
  public record Job(
      String docId,
      String fingerprint,
      String registrationId,
      String request,
      byte[] ciphertext,
      String state,
      int attempts,
      String code,
      long updatedAt) {}

  private final JdbcTemplate jdbc;

  public UploadJournal(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS upload_jobs (doc_id VARCHAR(50) PRIMARY KEY, fingerprint"
            + " VARCHAR(71) NOT NULL, registration_id VARCHAR(36) NOT NULL, request_json CLOB NOT"
            + " NULL, ciphertext BLOB, state VARCHAR(20) NOT NULL, attempts INT NOT NULL DEFAULT 0,"
            + " code VARCHAR(40) NOT NULL DEFAULT '', updated_at BIGINT NOT NULL, next_attempt"
            + " BIGINT NOT NULL DEFAULT 0)");
  }

  public Optional<Job> find(String id) {
    return jdbc
        .query(
            "SELECT * FROM upload_jobs WHERE doc_id=?",
            (rs, row) ->
                new Job(
                    rs.getString("doc_id"),
                    rs.getString("fingerprint"),
                    rs.getString("registration_id"),
                    rs.getString("request_json"),
                    rs.getBytes("ciphertext"),
                    rs.getString("state"),
                    rs.getInt("attempts"),
                    rs.getString("code"),
                    rs.getLong("updated_at")),
            id)
        .stream()
        .findFirst();
  }

  public void create(Job job) {
    Long total =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(OCTET_LENGTH(ciphertext)),0) FROM upload_jobs", Long.class);
    if (total != null && total + job.ciphertext().length > 536870912L)
      throw new RegistryException(
          "STORAGE_CAPACITY",
          HttpStatus.SERVICE_UNAVAILABLE,
          "Upload journal requires operator attention");
    try {
      jdbc.update(
          "INSERT INTO"
              + " upload_jobs(doc_id,fingerprint,registration_id,request_json,ciphertext,state,updated_at)"
              + " VALUES(?,?,?,?,?,?,?)",
          job.docId(),
          job.fingerprint(),
          job.registrationId(),
          job.request(),
          job.ciphertext(),
          job.state(),
          job.updatedAt());
    } catch (DuplicateKeyException e) {
      throw new RegistryException(
          "DUPLICATE", HttpStatus.CONFLICT, "Document registration already started");
    }
  }

  public void update(String id, String state, String request, String code, boolean clearPayload) {
    jdbc.update(
        "UPDATE upload_jobs SET state=?,request_json=?,code=?,updated_at=?,ciphertext=CASE WHEN ?"
            + " THEN NULL ELSE ciphertext END WHERE doc_id=?",
        state,
        request,
        code,
        System.currentTimeMillis(),
        clearPayload,
        id);
  }

  public void defer(String id, String code) {
    Job job = find(id).orElseThrow();
    long delay = Math.min(300000L, 5000L * (1L << Math.min(job.attempts(), 6)));
    jdbc.update(
        "UPDATE upload_jobs SET code=?,attempts=attempts+1,next_attempt=?,updated_at=? WHERE"
            + " doc_id=?",
        code,
        System.currentTimeMillis() + delay,
        System.currentTimeMillis(),
        id);
  }

  public List<String> due() {
    return jdbc.queryForList(
        "SELECT doc_id FROM upload_jobs WHERE state NOT IN ('CONFIRMED','REJECTED') AND"
            + " next_attempt<=? ORDER BY updated_at LIMIT 20",
        String.class,
        System.currentTimeMillis());
  }
}
