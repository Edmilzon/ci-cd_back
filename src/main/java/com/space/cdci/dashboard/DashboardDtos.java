package com.space.cdci.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class DashboardDtos {
	private DashboardDtos() {}

	public record Summary(int projectCount, long pipelineCount, long runsToday, long activeRuns,
			double successRate30Days, Long medianDurationMillis30Days, List<DailyRuns> dailyRuns,
			List<RecentRun> recentRuns, List<RecentDeployment> recentDeployments) {}
	public record DailyRuns(LocalDate day, long succeeded, long failed, long active) {}
	public record RecentRun(UUID id, UUID projectId, UUID pipelineId, String projectName, String pipelineName,
			String repositoryName, String branchName, int version, String status, String requestedBy,
			Instant requestedAt, Instant startedAt, Instant completedAt, Long durationMillis) {}
	public record RecentDeployment(UUID id, UUID projectId, UUID runId, String projectName, String pipelineName,
			String repositoryName, String environmentName, String sourceCommitSha, String status,
			Instant requestedAt, String githubHtmlUrl) {}
}
