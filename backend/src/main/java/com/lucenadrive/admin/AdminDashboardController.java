package com.lucenadrive.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@Tag(name = "Admin · Dashboard")
public class AdminDashboardController {

    private final DashboardService dashboard;

    public AdminDashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/summary")
    public DashboardService.Summary summary() {
        return dashboard.summary();
    }

    @GetMapping("/timeseries")
    public List<DashboardService.DayPoint> timeseries(@RequestParam(defaultValue = "14") int days) {
        return dashboard.timeseries(days);
    }

    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        return dashboard.alerts();
    }
}
