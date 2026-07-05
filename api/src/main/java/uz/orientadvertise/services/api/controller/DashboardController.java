package uz.orientadvertise.services.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.service.DashboardService;
import uz.orientadvertise.services.service.DashboardService.DashboardSummary;
import uz.orientadvertise.services.service.OperatorScopeResolver;

@Tag(name = "Reports", description = "Dashboard summary backing FE-11/FE-12")
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final OperatorScopeResolver operatorScopeResolver;

    public DashboardController(DashboardService dashboardService,
                             OperatorScopeResolver operatorScopeResolver) {
        this.dashboardService = dashboardService;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    @Operation(
            summary = "Aggregated dashboard summary",
            description = """
                    Single-shot aggregation: total devices, status breakdown \
                    (ONLINE / OFFLINE / NO_CONTENT), open incidents split by CRITICAL and \
                    WARNING, and per-region online vs total counts. Cached for 30 seconds; \
                    counts are zero-filled — every status bucket and region appears in the \
                    response even when empty, so the frontend renders without null checks.
                    """
    )
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Dashboard summary",
            content = @Content(examples = @ExampleObject(value = """
                    {
                      "totalDevices": 12,
                      "onlineCount": 8,
                      "offlineCount": 3,
                      "noContentCount": 1,
                      "openIncidents": { "critical": 2, "warning": 5 },
                      "regionSummary": [
                        { "regionId": 1, "regionName": "Karachi", "onlineCount": 5, "totalCount": 7 },
                        { "regionId": 2, "regionName": "Lahore",  "onlineCount": 3, "totalCount": 5 },
                        { "regionId": 3, "regionName": "Quetta",  "onlineCount": 0, "totalCount": 0 }
                      ]
                    }
                    """))))
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<DashboardSummary> getSummary() {
        return ResponseEntity.ok(dashboardService.getSummary(operatorScopeResolver.resolve()));
    }
}
