package com.space.cdci.dashboard;

import com.space.cdci.identity.CdciPrincipal;
import com.space.cdci.pipelines.PipelineDefinitionRepository;
import com.space.cdci.pipelines.PipelineRun;
import com.space.cdci.pipelines.PipelineRunRepository;
import com.space.cdci.pipelines.PipelineRunStatus;
import com.space.cdci.projects.ProjectDtos;
import com.space.cdci.projects.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
	private static final ZoneId DASHBOARD_ZONE = ZoneId.of("America/La_Paz");
	private static final List<PipelineRunStatus> COMPLETED = List.of(PipelineRunStatus.SUCCEEDED, PipelineRunStatus.FAILED);
	private static final List<PipelineRunStatus> ACTIVE = List.of(PipelineRunStatus.QUEUED, PipelineRunStatus.RUNNING);
	private final ProjectService projects;
	private final PipelineDefinitionRepository pipelines;
	private final PipelineRunRepository runs;

	public DashboardService(ProjectService projects, PipelineDefinitionRepository pipelines, PipelineRunRepository runs) {
		this.projects = projects;
		this.pipelines = pipelines;
		this.runs = runs;
	}

	@Transactional(readOnly = true)
	public DashboardDtos.Summary summary(CdciPrincipal actor) {
		List<ProjectDtos.ProjectSummary> visibleProjects = projects.list(actor);
		List<UUID> projectIds = visibleProjects.stream().map(ProjectDtos.ProjectSummary::id).toList();
		ZonedDateTime now = ZonedDateTime.now(DASHBOARD_ZONE);
		Instant todayStart = now.toLocalDate().atStartOfDay(DASHBOARD_ZONE).toInstant();
		Instant thirtyDaysAgo = now.toInstant().minus(Duration.ofDays(30));
		List<DashboardDtos.DailyRuns> daily = dailyRuns(now.toLocalDate(), projectIds);
		if (projectIds.isEmpty()) {
			return new DashboardDtos.Summary(0, 0, 0, 0, 0, null, daily, List.of());
		}

		long runsToday = runs.countByPipeline_Project_IdInAndRequestedAtGreaterThanEqual(projectIds, todayStart);
		long activeRuns = runs.countByPipeline_Project_IdInAndStatusIn(projectIds, ACTIVE);
		long completed = runs.countByPipeline_Project_IdInAndStatusInAndRequestedAtGreaterThanEqual(projectIds, COMPLETED, thirtyDaysAgo);
		long succeeded = runs.countByPipeline_Project_IdInAndStatusAndRequestedAtGreaterThanEqual(projectIds,
				PipelineRunStatus.SUCCEEDED, thirtyDaysAgo);
		double successRate = completed == 0 ? 0 : Math.round((succeeded * 10000.0) / completed) / 100.0;
		Double median = runs.dashboardMedianDurationMillis(projectIds, thirtyDaysAgo);
		List<DashboardDtos.RecentRun> recent = runs.findTop50ByPipeline_Project_IdInOrderByRequestedAtDesc(projectIds)
				.stream().map(DashboardService::recentRun).toList();
		return new DashboardDtos.Summary(visibleProjects.size(), pipelines.countByProject_IdIn(projectIds), runsToday,
				activeRuns, successRate, median == null ? null : Math.round(median), daily, recent);
	}

	private List<DashboardDtos.DailyRuns> dailyRuns(LocalDate today, List<UUID> projectIds) {
		Map<LocalDate, DashboardDtos.DailyRuns> byDay = new HashMap<>();
		if (!projectIds.isEmpty()) {
			Instant firstDay = today.minusDays(6).atStartOfDay(DASHBOARD_ZONE).toInstant();
			for (Object[] row : runs.dashboardDailyCounts(projectIds, firstDay)) {
				LocalDate day = row[0] instanceof LocalDate value ? value : ((java.sql.Date) row[0]).toLocalDate();
				byDay.put(day, new DashboardDtos.DailyRuns(day, number(row[1]), number(row[2]), number(row[3])));
			}
		}
		List<DashboardDtos.DailyRuns> result = new ArrayList<>(7);
		for (int offset = 6; offset >= 0; offset--) {
			LocalDate day = today.minusDays(offset);
			result.add(byDay.getOrDefault(day, new DashboardDtos.DailyRuns(day, 0, 0, 0)));
		}
		return List.copyOf(result);
	}

	private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }

	private static DashboardDtos.RecentRun recentRun(PipelineRun run) {
		Long duration = run.getStartedAt() == null || run.getCompletedAt() == null ? null
				: Math.max(0, Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis());
		return new DashboardDtos.RecentRun(run.getId(), run.getPipeline().getProject().getId(), run.getPipeline().getId(),
			run.getPipeline().getProject().getName(), run.getPipeline().getName(), run.getPipeline().getRepository().getFullName(),
			run.getBranchName(), run.getPipelineVersion().getVersionNumber(), run.getStatus().name(),
			run.getRequestedBy().getEmail(), run.getRequestedAt(), run.getStartedAt(), run.getCompletedAt(), duration);
	}
}
