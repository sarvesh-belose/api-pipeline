package probe;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read-only JPA view of pipeline_run (README §5.1 rule 2): timestamptz and jsonb mapping on Hibernate 7. */
@Entity
@Immutable
@Table(name = "pipeline_run")
public class RunRow {
    @Id
    UUID id;
    String status;
    @Column(name = "created_at")
    Instant createdAt;
    @Column(name = "lease_until")
    Instant leaseUntil;
    @Column(name = "lease_epoch")
    long leaseEpoch;
    @JdbcTypeCode(SqlTypes.JSON)
    String meta;
}
