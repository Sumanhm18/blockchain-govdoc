# Identity runtime remediation

The staging PostgreSQL and Keycloak images have been replaced with digest-pinned Alpine-based runtimes. PostgreSQL remains on major version 17 and now starts directly as its Alpine UID/GID 70 account. Its obsolete `/usr/local/bin/gosu` helper is removed; the full scan had found one critical and 24 high Go findings inside that helper even though the OS-only result was clear. Both migrated and fresh-volume initialization are validated. Keycloak remains 26.8.0, with its distribution copied from the pinned upstream image and optimized for PostgreSQL; the runtime uses Temurin OpenJDK 21 and runs as UID 1000. The build retains Keycloak's application libraries rather than silently removing flagged components.

Keycloak's supported Java/database combinations are described in its [supported configurations](https://www.keycloak.org/server/supported-configurations). This custom image is locally validated; it is not a claim of vendor certification for every optional extension, authentication method or FIPS deployment. Test those features under the intended production configuration.

## Measured OS results

| Image | Previous critical / high | Replacement critical / high | Remaining medium |
|---|---:|---:|---:|
| PostgreSQL | 1 / 63 | 0 / 0 | 1 |
| Keycloak | 0 / 6 | 0 / 0 | 2 |

The subsequent full Keycloak image scan, including Java libraries, also reported only two medium findings. No advisory exclusions were used. See [Security gate](SECURITY_GATE.md) for the final complete image inventory.

## Existing staging databases

Do not attach the old Debian database directory directly to Alpine. The libc/collation environment changes. `staging.sh` refuses to switch an existing unmigrated database automatically.

```sh
.runtime/tools/bin/python scripts/migrate-staging-postgres.py
```

Use a virtual environment with `config/operations-tools-requirements.txt` installed if the above environment does not exist. The migration is restricted to the owned local PostgreSQL 17 staging database. It stops the local identity/API writers, obtains a consistent custom-format logical dump, encrypts it with AES-256-GCM, restores to a separate volume, checks hashed identity/client/credential rows, and verifies HTTPS/OIDC login and citizen isolation. The old volume is retained. No live external database is migrated.

Backups are `.runtime/backups/keycloak-*.dump.gcm`; the separate wrapping key is `.runtime/secrets/identity-backup.key`. Keep both under restricted access and copy them to separately controlled backup systems for real disaster recovery. The migration record is `.runtime/staging/postgres-migration.json`. The destination uses UTF-8/C.UTF-8; rebuilds of indexes occur through logical restore. Production collation, extension, locale and uniqueness semantics need explicit review; matching local test rows does not establish compatibility for arbitrary government data.

On validation failure after switching, the script attempts to restart the old image and volume and records `rolled_back`. A later default startup refuses that state. For a reviewed manual rollback, use the `source_image` and `source_volume` from the protected migration record as `POSTGRES_IMAGE` and `POSTGRES_VOLUME` when running `scripts/staging.sh up`. Writes after cutover are not automatically copied back; this is a staging rollback procedure, not production bidirectional migration. Retained volumes are never automatically deleted.

## Validation

The migration completed with an encrypted backup, successful restore, identical hashed user/client/credential rows and all 37 HTTPS/OIDC checks passing. Tests passed again with the rebuilt Keycloak runtime. `scripts/verify-identity-images.py` verifies the actual deployed image IDs, non-root Keycloak user and destination volume; integration CI includes that check.

The original volume and encrypted backup remain available locally. No production deployment, remote credential rotation or off-site backup configuration is implied.
