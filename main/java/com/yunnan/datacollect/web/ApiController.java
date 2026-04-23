package com.yunnan.datacollect.web;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yunnan.datacollect.service.PlatformService;
import com.yunnan.datacollect.service.PlatformService.BatchCityReviewRequest;
import com.yunnan.datacollect.service.PlatformService.BatchProvinceReviewRequest;
import com.yunnan.datacollect.service.PlatformService.ChangePasswordRequest;
import com.yunnan.datacollect.service.PlatformService.ComparisonRequest;
import com.yunnan.datacollect.service.PlatformService.EnterpriseRequest;
import com.yunnan.datacollect.service.PlatformService.LoginRequest;
import com.yunnan.datacollect.service.PlatformService.MonthlyReportRequest;
import com.yunnan.datacollect.service.PlatformService.NoticeRequest;
import com.yunnan.datacollect.service.PlatformService.PasswordResetRequest;
import com.yunnan.datacollect.service.PlatformService.SurveyPeriodRequest;
import com.yunnan.datacollect.service.PlatformService.TrendRequest;
import com.yunnan.datacollect.service.PlatformService.UserAdminResetPasswordRequest;
import com.yunnan.datacollect.service.PlatformService.UserCreateRequest;
import com.yunnan.datacollect.service.PlatformService.UserEnableRequest;
import com.yunnan.datacollect.service.PlatformService.UserRoleUpdateRequest;
import com.yunnan.datacollect.service.PlatformService.UserUnlockRequest;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final PlatformService platformService;

    public ApiController(PlatformService platformService) {
        this.platformService = platformService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<?> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.ok(platformService.login(request, clientIp(servletRequest)));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<?> logout(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        platformService.logout(token);
        return ApiResponse.ok("已退出登录", null);
    }

    @PostMapping("/auth/change-password")
    public ApiResponse<?> changePassword(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                         @RequestBody ChangePasswordRequest request) {
        platformService.changePassword(token, request);
        return ApiResponse.ok("密码修改成功，请重新登录", null);
    }

    @PostMapping("/auth/reset-password")
    public ApiResponse<?> resetPassword(@RequestBody PasswordResetRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.ok(platformService.resetPassword(request, clientIp(servletRequest)));
    }

    @GetMapping("/auth/me")
    public ApiResponse<?> me(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ApiResponse.ok(platformService.currentUser(token));
    }

    @GetMapping("/dashboard")
    public ApiResponse<?> dashboard(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ApiResponse.ok(platformService.dashboard(token));
    }

    @GetMapping("/periods")
    public ApiResponse<?> periods(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ApiResponse.ok(platformService.listPeriods(token));
    }

    @PostMapping("/periods")
    public ApiResponse<?> savePeriod(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                     @RequestBody SurveyPeriodRequest request) {
        return ApiResponse.ok(platformService.savePeriod(token, request));
    }

    @GetMapping("/enterprises")
    public ApiResponse<?> enterprises(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String city,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String nature,
                                      @RequestParam(required = false) String industry,
                                      @RequestParam(required = false) String orgCode,
                                      @RequestParam(defaultValue = "1") Integer page,
                                      @RequestParam(defaultValue = "10") Integer size) {
        return ApiResponse.ok(platformService.listEnterprisesPage(token, status, city, keyword, nature, industry, orgCode, page, size));
    }

    @PostMapping("/enterprises/save")
    public ApiResponse<?> saveEnterprise(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                         @RequestParam(defaultValue = "false") boolean submit,
                                         @RequestBody EnterpriseRequest request) {
        return ApiResponse.ok(platformService.saveEnterpriseProfile(token, request, submit));
    }

    @PostMapping("/enterprises/{enterpriseId}/review")
    public ApiResponse<?> reviewEnterprise(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                           @PathVariable long enterpriseId,
                                           @RequestParam boolean approved,
                                           @RequestParam(required = false) String reason) {
        return ApiResponse.ok(platformService.reviewEnterprise(token, enterpriseId, approved, reason));
    }

    @GetMapping("/reports")
    public ApiResponse<?> reports(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) Long periodId,
                                  @RequestParam(required = false) String city) {
        return ApiResponse.ok(platformService.listReports(token, status, periodId, city));
    }

    @GetMapping("/reports/page")
    public ApiResponse<?> reportsPage(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Long periodId,
                                      @RequestParam(required = false) String city,
                                      @RequestParam(defaultValue = "1") Integer page,
                                      @RequestParam(defaultValue = "10") Integer size) {
        return ApiResponse.ok(platformService.listReportsPage(token, status, periodId, city, page, size));
    }

    @PostMapping("/reports/save")
    public ApiResponse<?> saveReport(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                     @RequestParam(defaultValue = "false") boolean submit,
                                     @RequestBody MonthlyReportRequest request) {
        return ApiResponse.ok(platformService.saveReport(token, request, submit));
    }

    @PostMapping("/reports/{reportId}/submit")
    public ApiResponse<?> submitReport(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                       @PathVariable long reportId) {
        return ApiResponse.ok(platformService.submitReport(token, reportId));
    }

    @PostMapping("/reports/{reportId}/city-review")
    public ApiResponse<?> cityReview(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                     @PathVariable long reportId,
                                     @RequestParam boolean approved,
                                     @RequestParam(required = false) String reason) {
        return ApiResponse.ok(platformService.reviewCityReport(token, reportId, approved, reason));
    }

    @PostMapping("/reports/city-review/batch")
    public ApiResponse<?> cityReviewBatch(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                          @RequestBody BatchCityReviewRequest request) {
        return ApiResponse.ok(platformService.reviewCityReportBatch(token, request));
    }

    @PostMapping("/reports/{reportId}/province-review")
    public ApiResponse<?> provinceReview(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                          @PathVariable long reportId,
                                          @RequestParam boolean approved,
                                          @RequestParam(required = false) String reason) {
        return ApiResponse.ok(platformService.reviewProvinceReport(token, reportId, approved, reason));
    }

    @PostMapping("/reports/province-review/batch")
    public ApiResponse<?> provinceReviewBatch(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                              @RequestBody BatchProvinceReviewRequest request) {
        return ApiResponse.ok(platformService.reviewProvinceReportBatch(token, request));
    }

    @PostMapping("/reports/{reportId}/province-correct")
    public ApiResponse<?> provinceCorrect(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                           @PathVariable long reportId,
                                           @RequestBody MonthlyReportRequest request) {
        return ApiResponse.ok(platformService.provinceCorrectReport(token, reportId, request));
    }

    @PostMapping("/reports/publish")
    public ApiResponse<?> publishToMinistry(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                            @RequestParam(required = false) Long periodId) {
        return ApiResponse.ok(platformService.publishToMinistry(token, periodId));
    }

    @GetMapping("/ministry/export")
    public ResponseEntity<byte[]> exportMinistryXml(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                    @RequestParam(required = false) Long periodId) {
        String xml = platformService.exportMinistryXml(token, periodId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ministry-report.xml")
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/ministry/preview")
    public ApiResponse<?> ministryPreview(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                          @RequestParam(required = false) Long periodId) {
        return ApiResponse.ok(platformService.ministryExportPreview(token, periodId));
    }

    @GetMapping("/ministry/export-json")
    public ApiResponse<?> exportMinistryJson(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                             @RequestParam(required = false) Long periodId,
                                             @RequestParam(defaultValue = "false") boolean publish) {
        return ApiResponse.ok(platformService.exportMinistryJson(token, periodId, publish));
    }

    @GetMapping("/ministry/export-excel")
    public ResponseEntity<byte[]> exportMinistryExcel(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                      @RequestParam(required = false) Long periodId) {
        byte[] file = platformService.exportMinistryExcel(token, periodId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ministry-report.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @GetMapping("/notices")
    public ApiResponse<?> notices(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String city,
                                  @RequestParam(required = false) String createdFrom,
                                  @RequestParam(required = false) String createdTo,
                                  @RequestParam(defaultValue = "1") Integer page,
                                  @RequestParam(defaultValue = "10") Integer size) {
        return ApiResponse.ok(platformService.listNoticesPage(token, status, keyword, city, createdFrom, createdTo, page, size));
    }

    @PostMapping("/notices/save")
    public ApiResponse<?> saveNotice(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                     @RequestParam(defaultValue = "true") boolean publishNow,
                                     @RequestBody NoticeRequest request) {
        return ApiResponse.ok(platformService.saveNotice(token, request, publishNow));
    }

    @PostMapping("/notices/{noticeId}/delete")
    public ApiResponse<?> deleteNotice(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                       @PathVariable long noticeId) {
        return ApiResponse.ok(platformService.deleteNotice(token, noticeId));
    }

    @GetMapping("/summary")
    public ApiResponse<?> summary(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                  @RequestParam(required = false) Long periodId) {
        return ApiResponse.ok(platformService.summary(token, periodId));
    }

    @GetMapping("/sampling")
    public ApiResponse<?> sampling(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                   @RequestParam(required = false) String city) {
        return ApiResponse.ok(platformService.sampling(token, city));
    }

    @PostMapping("/comparison")
    public ApiResponse<?> comparison(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                     @RequestBody ComparisonRequest request) {
        return ApiResponse.ok(platformService.comparison(token, request));
    }

    @PostMapping("/trend")
    public ApiResponse<?> trend(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                @RequestBody TrendRequest request) {
        return ApiResponse.ok(platformService.trend(token, request));
    }

    @GetMapping("/monitor")
    public ApiResponse<?> monitor(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ApiResponse.ok(platformService.monitor());
    }

    @GetMapping("/logs")
    public ApiResponse<?> logs(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                               @RequestParam(required = false) String targetType,
                               @RequestParam(required = false) Long targetId) {
        return ApiResponse.ok(platformService.logs(token, targetType, targetId));
    }

    @GetMapping("/logs/page")
    public ApiResponse<?> logsPage(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                   @RequestParam(required = false) String targetType,
                                   @RequestParam(required = false) Long targetId,
                                   @RequestParam(required = false) String action,
                                   @RequestParam(required = false) String actorName,
                                   @RequestParam(required = false) String createdFrom,
                                   @RequestParam(required = false) String createdTo,
                                   @RequestParam(defaultValue = "1") Integer page,
                                   @RequestParam(defaultValue = "10") Integer size) {
        return ApiResponse.ok(platformService.logsPage(token, targetType, targetId, action, actorName, createdFrom, createdTo, page, size));
    }

    @GetMapping("/logs/export-csv")
    public ResponseEntity<byte[]> exportLogsCsv(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                @RequestParam(required = false) String targetType,
                                                @RequestParam(required = false) Long targetId,
                                                @RequestParam(required = false) String action,
                                                @RequestParam(required = false) String actorName,
                                                @RequestParam(required = false) String createdFrom,
                                                @RequestParam(required = false) String createdTo) {
        byte[] file = platformService.exportLogsCsv(token, targetType, targetId, action, actorName, createdFrom, createdTo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-logs.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file);
    }

    @GetMapping("/transmissions")
    public ApiResponse<?> transmissions(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                        @RequestParam(required = false) Long periodId) {
        return ApiResponse.ok(platformService.listTransmissions(token, periodId));
    }

    @PostMapping("/users")
    public ApiResponse<?> createUser(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                     @RequestBody UserCreateRequest request) {
        return ApiResponse.ok(platformService.createUser(token, request));
    }

    @GetMapping("/users")
    public ApiResponse<?> users(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                @RequestParam(required = false) String keyword,
                                @RequestParam(required = false) String role,
                                @RequestParam(required = false) String city,
                                @RequestParam(required = false) Boolean enabled,
                                @RequestParam(defaultValue = "1") Integer page,
                                @RequestParam(defaultValue = "10") Integer size) {
        return ApiResponse.ok(platformService.listUsersPage(token, keyword, role, city, enabled, page, size));
    }

    @PostMapping("/users/role")
    public ApiResponse<?> updateUserRole(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                         @RequestBody UserRoleUpdateRequest request) {
        return ApiResponse.ok(platformService.updateUserRole(token, request));
    }

    @PostMapping("/users/enabled")
    public ApiResponse<?> updateUserEnabled(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                            @RequestBody UserEnableRequest request) {
        return ApiResponse.ok(platformService.setUserEnabled(token, request));
    }

    @PostMapping("/users/unlock")
    public ApiResponse<?> unlockUser(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                     @RequestBody UserUnlockRequest request) {
        return ApiResponse.ok(platformService.unlockUser(token, request));
    }

    @PostMapping("/users/reset-password-admin")
    public ApiResponse<?> resetUserPasswordByAdmin(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                   @RequestBody UserAdminResetPasswordRequest request) {
        return ApiResponse.ok(platformService.adminResetUserPassword(token, request));
    }

    @GetMapping("/dashboard/export/notices")
    public ResponseEntity<byte[]> exportNotices(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        byte[] file = platformService.exportNoticesExcel(token, platformService.listNotices(token, null, null, null, null, null));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=notices.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @GetMapping("/dashboard/export/reports")
    public ResponseEntity<byte[]> exportReports(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        byte[] file = platformService.exportReportsExcel(token, platformService.listReports(token, null, null, null));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reports.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @GetMapping("/dashboard/export/reports-csv")
    public ResponseEntity<byte[]> exportReportsCsv(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        byte[] file = platformService.exportReportsCsv(token, platformService.listReports(token, null, null, null));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reports.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file);
    }

    @GetMapping("/dashboard/export/enterprises")
    public ResponseEntity<byte[]> exportEnterprises(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        byte[] file = platformService.exportEnterprisesExcel(token, platformService.listEnterprises(token, null, null, null, null, null, null));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=enterprises.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @GetMapping("/dashboard/export/enterprises-csv")
    public ResponseEntity<byte[]> exportEnterprisesCsv(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        byte[] file = platformService.exportEnterprisesCsv(token, platformService.listEnterprises(token, null, null, null, null, null, null));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=enterprises.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file);
    }

    @GetMapping("/dashboard/export/summary")
    public ResponseEntity<byte[]> exportSummary(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                                @RequestParam(required = false) Long periodId) {
        byte[] file = platformService.exportSummaryExcel(token, periodId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=summary.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

            @GetMapping("/dashboard/export/reports-custom-csv")
            public ResponseEntity<byte[]> exportReportsCustomCsv(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                     @RequestParam(required = false) String fields,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) Long periodId,
                                     @RequestParam(required = false) String city) {
            byte[] file = platformService.exportReportsCustomCsv(
                token,
                platformService.listReports(token, status, periodId, city),
                parseFields(fields)
            );
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=reports-custom.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file);
            }

            @GetMapping("/dashboard/export/enterprises-custom-csv")
            public ResponseEntity<byte[]> exportEnterprisesCustomCsv(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                         @RequestParam(required = false) String fields,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String city,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String nature,
                                         @RequestParam(required = false) String industry,
                                         @RequestParam(required = false) String orgCode) {
            byte[] file = platformService.exportEnterprisesCustomCsv(
                token,
                platformService.listEnterprises(token, status, city, keyword, nature, industry, orgCode),
                parseFields(fields)
            );
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=enterprises-custom.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file);
            }

        @GetMapping("/dashboard/export/summary-csv")
        public ResponseEntity<byte[]> exportSummaryCsv(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                               @RequestParam(required = false) Long periodId) {
        byte[] file = platformService.exportSummaryCsv(token, periodId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=summary.csv")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(file);
        }

    @GetMapping("/health")
    public ApiResponse<?> health() {
        return ApiResponse.ok(Map.of("status", "UP", "time", LocalDate.now().toString()));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private java.util.List<String> parseFields(String fields) {
        if (fields == null || fields.isBlank()) {
            return java.util.List.of();
        }
        return Arrays.stream(fields.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
