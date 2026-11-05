package com.crisil.apiorch.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.crisil.apiorch.pipeline.dto.DraftRequest;
import com.crisil.apiorch.pipeline.dto.DraftResponse;

import testsupport.db.SharedSqlServer;

/** Slow tier: drafts on SQL Server (ISJSON check on content, the summary query, If-Match replace). */
@Tag("slow")
@SpringBootTest
@ActiveProfiles("test")
@ImportTestcontainers(SharedSqlServer.class)
class DraftSqlServerIT {

    @Autowired DraftService drafts;
    @Autowired ObjectMapper json;

    @Test
    void draftLifecycleOnSqlServer() throws Exception {
        DraftRequest create = new DraftRequest("MSSQL_DRAFT", "on sql server", json.readTree("""
                {"code":"MSSQL_DRAFT","steps":[{"id":"a","endpoint":"e","method":"GET","path":"/a"}]}"""));
        DraftResponse created = drafts.create(create, "tester");
        assertThat(created.rowVersion()).isZero();
        assertThat(created.validation().valid()).isFalse();

        DraftResponse replaced = drafts.put("MSSQL_DRAFT", new DraftRequest(null, null, create.definition()), 0L,
                "tester");
        assertThat(replaced.rowVersion()).isEqualTo(1);
        assertThat(drafts.get("MSSQL_DRAFT").definition().path("dslVersion").asInt()).isEqualTo(1);
        assertThat(drafts.list()).anySatisfy(s -> {
            assertThat(s.code()).isEqualTo("MSSQL_DRAFT");
            assertThat(s.hasDraft()).isTrue();
            assertThat(s.latestVersion()).isNull();
        });
        drafts.delete("MSSQL_DRAFT", "tester");
        assertThat(drafts.list()).anySatisfy(s -> assertThat(s.hasDraft()).isFalse());
    }
}
