package com.crisil.apiorch.pipeline;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PipelineRepository extends JpaRepository<PipelineEntity, String> {

    /** One row per pipeline for the admin list (US-3.1 AC5). */
    @Query("""
            select p.code as code, p.description as description, p.enabled as enabled,
                   (select max(d.version) from PipelineDefinitionEntity d where d.code = p.code and d.version > 0)
                       as latestVersion,
                   (select max(d.publishedAt) from PipelineDefinitionEntity d where d.code = p.code and d.version > 0)
                       as latestPublishedAt,
                   (select d.updatedAt from PipelineDefinitionEntity d where d.code = p.code and d.version = 0)
                       as draftUpdatedAt
            from PipelineEntity p order by p.code""")
    List<PipelineSummaryRow> summaries();

    interface PipelineSummaryRow {
        String getCode();
        String getDescription();
        boolean getEnabled();
        Integer getLatestVersion();
        java.time.Instant getLatestPublishedAt();
        java.time.Instant getDraftUpdatedAt();
    }
}
