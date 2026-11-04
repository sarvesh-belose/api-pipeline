package probe;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Read-only JPA view of pipeline_run (README §5.1 rule 2): DATETIME2 UTC and NVARCHAR(MAX) JSON on Hibernate 6.6. */
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
    String meta;
}
