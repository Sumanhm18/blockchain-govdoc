package com.gov.docregistry.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RegistryMetrics implements MeterBinder {
  private final JdbcTemplate jdbc;

  public RegistryMetrics(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(
            "registry.upload.pending",
            jdbc,
            j ->
                j.queryForObject(
                    "SELECT COUNT(*) FROM upload_jobs WHERE state NOT IN ('CONFIRMED','REJECTED')",
                    Double.class))
        .description("Uploads awaiting ledger confirmation")
        .register(registry);
    Gauge.builder(
            "registry.upload.rejected",
            jdbc,
            j ->
                j.queryForObject(
                    "SELECT COUNT(*) FROM upload_jobs WHERE state='REJECTED'", Double.class))
        .description("Rejected uploads requiring review")
        .register(registry);
    Gauge.builder(
            "registry.upload.oldest.pending.seconds",
            jdbc,
            j -> {
              Long updated =
                  j.queryForObject(
                      "SELECT MIN(updated_at) FROM upload_jobs WHERE state NOT IN"
                          + " ('CONFIRMED','REJECTED')",
                      Long.class);
              return updated == null
                  ? 0
                  : Math.max(0, (System.currentTimeMillis() - updated) / 1000.0);
            })
        .description("Age since last pending upload update")
        .register(registry);
  }
}
