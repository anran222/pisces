package com.pisces.service.conclusion;

import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.ExperimentReportSnapshot;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 实验结论状态策略
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/18 14:55
 */
public final class ExperimentConclusionStatusPolicy {

    private ExperimentConclusionStatusPolicy() {
    }

    /**
     * 解析最新分析建议状态
     */
    public static ExperimentMetadata.ConclusionStatus resolveSuggestedStatus(List<ExperimentReportSnapshot> snapshots) {
        ExperimentReportSnapshot latestSnapshot = resolveLatestSnapshot(snapshots);
        if (latestSnapshot == null) {
            return null;
        }
        return latestSnapshot.getConclusionStatus();
    }

    /**
     * 解析最新分析建议更新时间
     */
    public static LocalDateTime resolveSuggestedUpdatedAt(List<ExperimentReportSnapshot> snapshots) {
        ExperimentReportSnapshot latestSnapshot = resolveLatestSnapshot(snapshots);
        if (latestSnapshot == null) {
            return null;
        }
        return latestSnapshot.getGeneratedAt();
    }

    /**
     * 解析最新报告快照
     */
    public static ExperimentReportSnapshot resolveLatestSnapshot(List<ExperimentReportSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        return snapshots.stream()
                .filter(Objects::nonNull)
                .filter(snapshot -> snapshot.getSnapshotVersion() != null)
                .max(Comparator.comparing(ExperimentReportSnapshot::getSnapshotVersion))
                .orElseGet(() -> snapshots.stream()
                        .filter(Objects::nonNull)
                        .max(Comparator.comparing(ExperimentReportSnapshot::getGeneratedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                        .orElse(null));
    }
}
