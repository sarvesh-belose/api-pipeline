package probe;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RunRowRepo extends JpaRepository<RunRow, UUID> {
}
