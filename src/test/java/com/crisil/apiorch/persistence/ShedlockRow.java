package com.crisil.apiorch.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Test-only entity over the baseline {@code shedlock} table. It proves the {@code Instant} mapping (ADR-003 F6,
 * rule 5) before the first production entity exists.
 */
@Entity
@Table(name = "shedlock")
public class ShedlockRow {

    @Id
    @Column(name = "name")
    private String name;

    @Column(name = "lock_until")
    private Instant lockUntil;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    protected ShedlockRow() {
    }

    public ShedlockRow(String name, Instant lockUntil, Instant lockedAt, String lockedBy) {
        this.name = name;
        this.lockUntil = lockUntil;
        this.lockedAt = lockedAt;
        this.lockedBy = lockedBy;
    }

    public Instant lockUntil() {
        return lockUntil;
    }

    public Instant lockedAt() {
        return lockedAt;
    }
}
