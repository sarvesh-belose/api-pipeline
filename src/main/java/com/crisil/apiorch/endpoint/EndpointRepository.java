package com.crisil.apiorch.endpoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EndpointRepository extends JpaRepository<EndpointEntity, UUID> {

    Optional<EndpointEntity> findByName(String name);

    boolean existsByName(String name);

    List<EndpointEntity> findAllByOrderByNameAsc();

    /** name and row_version of every endpoint, for the cheap cross-node refresh check. */
    @Query("select e.name as name, e.rowVersion as rowVersion from EndpointEntity e")
    List<NameAndVersion> findAllVersions();

    @Query("select e.name as name, e.enabled as enabled from EndpointEntity e")
    List<NameAndEnabled> findAllStates();

    interface NameAndEnabled {
        String getName();

        boolean getEnabled();
    }

    interface NameAndVersion {
        String getName();

        long getRowVersion();
    }
}
