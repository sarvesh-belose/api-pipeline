package com.crisil.apiorch.pipeline;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineDefinitionRepository extends JpaRepository<PipelineDefinitionEntity, UUID> {

    Optional<PipelineDefinitionEntity> findByCodeAndVersion(String code, int version);
}
