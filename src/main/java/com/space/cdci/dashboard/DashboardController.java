package com.space.cdci.dashboard;

import com.space.cdci.identity.CdciPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
	private final DashboardService dashboard;

	public DashboardController(DashboardService dashboard) { this.dashboard = dashboard; }

	@GetMapping
	public DashboardDtos.Summary get(@AuthenticationPrincipal CdciPrincipal actor) { return dashboard.summary(actor); }
}
