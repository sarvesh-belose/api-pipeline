package archfixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Violates PIPELINE_RUN_ENTITY_IS_READ_ONLY. */
@Entity
@Table(name = "pipeline_run")
public class MutablePipelineRun {
    @Id
    String id;
}
