package com.gov.docregistry.storage;

import com.gov.docregistry.web.RegistryException;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UploadReconciler {
  private static final Logger log = LoggerFactory.getLogger(UploadReconciler.class);
  private final UploadJournal journal;
  private final DocumentService documents;

  public UploadReconciler(UploadJournal journal, DocumentService documents) {
    this.journal = journal;
    this.documents = documents;
  }

  @Scheduled(
      fixedDelayString = "${registry.reconcile.delay-ms:30000}",
      initialDelayString = "${registry.reconcile.delay-ms:30000}")
  public void reconcile() {
    for (String id : journal.due()) {
      try {
        documents.advance(id);
      } catch (RegistryException e) {
        log.info("Reconciliation result={}", e.code());
      } catch (Exception e) {
        log.error("Reconciliation requires review type={}", e.getClass().getSimpleName());
      }
    }
  }
}
