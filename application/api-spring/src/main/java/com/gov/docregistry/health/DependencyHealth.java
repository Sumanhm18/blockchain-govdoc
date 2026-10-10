package com.gov.docregistry.health;

import com.gov.docregistry.fabric.DocumentLedger;
import com.gov.docregistry.ipfs.IpfsService;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component("registryDependencies")
public class DependencyHealth implements HealthIndicator {
  private final DocumentLedger ledger;
  private final IpfsService ipfs;

  public DependencyHealth(DocumentLedger ledger, IpfsService ipfs) {
    this.ledger = ledger;
    this.ipfs = ipfs;
  }

  @Override
  public Health health() {
    try {
      ledger.ping();
      ipfs.ping();
      return Health.up().build();
    } catch (Exception e) {
      return Health.down().build();
    }
  }
}
