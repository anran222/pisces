package com.pisces.service.service.impl;

import java.time.LocalDateTime;
import java.util.List;

final class ApprovalRiskContext {

    String riskLevel;

    List<String> riskFlags = List.of();

    String guardrailStatus;

    Boolean analysisReady;

    Boolean hasSrm;

    List<String> breachedGuardrails = List.of();

    Integer latestSnapshotVersion;

    LocalDateTime latestGeneratedAt;
}
