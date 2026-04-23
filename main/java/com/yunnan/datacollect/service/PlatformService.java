package com.yunnan.datacollect.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.yunnan.datacollect.repository.AuditLogRepository;
import com.yunnan.datacollect.repository.EnterpriseProfileRepository;
import com.yunnan.datacollect.repository.MonthlyReportRepository;
import com.yunnan.datacollect.repository.NoticeRecordRepository;
import com.yunnan.datacollect.repository.SurveyPeriodRepository;
import com.yunnan.datacollect.repository.SystemSettingRepository;
import com.yunnan.datacollect.repository.UserAccountRepository;
import com.yunnan.datacollect.web.SystemEventBroadcaster;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Service
public class PlatformService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicLong idSequence = new AtomicLong(1000);
    private final Map<Long, UserAccount> usersById = new ConcurrentHashMap<>();
    private final Map<String, UserAccount> usersByUsername = new ConcurrentHashMap<>();
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<Long, EnterpriseProfile> enterprises = new ConcurrentHashMap<>();
    private final Map<Long, MonthlyReport> reports = new ConcurrentHashMap<>();
    private final Map<Long, NoticeRecord> notices = new ConcurrentHashMap<>();
    private final Map<Long, SurveyPeriod> periods = new ConcurrentHashMap<>();
    private final Map<String, SystemSetting> systemSettings = new ConcurrentHashMap<>();
    private final List<AuditLog> auditLogs = new CopyOnWriteArrayList<>();

    private volatile Duration sessionTtl = Duration.ofMinutes(30);
    private volatile Duration smsTtl = Duration.ofMinutes(3);
    private volatile int loginFailureThreshold = 3;

    private final UserAccountRepository userAccountRepository;
    private final EnterpriseProfileRepository enterpriseProfileRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final NoticeRecordRepository noticeRecordRepository;
    private final SurveyPeriodRepository surveyPeriodRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final AuditLogRepository auditLogRepository;
    private final SystemEventBroadcaster systemEventBroadcaster;

    public PlatformService(UserAccountRepository userAccountRepository,
                           EnterpriseProfileRepository enterpriseProfileRepository,
                           MonthlyReportRepository monthlyReportRepository,
                           NoticeRecordRepository noticeRecordRepository,
                           SurveyPeriodRepository surveyPeriodRepository,
                           SystemSettingRepository systemSettingRepository,
                           AuditLogRepository auditLogRepository,
                           SystemEventBroadcaster systemEventBroadcaster) {
        this.userAccountRepository = userAccountRepository;
        this.enterpriseProfileRepository = enterpriseProfileRepository;
        this.monthlyReportRepository = monthlyReportRepository;
        this.noticeRecordRepository = noticeRecordRepository;
        this.surveyPeriodRepository = surveyPeriodRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.auditLogRepository = auditLogRepository;
        this.systemEventBroadcaster = systemEventBroadcaster;
    }

    @PostConstruct
    public void init() {
        if (userAccountRepository.count() > 0) {
            loadStateFromDb();
            rebuildIndexes();
            refreshIdSequence();
            loadSystemSettings();
            return;
        }
        seedPeriods();
        seedAccounts();
        seedEnterprises();
        seedReports();
        seedNotices();
        seedSystemSettings();
        persistState();
    }

    private void loadStateFromDb() {
        usersById.clear();
        usersByUsername.clear();
        enterprises.clear();
        reports.clear();
        notices.clear();
        periods.clear();
        auditLogs.clear();

        userAccountRepository.findAll().forEach(user -> usersById.put(user.id, user));
        enterpriseProfileRepository.findAll().forEach(profile -> enterprises.put(profile.id, profile));
        monthlyReportRepository.findAll().forEach(report -> reports.put(report.id, report));
        noticeRecordRepository.findAll().forEach(notice -> notices.put(notice.id, notice));
        surveyPeriodRepository.findAll().forEach(period -> periods.put(period.id, period));
        auditLogRepository.findAll().forEach(audit -> auditLogs.add(audit));
    }

    private void loadSystemSettings() {
        systemSettings.clear();
        if (systemSettingRepository.count() == 0) {
            seedSystemSettings();
            persistState();
            return;
        }
        systemSettingRepository.findAll().forEach(setting -> systemSettings.put(setting.settingKey, setting));
        applySystemSettings();
    }

    private void rebuildIndexes() {
        usersByUsername.clear();
        for (UserAccount user : usersById.values()) {
            if (user.normalizedUsername == null && user.username != null) {
                user.normalizedUsername = user.username.toLowerCase(Locale.ROOT);
            }
            usersByUsername.put(user.normalizedUsername, user);
        }
    }

    private void refreshIdSequence() {
        long maxId = 1000L;
        maxId = Math.max(maxId, usersById.keySet().stream().mapToLong(Long::longValue).max().orElse(1000L));
        maxId = Math.max(maxId, enterprises.keySet().stream().mapToLong(Long::longValue).max().orElse(1000L));
        maxId = Math.max(maxId, reports.keySet().stream().mapToLong(Long::longValue).max().orElse(1000L));
        maxId = Math.max(maxId, notices.keySet().stream().mapToLong(Long::longValue).max().orElse(1000L));
        maxId = Math.max(maxId, periods.keySet().stream().mapToLong(Long::longValue).max().orElse(1000L));
        maxId = Math.max(maxId, auditLogs.stream().mapToLong(log -> log.id).max().orElse(1000L));
        idSequence.set(maxId);
    }

    private void persistState() {
        userAccountRepository.saveAll(new ArrayList<>(usersById.values()));
        enterpriseProfileRepository.saveAll(new ArrayList<>(enterprises.values()));
        monthlyReportRepository.saveAll(new ArrayList<>(reports.values()));
        noticeRecordRepository.saveAll(new ArrayList<>(notices.values()));
        surveyPeriodRepository.saveAll(new ArrayList<>(periods.values()));
        systemSettingRepository.saveAll(new ArrayList<>(systemSettings.values()));
    }

    private void emit(String type, Object payload) {
        systemEventBroadcaster.broadcast(type, payload, SystemEventBroadcaster.EventAudience.provinceOnly());
    }

    private void emit(String type, Object payload, SystemEventBroadcaster.EventAudience audience) {
        systemEventBroadcaster.broadcast(type, payload, audience);
    }

    private SystemEventBroadcaster.EventAudience reportAudience(MonthlyReport report) {
        return SystemEventBroadcaster.EventAudience.cityAndEnterprise(report.cityName, report.enterpriseId);
    }

    private SystemEventBroadcaster.EventAudience enterpriseAudience(EnterpriseProfile profile) {
        return SystemEventBroadcaster.EventAudience.cityAndEnterprise(profile.cityName, profile.id);
    }

    private SystemEventBroadcaster.EventAudience noticeAudience(NoticeRecord notice) {
        if (notice.appliesToAll) {
            return SystemEventBroadcaster.EventAudience.all();
        }
        Set<String> cities = notice.targetCities == null
                ? Set.of()
                : notice.targetCities.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return SystemEventBroadcaster.EventAudience.cities(cities);
    }

    public SystemEventBroadcaster.ClientContext resolveWsClientContext(String token) {
        UserAccount account = requireUser(token);
        Long enterpriseId = null;
        if (account.role == Role.ENTERPRISE) {
            EnterpriseProfile profile = findEnterpriseForActor(account);
            enterpriseId = profile == null ? null : profile.id;
        }
        return new SystemEventBroadcaster.ClientContext(account.id, account.role.name(), account.cityName, enterpriseId);
    }

    public LoginResponse login(LoginRequest request, String clientIp) {
        UserAccount account = usersByUsername.get(request.username().trim().toLowerCase(Locale.ROOT));
        if (account == null) {
            return LoginResponse.failure("账号不存在");
        }
        if (account.lockedUntil != null && account.lockedUntil.isAfter(Instant.now())) {
            return LoginResponse.locked("账号已临时锁定，请稍后再试", account.lockedUntil);
        }
        if (request.role() != null && account.role != request.role()) {
            return LoginResponse.failure("角色不匹配");
        }
        if (account.smsChallengeCode != null && account.smsChallengeExpiresAt != null && account.smsChallengeExpiresAt.isAfter(Instant.now())) {
            if (request.smsCode() == null || !account.smsChallengeCode.equals(request.smsCode().trim())) {
                return LoginResponse.smsChallenge(maskPhone(account.phone), "连续3次登录失败，需要短信验证码", account.smsChallengeCode);
            }
        }
        if (!hashPassword(request.password(), account.salt).equals(account.passwordHash)) {
            account.failedLoginCount++;
            account.lastFailedLoginAt = Instant.now();
            if (account.failedLoginCount >= loginFailureThreshold) {
                account.smsChallengeCode = randomDigits(6);
                account.smsChallengeExpiresAt = Instant.now().plus(smsTtl);
                account.lockedUntil = Instant.now().plus(Duration.ofMinutes(5));
                account.failedLoginCount = 0;
                userAccountRepository.save(account);
                log("LOGIN_LOCK", "USER", account.id, "连续登录失败后触发短信校验", account.id, clientIp);
                return LoginResponse.smsChallenge(maskPhone(account.phone), "连续3次登录失败，需要短信验证码", account.smsChallengeCode);
            }
            userAccountRepository.save(account);
            log("LOGIN_FAIL", "USER", account.id, "密码错误", account.id, clientIp);
            return LoginResponse.failure("密码错误");
        }
        account.failedLoginCount = 0;
        account.smsChallengeCode = null;
        account.smsChallengeExpiresAt = null;
        account.lockedUntil = null;
        SessionInfo session = new SessionInfo();
        session.token = UUID.randomUUID().toString().replace("-", "");
        session.userId = account.id;
        session.role = account.role;
        session.clientIp = clientIp;
        session.createdAt = Instant.now();
        session.expiresAt = Instant.now().plus(sessionTtl);
        account.lastKnownIp = clientIp;
        userAccountRepository.save(account);
        sessions.put(session.token, session);
        log("LOGIN", "USER", account.id, "登录成功", account.id, clientIp);
        emit("LOGIN", Map.of("userId", account.id, "username", account.username, "role", account.role.name()));
        return LoginResponse.success(buildUserView(account), session.token);
    }

    public UserView currentUser(String token) {
        UserAccount account = requireUser(token);
        return buildUserView(account);
    }

    public void logout(String token) {
        SessionInfo session = sessions.remove(token);
        if (session != null) {
            log("LOGOUT", "USER", session.userId, "退出登录", session.userId, session.clientIp);
            emit("LOGOUT", Map.of("userId", session.userId));
        }
    }

    public void changePassword(String token, ChangePasswordRequest request) {
        UserAccount account = requireUser(token);
        String oldPassword = requireText(request.oldPassword(), "原密码不能为空");
        String newPassword = requireText(request.newPassword(), "新密码不能为空");
        String confirmPassword = requireText(request.confirmPassword(), "确认新密码不能为空");

        if (!hashPassword(oldPassword, account.salt).equals(account.passwordHash)) {
            throw badRequest("原密码输入错误");
        }
        if (!Objects.equals(newPassword, confirmPassword)) {
            throw badRequest("新密码与确认密码不一致");
        }
        if (Objects.equals(oldPassword, newPassword)) {
            throw badRequest("新密码不能与原密码相同");
        }
        validatePasswordRule(newPassword);

        account.passwordHash = hashPassword(newPassword, account.salt);
        account.failedLoginCount = 0;
        account.lockedUntil = null;
        account.smsChallengeCode = null;
        account.smsChallengeExpiresAt = null;
        userAccountRepository.save(account);

        sessions.entrySet().removeIf(entry -> entry.getValue().userId == account.id);
        log("PASSWORD_CHANGE", "USER", account.id, "用户修改密码", account.id, currentIpFromToken(token));
        emit("PASSWORD_CHANGE", Map.of("userId", account.id, "username", account.username));
    }

    public LoginResponse resetPassword(PasswordResetRequest request, String clientIp) {
        String username = requireText(request.username(), "账号不能为空").toLowerCase(Locale.ROOT);
        UserAccount account = usersByUsername.get(username);
        if (account == null) {
            throw badRequest("账号不存在");
        }
        String phone = requireText(request.phone(), "手机号不能为空");
        if (!phone.equals(account.phone)) {
            throw badRequest("手机号与账号不匹配");
        }
        String newPassword = requireText(request.newPassword(), "新密码不能为空");
        String confirmPassword = requireText(request.confirmPassword(), "确认新密码不能为空");
        if (!Objects.equals(newPassword, confirmPassword)) {
            throw badRequest("新密码与确认密码不一致");
        }
        validatePasswordRule(newPassword);
        account.passwordHash = hashPassword(newPassword, account.salt);
        account.failedLoginCount = 0;
        account.lockedUntil = null;
        account.smsChallengeCode = null;
        account.smsChallengeExpiresAt = null;
        userAccountRepository.save(account);
        sessions.entrySet().removeIf(entry -> entry.getValue().userId == account.id);
        log("PASSWORD_RESET", "USER", account.id, "找回密码并重置", account.id, clientIp == null ? "127.0.0.1" : clientIp);
        emit("PASSWORD_RESET", Map.of("userId", account.id, "username", account.username));
        return LoginResponse.success(buildUserView(account), null);
    }

    public EnterpriseView saveEnterpriseProfile(String token, EnterpriseRequest request, boolean submit) {
        UserAccount account = requireUser(token);
        EnterpriseProfile profile = findEnterpriseForActor(account);
        if (profile == null) {
            if (account.role != Role.ENTERPRISE && account.role != Role.PROVINCE) {
                throw forbidden("无权操作企业备案");
            }
            profile = new EnterpriseProfile();
            profile.id = nextId();
            profile.enterpriseUserId = account.role == Role.ENTERPRISE ? account.id : null;
            enterprises.put(profile.id, profile);
        }
        if (account.role == Role.ENTERPRISE && profile.enterpriseUserId != null && !Objects.equals(profile.enterpriseUserId, account.id)) {
            throw forbidden("无权操作该企业备案");
        }
        profile.regionProvince = nvl(request.regionProvince(), profile.regionProvince, "云南省");
        profile.cityName = requireText(request.cityName(), "所属地市不能为空");
        profile.countyName = nvl(request.countyName(), profile.countyName, "");
        profile.orgCode = requireText(request.orgCode(), "组织机构代码不能为空");
        profile.enterpriseName = requireText(request.enterpriseName(), "企业名称不能为空");
        profile.enterpriseNature = requireText(request.enterpriseNature(), "企业性质不能为空");
        profile.industry = requireText(request.industry(), "所属行业不能为空");
        profile.contactName = requireText(request.contactName(), "联系人不能为空");
        profile.contactPhone = requireText(request.contactPhone(), "联系电话不能为空");
        profile.address = nvl(request.address(), profile.address, "");
        if (submit) {
            profile.status = EnterpriseStatus.PENDING_PROVINCE_REVIEW;
            profile.reviewReason = null;
            profile.submittedAt = Instant.now();
            log("ENTERPRISE_SUBMIT", "ENTERPRISE", profile.id, "企业备案提交审核", account.id, currentIp(account));
        } else if (profile.status == null) {
            profile.status = EnterpriseStatus.DRAFT;
        }
        profile.updatedAt = Instant.now();
        if (profile.enterpriseUserId == null && account.role == Role.ENTERPRISE) {
            profile.enterpriseUserId = account.id;
        }
        persistState();
        emit("ENTERPRISE_SAVE", Map.of("enterpriseId", profile.id, "enterpriseName", profile.enterpriseName, "submitted", submit), enterpriseAudience(profile));
        return EnterpriseView.from(profile, getPeriodName(profile));
    }

    public EnterpriseView reviewEnterprise(String token, long enterpriseId, boolean approved, String reason) {
        UserAccount account = requireRole(token, Role.PROVINCE);
        EnterpriseProfile profile = requireEnterprise(enterpriseId);
        profile.status = approved ? EnterpriseStatus.APPROVED : EnterpriseStatus.REJECTED;
        profile.reviewReason = approved ? null : requireText(reason, "退回理由不能为空");
        profile.reviewedBy = account.id;
        profile.reviewedAt = Instant.now();
        log(approved ? "ENTERPRISE_APPROVE" : "ENTERPRISE_REJECT", "ENTERPRISE", enterpriseId,
                approved ? "备案审核通过" : "备案审核退回", account.id, currentIp(account));
        persistState();
        emit("ENTERPRISE_REVIEW", Map.of("enterpriseId", enterpriseId, "approved", approved), enterpriseAudience(profile));
        return EnterpriseView.from(profile, getPeriodName(profile));
    }

    public List<EnterpriseView> listEnterprises(String token, String status, String city, String keyword, String nature, String industry, String orgCode) {
        UserAccount account = requireUser(token);
        return enterprises.values().stream()
                .filter(profile -> canSeeEnterprise(account, profile))
                .filter(profile -> status == null || status.isBlank() || profile.status.name().equalsIgnoreCase(status))
                .filter(profile -> city == null || city.isBlank() || containsIgnoreCase(profile.cityName, city))
                .filter(profile -> keyword == null || keyword.isBlank() || containsIgnoreCase(profile.enterpriseName, keyword) || containsIgnoreCase(profile.orgCode, keyword))
                .filter(profile -> nature == null || nature.isBlank() || containsIgnoreCase(profile.enterpriseNature, nature))
                .filter(profile -> industry == null || industry.isBlank() || containsIgnoreCase(profile.industry, industry))
                .filter(profile -> orgCode == null || orgCode.isBlank() || containsIgnoreCase(profile.orgCode, orgCode))
                .sorted(Comparator.comparing((EnterpriseProfile p) -> p.updatedAt == null ? Instant.EPOCH : p.updatedAt).reversed())
                .map(profile -> EnterpriseView.from(profile, getPeriodName(profile)))
                .collect(Collectors.toList());
    }

    public PagedResult<EnterpriseView> listEnterprisesPage(String token, String status, String city, String keyword,
                                                           String nature, String industry, String orgCode,
                                                           Integer page, Integer size) {
        return paginate(listEnterprises(token, status, city, keyword, nature, industry, orgCode), page, size);
    }

    public MonthlyReportView saveReport(String token, MonthlyReportRequest request, boolean submit) {
        UserAccount account = requireRole(token, Role.ENTERPRISE, Role.PROVINCE);
        EnterpriseProfile profile = findEnterpriseForActor(account);
        if (profile == null || profile.status != EnterpriseStatus.APPROVED) {
            throw forbidden("备案通过后才能填报数据");
        }
        SurveyPeriod period = requirePeriod(request.periodId());
        if (!isWithinReportWindow(period)) {
            throw badRequest("当前调查期不在允许上报时间内");
        }
        MonthlyReport report = request.reportId() == null ? null : reports.get(request.reportId());
        if (report == null) {
            report = new MonthlyReport();
            report.id = nextId();
            report.enterpriseId = profile.id;
            report.enterpriseName = profile.enterpriseName;
            report.cityName = profile.cityName;
            report.periodId = period.id;
            report.periodName = period.name;
            report.createdAt = Instant.now();
            reports.put(report.id, report);
        }
        ensureReportOwner(account, report);
        validateReportRequest(request);
        report.archivedJobs = request.archivedJobs();
        report.surveyJobs = request.surveyJobs();
        report.otherReason = requireText(request.otherReason(), "其他原因不能为空");
        report.decreaseType = request.surveyJobs() < request.archivedJobs() ? requireText(request.decreaseType(), "就业人数减少类型不能为空") : request.decreaseType();
        report.mainReason = request.surveyJobs() < request.archivedJobs() ? requireText(request.mainReason(), "主要原因不能为空") : request.mainReason();
        report.mainReasonDescription = request.surveyJobs() < request.archivedJobs() ? requireText(request.mainReasonDescription(), "主要原因说明不能为空") : request.mainReasonDescription();
        report.secondaryReason = request.secondaryReason();
        report.secondaryReasonDescription = request.secondaryReasonDescription();
        report.thirdReason = request.thirdReason();
        report.thirdReasonDescription = request.thirdReasonDescription();
        report.updatedAt = Instant.now();
        if (submit) {
            report.status = ReportStatus.PENDING_CITY_REVIEW;
            report.submittedAt = Instant.now();
            log("REPORT_SUBMIT", "REPORT", report.id, "企业填报上报市级审核", account.id, currentIp(account));
        } else if (report.status == null) {
            report.status = ReportStatus.DRAFT;
        }
        persistState();
        emit("REPORT_SAVE", Map.of("reportId", report.id, "periodId", period.id, "submit", submit), reportAudience(report));
        return MonthlyReportView.from(report);
    }

    public MonthlyReportView submitReport(String token, long reportId) {
        UserAccount account = requireRole(token, Role.ENTERPRISE, Role.PROVINCE);
        MonthlyReport report = requireReport(reportId);
        ensureReportOwner(account, report);
        report.status = ReportStatus.PENDING_CITY_REVIEW;
        report.submittedAt = Instant.now();
        report.updatedAt = Instant.now();
        log("REPORT_SUBMIT", "REPORT", report.id, "数据正式提交", account.id, currentIp(account));
        persistState();
        Map<String, Object> payload = new HashMap<>();
        payload.put("reportId", report.id);
        payload.put("periodId", report.periodId);
        payload.put("submit", true);
        emit("REPORT_SAVE", payload, reportAudience(report));
        return MonthlyReportView.from(report);
    }

    public List<MonthlyReportView> listReports(String token, String state, Long periodId, String cityName) {
        UserAccount account = requireUser(token);
        return reports.values().stream()
                .filter(report -> canSeeReport(account, report))
                .filter(report -> state == null || state.isBlank() || report.status.name().equalsIgnoreCase(state))
                .filter(report -> periodId == null || Objects.equals(report.periodId, periodId))
                .filter(report -> cityName == null || cityName.isBlank() || containsIgnoreCase(report.cityName, cityName))
                .sorted(Comparator.comparing((MonthlyReport r) -> r.updatedAt == null ? Instant.EPOCH : r.updatedAt).reversed())
                .map(MonthlyReportView::from)
                .collect(Collectors.toList());
    }

    public PagedResult<MonthlyReportView> listReportsPage(String token, String state, Long periodId, String cityName,
                                                          Integer page, Integer size) {
        return paginate(listReports(token, state, periodId, cityName), page, size);
    }

    public MonthlyReportView reviewCityReport(String token, long reportId, boolean approved, String reason) {
        UserAccount account = requireRole(token, Role.CITY);
        MonthlyReport report = requireReport(reportId);
        if (!isReportInCity(account, report)) {
            throw forbidden("无权审核该市数据");
        }
        if (report.status != ReportStatus.PENDING_CITY_REVIEW && report.status != ReportStatus.CITY_REJECTED) {
            throw badRequest("该数据已被处理，请刷新后重试");
        }
        report.cityReviewReason = approved ? null : requireText(reason, "退回理由不能为空");
        report.cityReviewedBy = account.id;
        report.cityReviewedAt = Instant.now();
        if (approved) {
            report.status = ReportStatus.PENDING_PROVINCE_REVIEW;
            log("CITY_REPORT_APPROVE", "REPORT", report.id, "市级审核通过", account.id, currentIp(account));
        } else {
            report.status = ReportStatus.CITY_REJECTED;
            log("CITY_REPORT_REJECT", "REPORT", report.id, "市级审核退回", account.id, currentIp(account));
        }
        report.updatedAt = Instant.now();
        persistState();
        emit("CITY_REVIEW", Map.of("reportId", report.id, "approved", approved), reportAudience(report));
        return MonthlyReportView.from(report);
    }

    public BatchReviewResult reviewCityReportBatch(String token, BatchCityReviewRequest request) {
        requireRole(token, Role.CITY);
        if (request == null || request.reportIds() == null || request.reportIds().isEmpty()) {
            throw badRequest("请至少选择1条待审核数据");
        }
        List<Long> successIds = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Long reportId : request.reportIds()) {
            if (reportId == null) {
                continue;
            }
            try {
                reviewCityReport(token, reportId, request.approved(), request.reason());
                successIds.add(reportId);
            } catch (RuntimeException ex) {
                failed.add(reportId + ":" + ex.getMessage());
            }
        }
        emit("CITY_REVIEW_BATCH", Map.of("successIds", successIds, "failedMessages", failed));
        return new BatchReviewResult(successIds, failed);
    }

    public MonthlyReportView reviewProvinceReport(String token, long reportId, boolean approved, String reason) {
        UserAccount account = requireRole(token, Role.PROVINCE);
        MonthlyReport report = requireReport(reportId);
        if (report.status != ReportStatus.PENDING_PROVINCE_REVIEW) {
            throw badRequest("该数据已被处理，请刷新后重试");
        }
        report.provinceReviewReason = approved ? null : requireText(reason, "退回理由不能为空");
        report.provinceReviewedBy = account.id;
        report.provinceReviewedAt = Instant.now();
        if (approved) {
            report.status = ReportStatus.PROVINCE_APPROVED;
            log("PROVINCE_REPORT_APPROVE", "REPORT", report.id, "省级审核通过", account.id, currentIp(account));
        } else {
            report.status = ReportStatus.PROVINCE_REJECTED;
            log("PROVINCE_REPORT_REJECT", "REPORT", report.id, "省级审核退回", account.id, currentIp(account));
        }
        report.updatedAt = Instant.now();
        persistState();
        emit("PROVINCE_REVIEW", Map.of("reportId", report.id, "approved", approved), reportAudience(report));
        return MonthlyReportView.from(report);
    }

    public BatchReviewResult reviewProvinceReportBatch(String token, BatchProvinceReviewRequest request) {
        requireRole(token, Role.PROVINCE);
        if (request == null || request.reportIds() == null || request.reportIds().isEmpty()) {
            throw badRequest("请至少选择1条待审核数据");
        }
        List<Long> successIds = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Long reportId : request.reportIds()) {
            if (reportId == null) {
                continue;
            }
            try {
                reviewProvinceReport(token, reportId, request.approved(), request.reason());
                successIds.add(reportId);
            } catch (RuntimeException ex) {
                failed.add(reportId + ":" + ex.getMessage());
            }
        }
        emit("PROVINCE_REVIEW_BATCH", Map.of("successIds", successIds, "failedMessages", failed));
        return new BatchReviewResult(successIds, failed);
    }

    public MonthlyReportView provinceCorrectReport(String token, long reportId, MonthlyReportRequest request) {
        UserAccount account = requireRole(token, Role.PROVINCE);
        MonthlyReport report = requireReport(reportId);
        validateReportRequest(request);
        report.provinceAdjustment = new ProvinceAdjustment();
        report.provinceAdjustment.adjustedBy = account.id;
        report.provinceAdjustment.adjustedAt = Instant.now();
        report.provinceAdjustment.adjustReason = requireText(request.adjustReason(), "修改原因不能为空");
        report.provinceAdjustment.archivedJobs = request.archivedJobs();
        report.provinceAdjustment.surveyJobs = request.surveyJobs();
        report.provinceAdjustment.otherReason = requireText(request.otherReason(), "其他原因不能为空");
        report.provinceAdjustment.decreaseType = request.decreaseType();
        report.provinceAdjustment.mainReason = request.mainReason();
        report.provinceAdjustment.mainReasonDescription = request.mainReasonDescription();
        report.provinceAdjustment.secondaryReason = request.secondaryReason();
        report.provinceAdjustment.secondaryReasonDescription = request.secondaryReasonDescription();
        report.provinceAdjustment.thirdReason = request.thirdReason();
        report.provinceAdjustment.thirdReasonDescription = request.thirdReasonDescription();
        report.status = ReportStatus.PROVINCE_APPROVED;
        report.updatedAt = Instant.now();
        log("PROVINCE_REPORT_CORRECT", "REPORT", report.id, "省级代填/修改数据", account.id, currentIp(account));
        persistState();
        emit("PROVINCE_CORRECT", Map.of("reportId", report.id), reportAudience(report));
        return MonthlyReportView.from(report);
    }

    public List<NoticeView> listNotices(String token, String status, String keyword, String cityName, String createdFrom, String createdTo) {
        UserAccount account = requireUser(token);
        LocalDate from = parseOptionalDate(createdFrom);
        LocalDate to = parseOptionalDate(createdTo);
        return notices.values().stream()
                .filter(notice -> canSeeNotice(account, notice))
                .filter(notice -> status == null || status.isBlank() || notice.status.name().equalsIgnoreCase(status))
                .filter(notice -> keyword == null || keyword.isBlank() || containsIgnoreCase(notice.title, keyword))
                .filter(notice -> cityName == null || cityName.isBlank() || notice.appliesToAll || notice.targetCities.stream().anyMatch(c -> containsIgnoreCase(c, cityName)))
                .filter(notice -> {
                    if (notice.createdAt == null) {
                        return from == null && to == null;
                    }
                    LocalDate created = notice.createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                    return (from == null || !created.isBefore(from)) && (to == null || !created.isAfter(to));
                })
                .sorted(Comparator.comparing((NoticeRecord n) -> n.updatedAt == null ? Instant.EPOCH : n.updatedAt).reversed())
                .map(NoticeView::from)
                .collect(Collectors.toList());
    }

    public PagedResult<NoticeView> listNoticesPage(String token, String status, String keyword, String cityName,
                                                   String createdFrom, String createdTo, Integer page, Integer size) {
        return paginate(listNotices(token, status, keyword, cityName, createdFrom, createdTo), page, size);
    }

    public NoticeView saveNotice(String token, NoticeRequest request, boolean publishNow) {
        UserAccount account = requireRole(token, Role.PROVINCE, Role.CITY);
        if (account.role == Role.CITY && request.appliesToAll()) {
            throw forbidden("市级用户不能发布全省通知");
        }
        NoticeRecord notice = request.noticeId() == null ? new NoticeRecord() : notices.get(request.noticeId());
        if (notice == null) {
            notice = new NoticeRecord();
            notice.id = nextId();
            notices.put(notice.id, notice);
        }
        notice.title = requireText(request.title(), "通知标题不能为空");
        notice.content = requireText(request.content(), "通知内容不能为空");
        if (notice.title.length() > 50) {
            throw badRequest("通知标题不能超过50字");
        }
        if (notice.content.length() > 2000) {
            throw badRequest("通知内容不能超过2000字");
        }
        notice.appliesToAll = request.appliesToAll();
        notice.targetCities = request.targetCities() == null ? new ArrayList<>() : new ArrayList<>(request.targetCities());
        notice.publisherId = account.id;
        notice.publisherRole = account.role;
        notice.publisherName = account.username;
        notice.status = publishNow ? NoticeStatus.ACTIVE : notice.status == null ? NoticeStatus.DRAFT : notice.status;
        notice.createdAt = notice.createdAt == null ? Instant.now() : notice.createdAt;
        notice.updatedAt = Instant.now();
        log("NOTICE_SAVE", "NOTICE", notice.id, "发布或修改通知", account.id, currentIp(account));
        persistState();
        emit("NOTICE_SAVE", Map.of("noticeId", notice.id, "title", notice.title, "publishNow", publishNow), noticeAudience(notice));
        return NoticeView.from(notice);
    }

    public NoticeView deleteNotice(String token, long noticeId) {
        UserAccount account = requireUser(token);
        NoticeRecord notice = requireNotice(noticeId);
        if (account.role == Role.CITY && notice.publisherRole != Role.CITY) {
            throw forbidden("无权操作该通知");
        }
        if (account.role == Role.ENTERPRISE) {
            throw forbidden("无权操作该通知");
        }
        if (account.role == Role.PROVINCE || notice.publisherId == account.id) {
            notice.status = NoticeStatus.DELETED;
            notice.deletedAt = Instant.now();
            notice.updatedAt = Instant.now();
            log("NOTICE_DELETE", "NOTICE", notice.id, "删除通知", account.id, currentIp(account));
            persistState();
                    emit("NOTICE_DELETE", Map.of("noticeId", notice.id), noticeAudience(notice));
            return NoticeView.from(notice);
        }
        throw forbidden("无权操作该通知");
    }

    public SummaryView summary(String token, Long periodId) {
        requireRole(token, Role.PROVINCE, Role.CITY, Role.ENTERPRISE);
        SurveyPeriod period = periodId == null ? periods.values().stream().max(Comparator.comparing(p -> p.startDate)).orElseThrow(() -> badRequest("无可用调查期")) : requirePeriod(periodId);
        List<MonthlyReport> scope = effectiveReportsForPeriod(period.id);
        long enterpriseCount = scope.stream().map(MonthlyReport::effectiveEnterpriseKey).distinct().count();
        long archivedTotal = scope.stream().mapToLong(r -> r.effectiveData().archivedJobs).sum();
        long surveyTotal = scope.stream().mapToLong(r -> r.effectiveData().surveyJobs).sum();
        long jobChangeTotal = scope.stream().mapToLong(r -> r.effectiveData().surveyJobs - r.effectiveData().archivedJobs).sum();
        long decreasedTotal = scope.stream().filter(r -> r.effectiveData().surveyJobs < r.effectiveData().archivedJobs).count();
        double ratio = archivedTotal == 0 ? 0.0 : Math.max(0.0, (archivedTotal - surveyTotal) * 100.0 / archivedTotal);
        Map<String, Long> cityCounts = scope.stream().collect(Collectors.groupingBy(r -> r.cityName, LinkedHashMap::new, Collectors.counting()));
        return new SummaryView(period.id, period.name, enterpriseCount, archivedTotal, surveyTotal, jobChangeTotal, decreasedTotal, ratio, cityCounts);
    }

    public SamplingView sampling(String token, String cityFilter) {
        requireRole(token, Role.PROVINCE);
        Map<String, Long> counts = enterprises.values().stream()
                .filter(profile -> profile.status == EnterpriseStatus.APPROVED)
                .filter(profile -> cityFilter == null || cityFilter.isBlank() || containsIgnoreCase(profile.cityName, cityFilter))
                .collect(Collectors.groupingBy(profile -> profile.cityName, LinkedHashMap::new, Collectors.counting()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<SamplingRow> rows = counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new SamplingRow(entry.getKey(), entry.getValue(), total == 0 ? 0.0 : entry.getValue() * 100.0 / total))
                .toList();
        return new SamplingView(total, rows);
    }

    public ComparisonView comparison(String token, ComparisonRequest request) {
        requireRole(token, Role.PROVINCE);
        SurveyPeriod left = requirePeriod(request.leftPeriodId());
        SurveyPeriod right = requirePeriod(request.rightPeriodId());
        List<MonthlyReport> leftReports = filterReports(request, left.id);
        List<MonthlyReport> rightReports = filterReports(request, right.id);
        List<String> dimensions = normalizeDimensions(request.dimensions());
        Map<String, ComparisonMetric> leftMetrics = aggregateComparison(leftReports, dimensions);
        Map<String, ComparisonMetric> rightMetrics = aggregateComparison(rightReports, dimensions);
        List<ComparisonRow> rows = new ArrayList<>();
        Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(leftMetrics.keySet());
        keys.addAll(rightMetrics.keySet());
        for (String key : keys) {
            ComparisonMetric lm = leftMetrics.getOrDefault(key, new ComparisonMetric());
            ComparisonMetric rm = rightMetrics.getOrDefault(key, new ComparisonMetric());
            rows.add(new ComparisonRow(key, lm.enterpriseCount, rm.enterpriseCount, lm.archivedJobs, rm.archivedJobs,
                    lm.surveyJobs, rm.surveyJobs, lm.changeJobs, rm.changeJobs));
        }
        return new ComparisonView(left.name, right.name, dimensions, rows);
    }

    public TrendView trend(String token, TrendRequest request) {
        requireRole(token, Role.PROVINCE);
        if (request.periodIds() == null || request.periodIds().size() < 3) {
            throw badRequest("趋势分析至少选择3个连续调查期");
        }
        List<SurveyPeriod> selected = request.periodIds().stream().map(this::requirePeriod).sorted(Comparator.comparing(p -> p.startDate)).toList();
        for (int i = 1; i < selected.size(); i++) {
            if (!selected.get(i - 1).isPreviousTo(selected.get(i))) {
                throw badRequest("仅可选择连续调查期");
            }
        }
        List<MonthlyReport> scope = filterByConditions(request.cityName(), request.industry(), request.enterpriseNature(), null);
        List<TrendRow> rows = new ArrayList<>();
        Double previousRatio = null;
        for (SurveyPeriod period : selected) {
            List<MonthlyReport> periodReports = scope.stream().filter(report -> Objects.equals(report.periodId, period.id)).toList();
            long archivedTotal = periodReports.stream().mapToLong(r -> r.effectiveData().archivedJobs).sum();
            long surveyTotal = periodReports.stream().mapToLong(r -> r.effectiveData().surveyJobs).sum();
            double ratio = archivedTotal == 0 ? 0.0 : Math.max(0.0, (archivedTotal - surveyTotal) * 100.0 / archivedTotal);
            Double ring = previousRatio == null ? null : (previousRatio == 0.0 ? null : (ratio - previousRatio) * 100.0 / previousRatio);
            rows.add(new TrendRow(period.id, period.name, ratio, ring));
            previousRatio = ratio;
        }
        return new TrendView(rows, request.note() == null ? new ArrayList<>() : new ArrayList<>(request.note()));
    }

    public MonitorView monitor() {
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long max = Runtime.getRuntime().maxMemory();
        return new MonitorView(Runtime.getRuntime().availableProcessors(), used, max, sessions.size(), auditLogs.size(), Duration.between(Instant.now().minusSeconds(ProcessHandle.current().info().totalCpuDuration().map(Duration::toSeconds).orElse(0L)), Instant.now()).toSeconds());
    }

    public List<SessionView> listSessions(String token) {
        requireRole(token, Role.PROVINCE);
        return sessions.values().stream()
                .sorted(Comparator.comparing((SessionInfo session) -> session.createdAt).reversed())
                .map(this::buildSessionView)
                .collect(Collectors.toList());
    }

    public void forceLogout(String token, ForceLogoutRequest request) {
        UserAccount operator = requireRole(token, Role.PROVINCE);
        String targetToken = requireText(request.sessionToken(), "会话令牌不能为空");
        SessionInfo removed = sessions.remove(targetToken);
        if (removed == null) {
            throw badRequest("会话不存在或已失效");
        }
        log("SESSION_FORCE_LOGOUT", "USER", removed.userId, "强制下线会话", operator.id, currentIp(operator));
        emit("SESSION_FORCE_LOGOUT", Map.of("userId", removed.userId, "sessionToken", targetToken));
    }

    public List<SystemSettingView> listSystemSettings(String token) {
        requireRole(token, Role.PROVINCE);
        return systemSettings.values().stream()
                .sorted(Comparator.comparing((SystemSetting setting) -> setting.settingKey))
                .map(SystemSettingView::from)
                .collect(Collectors.toList());
    }

    public List<SystemSettingView> saveSystemSettings(String token, SystemSettingsRequest request) {
        UserAccount operator = requireRole(token, Role.PROVINCE);
        if (request == null) {
            throw badRequest("系统参数不能为空");
        }
        upsertSetting("SESSION_TTL_MINUTES", String.valueOf(normalizeMinutes(request.sessionTtlMinutes(), 30)));
        upsertSetting("SMS_TTL_MINUTES", String.valueOf(normalizeMinutes(request.smsTtlMinutes(), 3)));
        upsertSetting("LOGIN_FAILURE_THRESHOLD", String.valueOf(normalizeThreshold(request.loginFailureThreshold(), 3)));
        if (request.systemNotice() != null) {
            upsertSetting("SYSTEM_NOTICE", request.systemNotice().trim());
        }
        applySystemSettings();
        persistState();
        log("SYSTEM_SETTINGS_UPDATE", "SYSTEM", 1L, "更新系统参数配置", operator.id, currentIp(operator));
        emit("SYSTEM_SETTINGS_UPDATE", Map.of("operatorId", operator.id));
        return listSystemSettings(token);
    }

    public List<AuditLogView> logs(String token, String targetType, Long targetId) {
        requireRole(token, Role.PROVINCE, Role.CITY, Role.ENTERPRISE);
        return filterAuditLogs(targetType, targetId, null, null, null, null).stream()
                .sorted(Comparator.comparing((AuditLog l) -> l.createdAt).reversed())
                .map(AuditLogView::from)
                .collect(Collectors.toList());
    }

        public PagedResult<AuditLogView> logsPage(String token, String targetType, Long targetId, String action,
                              String actorName, String createdFrom, String createdTo,
                              Integer page, Integer size) {
        requireRole(token, Role.PROVINCE, Role.CITY, Role.ENTERPRISE);
        List<AuditLogView> rows = filterAuditLogs(targetType, targetId, action, actorName, createdFrom, createdTo).stream()
            .sorted(Comparator.comparing((AuditLog l) -> l.createdAt).reversed())
            .map(AuditLogView::from)
            .collect(Collectors.toList());
        return paginate(rows, page, size);
        }

        public byte[] exportLogsCsv(String token, String targetType, Long targetId, String action,
                    String actorName, String createdFrom, String createdTo) {
        requireRole(token, Role.PROVINCE, Role.CITY, Role.ENTERPRISE);
        List<List<String>> data = new ArrayList<>();
        data.add(List.of("ID", "动作", "目标类型", "目标ID", "描述", "操作人", "IP", "时间"));
        filterAuditLogs(targetType, targetId, action, actorName, createdFrom, createdTo).stream()
            .sorted(Comparator.comparing((AuditLog l) -> l.createdAt).reversed())
            .forEach(log -> data.add(List.of(
                String.valueOf(log.id),
                String.valueOf(log.action),
                String.valueOf(log.targetType),
                String.valueOf(log.targetId),
                String.valueOf(log.description),
                String.valueOf(log.actorName),
                String.valueOf(log.clientIp),
                String.valueOf(log.createdAt)
            )));
        return exportCsv(data);
        }

    public byte[] exportNoticesExcel(String token, List<NoticeView> rows) {
        requireRole(token, Role.PROVINCE, Role.CITY);
        return exportWorkbook("通知列表", List.of("ID", "标题", "发布单位", "发布时间", "适用地区", "状态"), rows.stream().map(row -> List.of(String.valueOf(row.id()), String.valueOf(row.title()), String.valueOf(row.publisherName()), String.valueOf(row.createdAt()), String.valueOf(row.scopeText()), String.valueOf(row.status()))).toList());
    }

    public byte[] exportReportsExcel(String token, List<MonthlyReportView> rows) {
        requireRole(token, Role.PROVINCE, Role.CITY);
        return exportWorkbook("报表数据", List.of("ID", "企业名称", "调查期", "建档期就业人数", "调查期就业人数", "状态", "市级审核", "省级审核"), rows.stream().map(row -> List.of(String.valueOf(row.id()), String.valueOf(row.enterpriseName()), String.valueOf(row.periodName()), String.valueOf(row.archivedJobs()), String.valueOf(row.surveyJobs()), String.valueOf(row.status()), String.valueOf(row.cityReviewReason()), String.valueOf(row.provinceReviewReason()))).toList());
    }

    public byte[] exportEnterprisesExcel(String token, List<EnterpriseView> rows) {
        requireRole(token, Role.PROVINCE, Role.CITY);
        return exportWorkbook("企业备案", List.of("ID", "企业名称", "组织机构代码", "地市", "备案状态", "联系人", "联系电话"), rows.stream().map(row -> List.of(String.valueOf(row.id()), String.valueOf(row.enterpriseName()), String.valueOf(row.orgCode()), String.valueOf(row.cityName()), String.valueOf(row.status()), String.valueOf(row.contactName()), String.valueOf(row.contactPhone()))).toList());
    }

    public byte[] exportEnterprisesCsv(String token, List<EnterpriseView> rows) {
        requireRole(token, Role.PROVINCE, Role.CITY);
        List<List<String>> data = new ArrayList<>();
        data.add(List.of("ID", "企业名称", "组织机构代码", "地市", "备案状态", "联系人", "联系电话"));
        for (EnterpriseView row : rows) {
            data.add(List.of(String.valueOf(row.id()), String.valueOf(row.enterpriseName()), String.valueOf(row.orgCode()), String.valueOf(row.cityName()), String.valueOf(row.status()), String.valueOf(row.contactName()), String.valueOf(row.contactPhone())));
        }
        return exportCsv(data);
    }

    public byte[] exportReportsCsv(String token, List<MonthlyReportView> rows) {
        requireRole(token, Role.PROVINCE, Role.CITY);
        List<List<String>> data = new ArrayList<>();
        data.add(List.of("ID", "企业名称", "调查期", "建档期就业人数", "调查期就业人数", "状态", "市级审核", "省级审核"));
        for (MonthlyReportView row : rows) {
            data.add(List.of(String.valueOf(row.id()), String.valueOf(row.enterpriseName()), String.valueOf(row.periodName()), String.valueOf(row.archivedJobs()), String.valueOf(row.surveyJobs()), String.valueOf(row.status()), String.valueOf(row.cityReviewReason()), String.valueOf(row.provinceReviewReason())));
        }
        return exportCsv(data);
    }

    public byte[] exportReportsCustomCsv(String token, List<MonthlyReportView> rows, List<String> fields) {
        requireRole(token, Role.PROVINCE, Role.CITY);
        List<String> selected = normalizeReportFields(fields);
        List<List<String>> data = new ArrayList<>();
        data.add(selected.stream().map(this::reportFieldLabel).collect(Collectors.toList()));
        for (MonthlyReportView row : rows) {
            data.add(selected.stream().map(field -> reportFieldValue(row, field)).collect(Collectors.toList()));
        }
        return exportCsv(data);
    }

    public byte[] exportEnterprisesCustomCsv(String token, List<EnterpriseView> rows, List<String> fields) {
        requireRole(token, Role.PROVINCE, Role.CITY);
        List<String> selected = normalizeEnterpriseFields(fields);
        List<List<String>> data = new ArrayList<>();
        data.add(selected.stream().map(this::enterpriseFieldLabel).collect(Collectors.toList()));
        for (EnterpriseView row : rows) {
            data.add(selected.stream().map(field -> enterpriseFieldValue(row, field)).collect(Collectors.toList()));
        }
        return exportCsv(data);
    }

    public LoginResponse createUser(String token, UserCreateRequest request) {
        requireRole(token, Role.PROVINCE);
        if (usersByUsername.containsKey(request.username().trim().toLowerCase(Locale.ROOT))) {
            throw badRequest("账号已存在");
        }
        validatePasswordRule(request.password());
        UserAccount user = new UserAccount();
        user.id = nextId();
        user.username = request.username().trim();
        user.normalizedUsername = user.username.toLowerCase(Locale.ROOT);
        user.role = request.role();
        user.cityName = request.cityName();
        user.phone = request.phone();
        user.salt = UUID.randomUUID().toString().replace("-", "");
        user.passwordHash = hashPassword(request.password(), user.salt);
        user.enabled = true;
        usersById.put(user.id, user);
        usersByUsername.put(user.normalizedUsername, user);
        log("USER_CREATE", "USER", user.id, "创建用户账号", currentUser(token).id(), currentIpFromToken(token));
        persistState();
        return LoginResponse.success(buildUserView(user), null);
    }

    public PagedResult<UserManageView> listUsersPage(String token, String keyword, String role, String city,
                                                     Boolean enabled, Integer page, Integer size) {
        requireRole(token, Role.PROVINCE);
        List<UserManageView> rows = usersById.values().stream()
                .filter(user -> keyword == null || keyword.isBlank()
                        || containsIgnoreCase(user.username, keyword)
                        || containsIgnoreCase(user.phone, keyword)
                        || containsIgnoreCase(user.cityName, keyword))
                .filter(user -> role == null || role.isBlank() || user.role.name().equalsIgnoreCase(role))
                .filter(user -> city == null || city.isBlank() || containsIgnoreCase(user.cityName, city))
                .filter(user -> enabled == null || user.enabled == enabled)
                .sorted(Comparator.comparing((UserAccount user) -> user.id).reversed())
                .map(this::buildUserManageView)
                .collect(Collectors.toList());
        return paginate(rows, page, size);
    }

    public UserView updateUserRole(String token, UserRoleUpdateRequest request) {
        UserAccount operator = requireRole(token, Role.PROVINCE);
        if (request == null || request.userId() == null || request.role() == null) {
            throw badRequest("用户ID与目标角色不能为空");
        }
        UserAccount target = requireUserById(request.userId());
        if (target.id == operator.id) {
            throw badRequest("不允许修改当前登录账号的角色");
        }
        if (request.role() == Role.CITY && isBlank(request.cityName())) {
            throw badRequest("市级账号必须指定所属地市");
        }
        target.role = request.role();
        target.cityName = request.cityName() == null ? target.cityName : request.cityName().trim();
        if (target.role == Role.PROVINCE && isBlank(target.cityName)) {
            target.cityName = "昆明市";
        }
        if (target.role != Role.ENTERPRISE) {
            target.enterpriseId = null;
        }
        userAccountRepository.save(target);
        sessions.entrySet().removeIf(entry -> entry.getValue().userId == target.id);
        log("USER_ROLE_UPDATE", "USER", target.id,
                "修改用户角色为" + target.role.name(), operator.id, currentIp(operator));
        return buildUserView(target);
    }

    public UserView setUserEnabled(String token, UserEnableRequest request) {
        UserAccount operator = requireRole(token, Role.PROVINCE);
        if (request == null || request.userId() == null) {
            throw badRequest("用户ID不能为空");
        }
        UserAccount target = requireUserById(request.userId());
        if (target.id == operator.id && !request.enabled()) {
            throw badRequest("不允许禁用当前登录账号");
        }
        target.enabled = request.enabled();
        if (!target.enabled) {
            sessions.entrySet().removeIf(entry -> entry.getValue().userId == target.id);
        }
        userAccountRepository.save(target);
        log("USER_ENABLED_UPDATE", "USER", target.id,
                request.enabled() ? "启用用户账号" : "禁用用户账号", operator.id, currentIp(operator));
        return buildUserView(target);
    }

    public UserView unlockUser(String token, UserUnlockRequest request) {
        UserAccount operator = requireRole(token, Role.PROVINCE);
        if (request == null || request.userId() == null) {
            throw badRequest("用户ID不能为空");
        }
        UserAccount target = requireUserById(request.userId());
        target.failedLoginCount = 0;
        target.lockedUntil = null;
        target.smsChallengeCode = null;
        target.smsChallengeExpiresAt = null;
        userAccountRepository.save(target);
        log("USER_UNLOCK", "USER", target.id, "解锁用户账号", operator.id, currentIp(operator));
        return buildUserView(target);
    }

    public UserView adminResetUserPassword(String token, UserAdminResetPasswordRequest request) {
        UserAccount operator = requireRole(token, Role.PROVINCE);
        if (request == null || request.userId() == null) {
            throw badRequest("用户ID不能为空");
        }
        UserAccount target = requireUserById(request.userId());
        String newPassword = requireText(request.newPassword(), "新密码不能为空");
        String confirmPassword = requireText(request.confirmPassword(), "确认密码不能为空");
        if (!Objects.equals(newPassword, confirmPassword)) {
            throw badRequest("新密码与确认密码不一致");
        }
        validatePasswordRule(newPassword);
        target.passwordHash = hashPassword(newPassword, target.salt);
        target.failedLoginCount = 0;
        target.lockedUntil = null;
        target.smsChallengeCode = null;
        target.smsChallengeExpiresAt = null;
        userAccountRepository.save(target);
        sessions.entrySet().removeIf(entry -> entry.getValue().userId == target.id);
        log("USER_PASSWORD_RESET_BY_ADMIN", "USER", target.id, "管理员重置用户密码", operator.id, currentIp(operator));
        return buildUserView(target);
    }

    public SurveyPeriodView savePeriod(String token, SurveyPeriodRequest request) {
        requireRole(token, Role.PROVINCE);
        SurveyPeriod period = request.periodId() == null ? new SurveyPeriod() : periods.get(request.periodId());
        if (period == null) {
            period = new SurveyPeriod();
            period.id = nextId();
            periods.put(period.id, period);
        }
        period.name = requireText(request.name(), "调查期名称不能为空");
        period.startDate = requireDate(request.startDate(), "开始时间不能为空");
        period.endDate = requireDate(request.endDate(), "结束时间不能为空");
        period.submissionStart = requireDate(request.submissionStart(), "填报开始时间不能为空");
        period.submissionEnd = requireDate(request.submissionEnd(), "填报结束时间不能为空");
        validatePeriodPlanRule(period.startDate, period.endDate);
        period.active = request.active();
        period.updatedAt = Instant.now();
        persistState();
        return SurveyPeriodView.from(period);
    }

    public List<SurveyPeriodView> listPeriods(String token) {
        requireUser(token);
        return periods.values().stream().sorted(Comparator.comparing(p -> p.startDate)).map(SurveyPeriodView::from).collect(Collectors.toList());
    }

    public Map<String, Object> dashboard(String token) {
        UserAccount account = requireUser(token);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", buildUserView(account));
        data.put("periods", listPeriods(token));
        data.put("reports", listReports(token, null, null, null).stream().limit(10).toList());
        data.put("notices", listNotices(token, null, null, null, null, null).stream().limit(10).toList());
        if (account.role == Role.PROVINCE) {
            data.put("summary", summary(token, null));
            data.put("sampling", sampling(token, null));
            data.put("monitor", monitor());
        }
        return data;
    }

    public byte[] exportSummaryExcel(String token, Long periodId) {
        SummaryView summary = summary(token, periodId);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("企业总数", String.valueOf(summary.enterpriseCount())));
        rows.add(List.of("建档期总岗位数", String.valueOf(summary.archivedJobs())));
        rows.add(List.of("调查期总岗位数", String.valueOf(summary.surveyJobs())));
        rows.add(List.of("岗位变化总数", String.valueOf(summary.jobChangeTotal())));
        rows.add(List.of("岗位减少总数", String.valueOf(summary.jobDecreaseTotal())));
        rows.add(List.of("岗位变化数量占比", String.format(Locale.ROOT, "%.2f%%", summary.changeRatio())));
        return exportWorkbook("汇总数据", List.of("指标", "数值"), rows);
    }

    public byte[] exportSummaryCsv(String token, Long periodId) {
        SummaryView summary = summary(token, periodId);
        return exportCsv(List.of(
                List.of("指标", "数值"),
                List.of("企业总数", String.valueOf(summary.enterpriseCount())),
                List.of("建档期总岗位数", String.valueOf(summary.archivedJobs())),
                List.of("调查期总岗位数", String.valueOf(summary.surveyJobs())),
                List.of("岗位变化总数", String.valueOf(summary.jobChangeTotal())),
                List.of("岗位减少总数", String.valueOf(summary.jobDecreaseTotal())),
                List.of("岗位变化数量占比", String.format(Locale.ROOT, "%.2f%%", summary.changeRatio()))
        ));
    }

    public byte[] exportSamplingCsv(String token, String cityFilter) {
        SamplingView sampling = sampling(token, cityFilter);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("地市", "企业数", "占比(%)"));
        for (SamplingRow row : sampling.rows()) {
            rows.add(List.of(row.cityName(), String.valueOf(row.enterpriseCount()), String.format(Locale.ROOT, "%.2f", row.ratio())));
        }
        return exportCsv(rows);
    }

    public byte[] exportComparisonCsv(String token, ComparisonRequest request) {
        ComparisonView comparison = comparison(token, request);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("分组", "左侧企业数", "右侧企业数", "左侧建档期岗位", "右侧建档期岗位", "左侧调查期岗位", "右侧调查期岗位", "左侧变化", "右侧变化"));
        for (ComparisonRow row : comparison.rows()) {
            rows.add(List.of(
                    row.groupKey(),
                    String.valueOf(row.leftEnterpriseCount()),
                    String.valueOf(row.rightEnterpriseCount()),
                    String.valueOf(row.leftArchivedJobs()),
                    String.valueOf(row.rightArchivedJobs()),
                    String.valueOf(row.leftSurveyJobs()),
                    String.valueOf(row.rightSurveyJobs()),
                    String.valueOf(row.leftChangeJobs()),
                    String.valueOf(row.rightChangeJobs())
            ));
        }
        return exportCsv(rows);
    }

    public byte[] exportTrendCsv(String token, TrendRequest request) {
        TrendView trend = trend(token, request);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("调查期", "变化率(%)", "环比增长率(%)"));
        for (TrendRow row : trend.rows()) {
            rows.add(List.of(row.periodName(), String.format(Locale.ROOT, "%.2f", row.changeRatio()), row.ringRatio() == null ? "" : String.format(Locale.ROOT, "%.2f", row.ringRatio())));
        }
        if (trend.notes() != null && !trend.notes().isEmpty()) {
            rows.add(List.of("备注", String.join("；", trend.notes()), ""));
        }
        return exportCsv(rows);
    }

    public PublishResult publishToMinistry(String token, Long periodId) {
        return publishToMinistryInternal(token, periodId, true).result;
    }

    public String exportMinistryXml(String token, Long periodId) {
        MinistryExportPackage exportPackage = publishToMinistryInternal(token, periodId, true);
        return buildMinistryXml(exportPackage);
    }

    public MinistryExportView ministryExportPreview(String token, Long periodId) {
        MinistryExportPackage exportPackage = publishToMinistryInternal(token, periodId, false);
        return new MinistryExportView(exportPackage.result.periodId(), exportPackage.result.periodName(), exportPackage.result.total(), exportPackage.result.changed(), exportPackage.result.publishedAt(),
                exportPackage.records.stream()
                        .map(report -> MinistryRecord.from(report, findEnterprise(report.enterpriseId)))
                        .toList());
    }

    private MinistryExportPackage publishToMinistryInternal(String token, Long periodId, boolean publish) {
        UserAccount account = requireRole(token, Role.PROVINCE);
        SurveyPeriod period = periodId == null
                ? periods.values().stream().max(Comparator.comparing(p -> p.startDate)).orElseThrow(() -> badRequest("无可用调查期"))
                : requirePeriod(periodId);
        List<MonthlyReport> ready = reports.values().stream()
                .filter(report -> Objects.equals(report.periodId, period.id))
                .filter(report -> report.status == ReportStatus.PROVINCE_APPROVED || report.status == ReportStatus.PUBLISHED_TO_MINISTRY)
                .toList();
        if (ready.isEmpty()) {
            throw badRequest("当前调查期无可上报数据");
        }
        int changed = 0;
        if (publish) {
            for (MonthlyReport report : ready) {
                if (report.status != ReportStatus.PUBLISHED_TO_MINISTRY) {
                    report.status = ReportStatus.PUBLISHED_TO_MINISTRY;
                    report.updatedAt = Instant.now();
                    changed++;
                }
                log("REPORT_PUBLISH_MINISTRY", "REPORT", report.id,
                        "省级上报至部级系统，调查期=" + period.name,
                        account.id, currentIp(account));
            }
            persistState();
            emit("REPORT_PUBLISH_MINISTRY", Map.of("periodId", period.id, "periodName", period.name, "count", ready.size()));
        }
        List<MonthlyReport> effectiveRows = ready.stream().sorted(Comparator.comparing((MonthlyReport report) -> report.enterpriseName)).toList();
        PublishResult result = new PublishResult(period.id, period.name, effectiveRows.size(), changed, Instant.now());
        return new MinistryExportPackage(result, effectiveRows);
    }

    private String buildMinistryXml(MinistryExportPackage exportPackage) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<MinistryEmploymentReport>");
        xml.append(tag("periodId", String.valueOf(exportPackage.result.periodId())));
        xml.append(tag("periodName", escapeXml(exportPackage.result.periodName())));
        xml.append(tag("publishedAt", exportPackage.result.publishedAt().toString()));
        xml.append("<records>");
        for (MonthlyReport report : exportPackage.records) {
            ReportData data = report.effectiveData();
            xml.append("<record>");
            xml.append(tag("reportId", String.valueOf(report.id)));
            xml.append(tag("enterpriseName", escapeXml(report.enterpriseName)));
            xml.append(tag("orgCode", escapeXml(findEnterprise(report.enterpriseId).orgCode)));
            xml.append(tag("cityName", escapeXml(report.cityName)));
            xml.append(tag("archivedJobs", String.valueOf(data.archivedJobs)));
            xml.append(tag("surveyJobs", String.valueOf(data.surveyJobs)));
            xml.append(tag("decreaseType", escapeXml(String.valueOf(data.decreaseType))));
            xml.append(tag("mainReason", escapeXml(String.valueOf(data.mainReason))));
            xml.append(tag("mainReasonDescription", escapeXml(String.valueOf(data.mainReasonDescription))));
            xml.append("</record>");
        }
        xml.append("</records></MinistryEmploymentReport>");
        return xml.toString();
    }

    public MinistryExportView exportMinistryJson(String token, Long periodId, boolean publish) {
        MinistryExportPackage exportPackage = publishToMinistryInternal(token, periodId, publish);
        return new MinistryExportView(exportPackage.result.periodId(), exportPackage.result.periodName(), exportPackage.result.total(), exportPackage.result.changed(), exportPackage.result.publishedAt(),
                exportPackage.records.stream()
                        .map(report -> MinistryRecord.from(report, findEnterprise(report.enterpriseId)))
                        .toList());
    }

    public byte[] exportMinistryExcel(String token, Long periodId) {
        MinistryExportPackage exportPackage = publishToMinistryInternal(token, periodId, true);
        List<List<String>> rows = exportPackage.records.stream().map(report -> {
            EnterpriseProfile profile = findEnterprise(report.enterpriseId);
            ReportData data = report.effectiveData();
            return List.of(
                    String.valueOf(report.id),
                    String.valueOf(report.enterpriseName),
                    String.valueOf(profile.orgCode),
                    String.valueOf(report.cityName),
                    String.valueOf(data.archivedJobs),
                    String.valueOf(data.surveyJobs),
                    String.valueOf(data.decreaseType),
                    String.valueOf(data.mainReason),
                    String.valueOf(data.mainReasonDescription)
            );
        }).toList();
        return exportWorkbook("部级上报数据", List.of("报表ID", "企业名称", "组织机构代码", "地市", "建档期岗位", "调查期岗位", "减少类型", "主要原因", "主要原因说明"), rows);
    }

    public List<TransmissionView> listTransmissions(String token, Long periodId) {
        requireRole(token, Role.PROVINCE, Role.CITY);
        return auditLogs.stream()
                .filter(log -> "REPORT_PUBLISH_MINISTRY".equals(log.action))
                .filter(log -> periodId == null || Objects.equals(requireReport(log.targetId).periodId, periodId))
                .sorted(Comparator.comparing((AuditLog log) -> log.createdAt).reversed())
                .map(log -> {
                    MonthlyReport report = requireReport(log.targetId);
                    return new TransmissionView(log.id, report.id, report.enterpriseName, report.periodId, report.periodName,
                            "SUCCESS", log.description, log.actorName, log.createdAt);
                })
                .toList();
    }

    private List<MonthlyReport> effectiveReportsForPeriod(Long periodId) {
        return reports.values().stream()
                .filter(report -> Objects.equals(report.periodId, periodId))
                .filter(report -> report.status == ReportStatus.PROVINCE_APPROVED || report.status == ReportStatus.PUBLISHED_TO_MINISTRY)
                .toList();
    }

    private List<MonthlyReport> filterReports(ComparisonRequest request, Long periodId) {
        return reports.values().stream()
                .filter(report -> Objects.equals(report.periodId, periodId))
                .filter(report -> report.status == ReportStatus.PROVINCE_APPROVED || report.status == ReportStatus.PUBLISHED_TO_MINISTRY)
                .filter(report -> request.cityName() == null || request.cityName().isBlank() || containsIgnoreCase(report.cityName, request.cityName()))
                .filter(report -> request.enterpriseNature() == null || request.enterpriseNature().isBlank() || containsIgnoreCase(findEnterprise(report.enterpriseId).enterpriseNature, request.enterpriseNature()))
                .filter(report -> request.industry() == null || request.industry().isBlank() || containsIgnoreCase(findEnterprise(report.enterpriseId).industry, request.industry()))
                .toList();
    }

    private List<MonthlyReport> filterByConditions(String cityName, String industry, String enterpriseNature, Long periodId) {
        return reports.values().stream()
                .filter(report -> periodId == null || Objects.equals(report.periodId, periodId))
                .filter(report -> report.status == ReportStatus.PROVINCE_APPROVED || report.status == ReportStatus.PUBLISHED_TO_MINISTRY)
                .filter(report -> cityName == null || cityName.isBlank() || containsIgnoreCase(report.cityName, cityName))
                .filter(report -> enterpriseNature == null || enterpriseNature.isBlank() || containsIgnoreCase(findEnterprise(report.enterpriseId).enterpriseNature, enterpriseNature))
                .filter(report -> industry == null || industry.isBlank() || containsIgnoreCase(findEnterprise(report.enterpriseId).industry, industry))
                .toList();
    }

    private Map<String, ComparisonMetric> aggregateComparison(List<MonthlyReport> data, List<String> dimensions) {
        Map<String, ComparisonMetric> result = new LinkedHashMap<>();
        for (MonthlyReport report : data) {
            String key = buildComparisonKey(report, dimensions);
            ComparisonMetric metric = result.computeIfAbsent(key, ignored -> new ComparisonMetric());
            metric.enterpriseCount += 1;
            metric.archivedJobs += report.effectiveData().archivedJobs;
            metric.surveyJobs += report.effectiveData().surveyJobs;
            metric.changeJobs += report.effectiveData().surveyJobs - report.effectiveData().archivedJobs;
        }
        return result;
    }

    private String buildComparisonKey(MonthlyReport report, List<String> dimensions) {
        if (dimensions.isEmpty()) {
            return report.cityName;
        }
        List<String> values = new ArrayList<>();
        for (String dimension : dimensions) {
            switch (dimension) {
                case "city" -> values.add(report.cityName);
                case "nature" -> values.add(findEnterprise(report.enterpriseId).enterpriseNature);
                case "industry" -> values.add(findEnterprise(report.enterpriseId).industry);
                default -> values.add("未分类");
            }
        }
        return String.join(" / ", values);
    }

    private List<String> normalizeDimensions(List<String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return List.of("city");
        }
        return dimensions.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).map(String::toLowerCase).distinct().toList();
    }

    private byte[] exportWorkbook(String sheetName, List<String> headers, List<List<String>> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
            }
            int rowIndex = 1;
            for (List<String> rowData : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < rowData.size(); i++) {
                    row.createCell(i).setCellValue(rowData.get(i));
                }
            }
            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (java.io.IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Excel导出失败", ex);
        }
    }

    private byte[] exportCsv(List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append('\uFEFF');
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(csvEscape(row.get(i)));
            }
            builder.append('\n');
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<AuditLog> filterAuditLogs(String targetType, Long targetId, String action,
                                           String actorName, String createdFrom, String createdTo) {
        LocalDate from = parseOptionalDate(createdFrom);
        LocalDate to = parseOptionalDate(createdTo);
        return auditLogs.stream()
                .filter(log -> targetType == null || targetType.isBlank() || log.targetType.equalsIgnoreCase(targetType))
                .filter(log -> targetId == null || Objects.equals(log.targetId, targetId))
                .filter(log -> action == null || action.isBlank() || containsIgnoreCase(log.action, action))
                .filter(log -> actorName == null || actorName.isBlank() || containsIgnoreCase(log.actorName, actorName))
                .filter(log -> {
                    if (log.createdAt == null) {
                        return from == null && to == null;
                    }
                    LocalDate created = log.createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                    return (from == null || !created.isBefore(from)) && (to == null || !created.isAfter(to));
                })
                .toList();
    }

    private List<String> normalizeReportFields(List<String> fields) {
        List<String> defaults = List.of("id", "enterpriseName", "periodName", "archivedJobs", "surveyJobs", "status");
        Set<String> allowed = Set.of("id", "enterpriseName", "cityName", "periodName", "archivedJobs", "surveyJobs",
                "status", "cityReviewReason", "provinceReviewReason", "submittedAt", "updatedAt");
        List<String> source = (fields == null || fields.isEmpty()) ? defaults : fields;
        List<String> normalized = source.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isBlank() && allowed.contains(s)).toList();
        if (normalized.isEmpty()) {
            return defaults;
        }
        return new ArrayList<>(new LinkedHashSet<>(normalized));
    }

    private List<String> normalizeEnterpriseFields(List<String> fields) {
        List<String> defaults = List.of("id", "enterpriseName", "orgCode", "cityName", "status");
        Set<String> allowed = Set.of("id", "enterpriseName", "orgCode", "cityName", "countyName", "enterpriseNature",
                "industry", "contactName", "contactPhone", "status", "reviewReason", "submittedAt", "reviewedAt");
        List<String> source = (fields == null || fields.isEmpty()) ? defaults : fields;
        List<String> normalized = source.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isBlank() && allowed.contains(s)).toList();
        if (normalized.isEmpty()) {
            return defaults;
        }
        return new ArrayList<>(new LinkedHashSet<>(normalized));
    }

    private String reportFieldLabel(String field) {
        return switch (field) {
            case "id" -> "ID";
            case "enterpriseName" -> "企业名称";
            case "cityName" -> "地市";
            case "periodName" -> "调查期";
            case "archivedJobs" -> "建档期就业人数";
            case "surveyJobs" -> "调查期就业人数";
            case "status" -> "状态";
            case "cityReviewReason" -> "市级审核意见";
            case "provinceReviewReason" -> "省级审核意见";
            case "submittedAt" -> "提交时间";
            case "updatedAt" -> "更新时间";
            default -> field;
        };
    }

    private String enterpriseFieldLabel(String field) {
        return switch (field) {
            case "id" -> "ID";
            case "enterpriseName" -> "企业名称";
            case "orgCode" -> "组织机构代码";
            case "cityName" -> "地市";
            case "countyName" -> "区县";
            case "enterpriseNature" -> "企业性质";
            case "industry" -> "所属行业";
            case "contactName" -> "联系人";
            case "contactPhone" -> "联系电话";
            case "status" -> "备案状态";
            case "reviewReason" -> "审核意见";
            case "submittedAt" -> "提交时间";
            case "reviewedAt" -> "审核时间";
            default -> field;
        };
    }

    private String reportFieldValue(MonthlyReportView row, String field) {
        return switch (field) {
            case "id" -> String.valueOf(row.id());
            case "enterpriseName" -> String.valueOf(row.enterpriseName());
            case "cityName" -> String.valueOf(row.cityName());
            case "periodName" -> String.valueOf(row.periodName());
            case "archivedJobs" -> String.valueOf(row.archivedJobs());
            case "surveyJobs" -> String.valueOf(row.surveyJobs());
            case "status" -> String.valueOf(row.status());
            case "cityReviewReason" -> String.valueOf(row.cityReviewReason());
            case "provinceReviewReason" -> String.valueOf(row.provinceReviewReason());
            case "submittedAt" -> String.valueOf(row.submittedAt());
            case "updatedAt" -> String.valueOf(row.updatedAt());
            default -> "";
        };
    }

    private String enterpriseFieldValue(EnterpriseView row, String field) {
        return switch (field) {
            case "id" -> String.valueOf(row.id());
            case "enterpriseName" -> String.valueOf(row.enterpriseName());
            case "orgCode" -> String.valueOf(row.orgCode());
            case "cityName" -> String.valueOf(row.cityName());
            case "countyName" -> String.valueOf(row.countyName());
            case "enterpriseNature" -> String.valueOf(row.enterpriseNature());
            case "industry" -> String.valueOf(row.industry());
            case "contactName" -> String.valueOf(row.contactName());
            case "contactPhone" -> String.valueOf(row.contactPhone());
            case "status" -> String.valueOf(row.status());
            case "reviewReason" -> String.valueOf(row.reviewReason());
            case "submittedAt" -> String.valueOf(row.submittedAt());
            case "reviewedAt" -> String.valueOf(row.reviewedAt());
            default -> "";
        };
    }

    private String csvEscape(String value) {
        String text = value == null ? "" : value;
        if (text.contains("\"") || text.contains(",") || text.contains("\n") || text.contains("\r")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private boolean canSeeEnterprise(UserAccount account, EnterpriseProfile profile) {
        return switch (account.role) {
            case PROVINCE -> true;
            case CITY -> containsIgnoreCase(profile.cityName, account.cityName);
            case ENTERPRISE -> Objects.equals(profile.enterpriseUserId, account.id);
        };
    }

    private boolean canSeeReport(UserAccount account, MonthlyReport report) {
        return switch (account.role) {
            case PROVINCE -> true;
            case CITY -> isReportInCity(account, report);
            case ENTERPRISE -> Objects.equals(report.enterpriseId, findEnterpriseForActor(account).id);
        };
    }

    private boolean canSeeNotice(UserAccount account, NoticeRecord notice) {
        if (account.role == Role.PROVINCE) {
            return true;
        }
        if (notice.status != NoticeStatus.ACTIVE) {
            return false;
        }
        if (notice.appliesToAll) {
            return true;
        }
        if (account.role == Role.CITY) {
            return notice.targetCities.stream().anyMatch(city -> containsIgnoreCase(city, account.cityName));
        }
        EnterpriseProfile profile = findEnterpriseForActor(account);
        return profile != null && (notice.targetCities.isEmpty() || notice.targetCities.stream().anyMatch(city -> containsIgnoreCase(city, profile.cityName)));
    }

    private boolean isReportInCity(UserAccount account, MonthlyReport report) {
        EnterpriseProfile profile = findEnterprise(report.enterpriseId);
        return profile != null && containsIgnoreCase(profile.cityName, account.cityName);
    }

    private void ensureReportOwner(UserAccount account, MonthlyReport report) {
        if (account.role == Role.ENTERPRISE) {
            EnterpriseProfile profile = findEnterpriseForActor(account);
            if (profile == null || !Objects.equals(profile.id, report.enterpriseId)) {
                throw forbidden("无权操作该企业数据");
            }
        }
    }

    private void validateReportRequest(MonthlyReportRequest request) {
        if (request.archivedJobs() == null || request.surveyJobs() == null) {
            throw badRequest("建档期就业人数和调查期就业人数不能为空");
        }
        if (request.archivedJobs() < 0 || request.surveyJobs() < 0) {
            throw badRequest("就业人数不能为负数");
        }
        if (request.otherReason() == null || request.otherReason().isBlank()) {
            throw badRequest("其他原因不能为空");
        }
        if (request.archivedJobs() != null && request.surveyJobs() != null && request.surveyJobs() < request.archivedJobs()) {
            if (isBlank(request.decreaseType()) || isBlank(request.mainReason()) || isBlank(request.mainReasonDescription())) {
                throw badRequest("当调查期就业人数小于建档期就业人数时，减少类型、主要原因、主要原因说明为必填项");
            }
        }
    }

    private EnterpriseProfile findEnterpriseForActor(UserAccount account) {
        if (account.role == Role.ENTERPRISE) {
            return enterprises.values().stream().filter(profile -> Objects.equals(profile.enterpriseUserId, account.id)).findFirst().orElse(null);
        }
        return enterprises.values().stream().filter(profile -> Objects.equals(profile.id, account.enterpriseId)).findFirst().orElse(null);
    }

    private EnterpriseProfile findEnterprise(long enterpriseId) {
        EnterpriseProfile profile = enterprises.get(enterpriseId);
        if (profile == null) {
            throw badRequest("企业不存在");
        }
        return profile;
    }

    private MonthlyReport requireReport(long reportId) {
        MonthlyReport report = reports.get(reportId);
        if (report == null) {
            throw badRequest("报表不存在");
        }
        return report;
    }

    private NoticeRecord requireNotice(long noticeId) {
        NoticeRecord notice = notices.get(noticeId);
        if (notice == null) {
            throw badRequest("通知不存在");
        }
        return notice;
    }

    private SurveyPeriod requirePeriod(Long periodId) {
        if (periodId == null) {
            throw badRequest("调查期不能为空");
        }
        SurveyPeriod period = periods.get(periodId);
        if (period == null) {
            throw badRequest("调查期不存在");
        }
        return period;
    }

    private boolean isWithinReportWindow(SurveyPeriod period) {
        LocalDate today = LocalDate.now();
        return !today.isBefore(period.submissionStart) && !today.isAfter(period.submissionEnd);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String requireText(String value, String message) {
        if (isBlank(value)) {
            throw badRequest(message);
        }
        return value.trim();
    }

    private LocalDate requireDate(String value, String message) {
        if (isBlank(value)) {
            throw badRequest(message);
        }
        return LocalDate.parse(value.trim(), DATE_FMT);
    }

    private LocalDate parseOptionalDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        return LocalDate.parse(value.trim(), DATE_FMT);
    }

    private void validatePeriodPlanRule(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return;
        }
        if (endDate.isBefore(startDate)) {
            throw badRequest("调查期结束日期不能早于开始日期");
        }
        if (startDate.getYear() != endDate.getYear() || startDate.getMonthValue() != endDate.getMonthValue()) {
            throw badRequest("调查期必须位于同一自然月内");
        }
        int month = startDate.getMonthValue();
        int startDay = startDate.getDayOfMonth();
        int endDay = endDate.getDayOfMonth();
        int monthEndDay = startDate.lengthOfMonth();
        if (month <= 3) {
            boolean upperHalf = startDay == 1 && endDay == 15;
            boolean lowerHalf = startDay == 16 && endDay == monthEndDay;
            if (!upperHalf && !lowerHalf) {
                throw badRequest("1-3月需按半月报配置：上半月(1-15)或下半月(16-月末)");
            }
            return;
        }
        if (startDay != 1 || endDay != monthEndDay) {
            throw badRequest("4-12月需按整月报配置：每月1日至月末");
        }
    }

    private String nvl(String value, String fallback, String defaultValue) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        if (fallback != null) {
            return fallback;
        }
        return defaultValue;
    }

    private void validatePasswordRule(String password) {
        if (password == null || password.length() < 8 || password.length() > 16) {
            throw badRequest("密码长度必须为8-16位");
        }
        if (!password.matches(".*[A-Za-z].*")) {
            throw badRequest("密码必须包含字母");
        }
        if (!password.matches(".*\\d.*")) {
            throw badRequest("密码必须包含数字");
        }
        if (!password.matches(".*[^A-Za-z0-9].*")) {
            throw badRequest("密码必须包含特殊字符");
        }
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "密码加密失败", ex);
        }
    }

    private String randomDigits(int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String getPeriodName(EnterpriseProfile profile) {
        return Optional.ofNullable(profile.latestPeriodName).orElse("-");
    }

    private UserView buildUserView(UserAccount account) {
        return new UserView(account.id, account.username, account.role.name(), account.cityName, account.enabled, account.lockedUntil);
    }

    private UserManageView buildUserManageView(UserAccount account) {
        return new UserManageView(account.id, account.username, account.role.name(), account.cityName,
                account.phone, account.enabled, account.failedLoginCount,
                account.lockedUntil, account.enterpriseId != null);
    }

    private UserAccount requireUser(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized("未登录");
        }
        SessionInfo session = sessions.get(token);
        if (session == null || session.expiresAt.isBefore(Instant.now())) {
            sessions.remove(token);
            throw unauthorized("会话已过期，请重新登录");
        }
        session.expiresAt = Instant.now().plus(sessionTtl);
        UserAccount account = usersById.get(session.userId);
        if (account == null || !account.enabled) {
            throw unauthorized("账号不可用");
        }
        return account;
    }

    private UserAccount requireRole(String token, Role... allowed) {
        UserAccount account = requireUser(token);
        if (Arrays.stream(allowed).noneMatch(role -> role == account.role)) {
            throw forbidden("无权访问该功能");
        }
        return account;
    }

    private UserAccount requireUserById(long userId) {
        UserAccount account = usersById.get(userId);
        if (account == null) {
            throw badRequest("用户不存在");
        }
        return account;
    }

    private String currentIp(UserAccount account) {
        return Optional.ofNullable(account.lastKnownIp).orElse("127.0.0.1");
    }

    private String currentIpFromToken(String token) {
        SessionInfo session = sessions.get(token);
        return session == null ? "127.0.0.1" : session.clientIp;
    }

    private void log(String action, String targetType, long targetId, String description, long actorId, String ip) {
        AuditLog log = new AuditLog();
        log.id = nextId();
        log.action = action;
        log.targetType = targetType;
        log.targetId = targetId;
        log.description = description;
        log.actorId = actorId;
        log.actorName = requireUserById(actorId).username;
        log.clientIp = ip;
        log.createdAt = Instant.now();
        auditLogs.add(log);
        auditLogRepository.save(log);
    }

    private void seedAccounts() {
        createSeedUser("province_admin", Role.PROVINCE, "昆明市", "13800000001", "P@ssw0rd1");
        createSeedUser("kunming_city", Role.CITY, "昆明市", "13800000002", "P@ssw0rd1");
        createSeedUser("yuxi_city", Role.CITY, "玉溪市", "13800000003", "P@ssw0rd1");
        createSeedUser("alpha_corp", Role.ENTERPRISE, "昆明市", "13800000004", "P@ssw0rd1");
        createSeedUser("beta_corp", Role.ENTERPRISE, "玉溪市", "13800000005", "P@ssw0rd1");
    }

    private void createSeedUser(String username, Role role, String cityName, String phone, String password) {
        UserAccount user = new UserAccount();
        user.id = nextId();
        user.username = username;
        user.normalizedUsername = username.toLowerCase(Locale.ROOT);
        user.role = role;
        user.cityName = cityName;
        user.phone = phone;
        user.salt = UUID.randomUUID().toString().replace("-", "");
        user.passwordHash = hashPassword(password, user.salt);
        user.enabled = true;
        usersById.put(user.id, user);
        usersByUsername.put(user.normalizedUsername, user);
    }

    private void seedEnterprises() {
        createSeedEnterprise("alpha_corp", "530100001", "云南高原科技有限公司", "有限责任公司", "信息技术", "张三", "13911112222", "昆明市", "五华区", EnterpriseStatus.APPROVED, "2026-01-10");
        createSeedEnterprise("beta_corp", "530400002", "玉溪绿色制造厂", "有限责任公司", "制造业", "李四", "13933334444", "玉溪市", "红塔区", EnterpriseStatus.PENDING_PROVINCE_REVIEW, "2026-02-11");
    }

    private void createSeedEnterprise(String username, String orgCode, String name, String nature, String industry, String contactName, String contactPhone, String cityName, String countyName, EnterpriseStatus status, String submittedDate) {
        EnterpriseProfile profile = new EnterpriseProfile();
        profile.id = nextId();
        profile.enterpriseUserId = usersByUsername.get(username).id;
        profile.orgCode = orgCode;
        profile.enterpriseName = name;
        profile.enterpriseNature = nature;
        profile.industry = industry;
        profile.contactName = contactName;
        profile.contactPhone = contactPhone;
        profile.cityName = cityName;
        profile.countyName = countyName;
        profile.regionProvince = "云南省";
        profile.status = status;
        profile.submittedAt = LocalDate.parse(submittedDate, DATE_FMT).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        profile.updatedAt = Instant.now();
        enterprises.put(profile.id, profile);
        usersById.get(profile.enterpriseUserId).enterpriseId = profile.id;
    }

    private void seedReports() {
        createSeedReport("alpha_corp", "2026年1月上半月调查期", 120, 114, ReportStatus.PROVINCE_APPROVED, "昆明市");
        createSeedReport("alpha_corp", "2026年1月下半月调查期", 118, 112, ReportStatus.PENDING_CITY_REVIEW, "昆明市");
        createSeedReport("beta_corp", "2026年2月上半月调查期", 90, 88, ReportStatus.PENDING_PROVINCE_REVIEW, "玉溪市");
    }

    private void createSeedReport(String username, String periodName, int archived, int survey, ReportStatus status, String cityName) {
        EnterpriseProfile enterprise = enterprises.values().stream().filter(p -> Objects.equals(p.enterpriseUserId, usersByUsername.get(username).id)).findFirst().orElseThrow();
        SurveyPeriod period = periods.values().stream().filter(p -> p.name.equals(periodName)).findFirst().orElse(periods.values().stream().findFirst().orElseThrow());
        MonthlyReport report = new MonthlyReport();
        report.id = nextId();
        report.enterpriseId = enterprise.id;
        report.enterpriseName = enterprise.enterpriseName;
        report.cityName = cityName;
        report.periodId = period.id;
        report.periodName = period.name;
        report.archivedJobs = archived;
        report.surveyJobs = survey;
        report.otherReason = "正常变动";
        report.status = status;
        report.createdAt = Instant.now();
        report.updatedAt = Instant.now();
        reports.put(report.id, report);
    }

    private void seedPeriods() {
        int year = 2026;
        for (int month = 1; month <= 12; month++) {
            if (month <= 3) {
                createHalfMonthPeriods(year, month, true);
            } else {
                createFullMonthPeriod(year, month, true);
            }
        }
    }

    private void createHalfMonthPeriods(int year, int month, boolean active) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        LocalDate mid = LocalDate.of(year, month, 15);
        createPeriod(String.format(Locale.ROOT, "%d年%d月上半月调查期", year, month),
                monthStart, mid, monthStart, mid, active);
        createPeriod(String.format(Locale.ROOT, "%d年%d月下半月调查期", year, month),
                mid.plusDays(1), monthEnd, mid.plusDays(1), monthEnd, active);
    }

    private void createFullMonthPeriod(int year, int month, boolean active) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        createPeriod(String.format(Locale.ROOT, "%d年%d月调查期", year, month),
                monthStart, monthEnd, monthStart, monthEnd, active);
    }

    private void createPeriod(String name, LocalDate start, LocalDate end, LocalDate submitStart, LocalDate submitEnd, boolean active) {
        SurveyPeriod period = new SurveyPeriod();
        period.id = nextId();
        period.name = name;
        period.startDate = start;
        period.endDate = end;
        period.submissionStart = submitStart;
        period.submissionEnd = submitEnd;
        period.active = active;
        period.updatedAt = Instant.now();
        periods.put(period.id, period);
    }

    private void seedNotices() {
        createNoticeSeed("2026年度上报安排", "1-3月执行半月报（上半月/下半月各1次），4-12月执行整月报，请按期完成填报。", true, List.of("昆明市", "玉溪市"), Role.PROVINCE, "province_admin");
        createNoticeSeed("昆明市补充通知", "请本市企业尽快完成2月报送。", false, List.of("昆明市"), Role.CITY, "kunming_city");
    }

    private void seedSystemSettings() {
        systemSettings.clear();
        upsertSetting("SESSION_TTL_MINUTES", "30");
        upsertSetting("SMS_TTL_MINUTES", "3");
        upsertSetting("LOGIN_FAILURE_THRESHOLD", "3");
        upsertSetting("SYSTEM_NOTICE", "请按时完成数据填报与审核。");
        applySystemSettings();
    }

    private void applySystemSettings() {
        sessionTtl = Duration.ofMinutes(normalizeMinutes(settingValue("SESSION_TTL_MINUTES", "30"), 30));
        smsTtl = Duration.ofMinutes(normalizeMinutes(settingValue("SMS_TTL_MINUTES", "3"), 3));
        loginFailureThreshold = normalizeThreshold(settingValue("LOGIN_FAILURE_THRESHOLD", "3"), 3);
    }

    private void upsertSetting(String key, String value) {
        SystemSetting setting = systemSettings.computeIfAbsent(key, ignored -> new SystemSetting());
        setting.settingKey = key;
        setting.settingValue = value;
        setting.updatedAt = Instant.now();
    }

    private String settingValue(String key, String defaultValue) {
        SystemSetting setting = systemSettings.get(key);
        if (setting == null || setting.settingValue == null || setting.settingValue.isBlank()) {
            return defaultValue;
        }
        return setting.settingValue.trim();
    }

    private int normalizeMinutes(Integer value, int fallback) {
        if (value == null || value < 1) {
            return fallback;
        }
        return value;
    }

    private int normalizeMinutes(String value, int fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return normalizeMinutes(Integer.valueOf(value.trim()), fallback);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int normalizeThreshold(Integer value, int fallback) {
        if (value == null || value < 1) {
            return fallback;
        }
        return value;
    }

    private int normalizeThreshold(String value, int fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            return normalizeThreshold(Integer.valueOf(value.trim()), fallback);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private SessionView buildSessionView(SessionInfo session) {
        UserAccount user = usersById.get(session.userId);
        return new SessionView(session.token, session.userId, user == null ? null : user.username,
                session.role == null ? null : session.role.name(), user == null ? null : user.cityName,
                session.clientIp, session.createdAt, session.expiresAt);
    }

    private void createNoticeSeed(String title, String content, boolean all, List<String> cities, Role role, String username) {
        NoticeRecord notice = new NoticeRecord();
        notice.id = nextId();
        notice.title = title;
        notice.content = content;
        notice.appliesToAll = all;
        notice.targetCities = new ArrayList<>(cities);
        notice.publisherRole = role;
        notice.publisherName = username;
        notice.publisherId = usersByUsername.get(username).id;
        notice.status = NoticeStatus.ACTIVE;
        notice.createdAt = Instant.now();
        notice.updatedAt = Instant.now();
        notices.put(notice.id, notice);
    }

    private EnterpriseProfile requireEnterprise(long enterpriseId) {
        return Optional.ofNullable(enterprises.get(enterpriseId)).orElseThrow(() -> badRequest("企业不存在"));
    }

    private long nextId() {
        return idSequence.incrementAndGet();
    }

    private <T> PagedResult<T> paginate(List<T> data, Integer page, Integer size) {
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = size == null || size < 1 ? 10 : size;
        if (normalizedSize > 100) {
            throw badRequest("每页条数不能超过100");
        }
        long total = data.size();
        int totalPages = total == 0 ? 1 : (int) ((total + normalizedSize - 1) / normalizedSize);
        int fromIndex = (normalizedPage - 1) * normalizedSize;
        if (fromIndex >= data.size()) {
            return new PagedResult<>(List.of(), normalizedPage, normalizedSize, total, totalPages);
        }
        int toIndex = Math.min(fromIndex + normalizedSize, data.size());
        return new PagedResult<>(data.subList(fromIndex, toIndex), normalizedPage, normalizedSize, total, totalPages);
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && keyword != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String tag(String name, String value) {
        return "<" + name + ">" + value + "</" + name + ">";
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private IllegalArgumentException badRequest(String message) {
        return new IllegalArgumentException(message);
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    public enum Role {
        PROVINCE,
        CITY,
        ENTERPRISE
    }

    public enum EnterpriseStatus {
        DRAFT,
        PENDING_PROVINCE_REVIEW,
        APPROVED,
        REJECTED
    }

    public enum ReportStatus {
        DRAFT,
        PENDING_CITY_REVIEW,
        CITY_REJECTED,
        PENDING_PROVINCE_REVIEW,
        PROVINCE_REJECTED,
        PROVINCE_APPROVED,
        PUBLISHED_TO_MINISTRY
    }

    public enum NoticeStatus {
        DRAFT,
        ACTIVE,
        DELETED
    }

    @Entity
    @Table(name = "user_account")
    public static class UserAccount {
        @Id
        public long id;
        @Column(nullable = false)
        public String username;
        @Column(name = "normalized_username")
        public String normalizedUsername;
        @Enumerated(EnumType.STRING)
        public Role role;
        @Column(name = "city_name")
        public String cityName;
        public String phone;
        public String salt;
        @Column(name = "password_hash")
        public String passwordHash;
        public boolean enabled;
        @Column(name = "failed_login_count")
        public Integer failedLoginCount = 0;
        @Column(name = "last_failed_login_at")
        public Instant lastFailedLoginAt;
        @Column(name = "locked_until")
        public Instant lockedUntil;
        @Column(name = "sms_challenge_code")
        public String smsChallengeCode;
        @Column(name = "sms_challenge_expires_at")
        public Instant smsChallengeExpiresAt;
        @Column(name = "enterprise_id")
        public Long enterpriseId;
        @Column(name = "last_known_ip")
        public String lastKnownIp;
    }

    public static class SessionInfo {
        public String token;
        public long userId;
        public Role role;
        public String clientIp;
        public Instant createdAt;
        public Instant expiresAt;
    }

    @Entity
    @Table(name = "enterprise_profile")
    public static class EnterpriseProfile {
        @Id
        public long id;
        @Column(name = "enterprise_user_id")
        public Long enterpriseUserId;
        @Column(name = "region_province")
        public String regionProvince;
        @Column(name = "city_name")
        public String cityName;
        @Column(name = "county_name")
        public String countyName;
        @Column(name = "org_code")
        public String orgCode;
        @Column(name = "enterprise_name")
        public String enterpriseName;
        @Column(name = "enterprise_nature")
        public String enterpriseNature;
        public String industry;
        @Column(name = "contact_name")
        public String contactName;
        @Column(name = "contact_phone")
        public String contactPhone;
        public String address;
        @Enumerated(EnumType.STRING)
        public EnterpriseStatus status;
        @Column(name = "review_reason")
        public String reviewReason;
        @Column(name = "reviewed_by")
        public Long reviewedBy;
        @Column(name = "reviewed_at")
        public Instant reviewedAt;
        @Column(name = "submitted_at")
        public Instant submittedAt;
        @Column(name = "updated_at")
        public Instant updatedAt;
        @Column(name = "latest_period_name")
        public String latestPeriodName;
    }

    @Entity
    @Table(name = "monthly_report")
    public static class MonthlyReport {
        @Id
        public long id;
        @Column(name = "enterprise_id")
        public long enterpriseId;
        @Column(name = "enterprise_name")
        public String enterpriseName;
        @Column(name = "city_name")
        public String cityName;
        @Column(name = "period_id")
        public long periodId;
        @Column(name = "period_name")
        public String periodName;
        @Column(name = "archived_jobs")
        public Integer archivedJobs;
        @Column(name = "survey_jobs")
        public Integer surveyJobs;
        @Column(name = "other_reason", length = 1000)
        public String otherReason;
        @Column(name = "decrease_type")
        public String decreaseType;
        @Column(name = "main_reason")
        public String mainReason;
        @Column(name = "main_reason_description", length = 2000)
        public String mainReasonDescription;
        @Column(name = "secondary_reason")
        public String secondaryReason;
        @Column(name = "secondary_reason_description", length = 2000)
        public String secondaryReasonDescription;
        @Column(name = "third_reason")
        public String thirdReason;
        @Column(name = "third_reason_description", length = 2000)
        public String thirdReasonDescription;
        @Enumerated(EnumType.STRING)
        public ReportStatus status;
        @Column(name = "city_reviewed_by")
        public Long cityReviewedBy;
        @Column(name = "city_reviewed_at")
        public Instant cityReviewedAt;
        @Column(name = "city_review_reason", length = 1000)
        public String cityReviewReason;
        @Column(name = "province_reviewed_by")
        public Long provinceReviewedBy;
        @Column(name = "province_reviewed_at")
        public Instant provinceReviewedAt;
        @Column(name = "province_review_reason", length = 1000)
        public String provinceReviewReason;
        @Column(name = "submitted_at")
        public Instant submittedAt;
        @Column(name = "created_at")
        public Instant createdAt;
        @Column(name = "updated_at")
        public Instant updatedAt;
        @Embedded
        public ProvinceAdjustment provinceAdjustment;

        public ReportData effectiveData() {
            if (provinceAdjustment != null) {
                return new ReportData(provinceAdjustment.archivedJobs, provinceAdjustment.surveyJobs, provinceAdjustment.otherReason,
                        provinceAdjustment.decreaseType, provinceAdjustment.mainReason, provinceAdjustment.mainReasonDescription,
                        provinceAdjustment.secondaryReason, provinceAdjustment.secondaryReasonDescription,
                        provinceAdjustment.thirdReason, provinceAdjustment.thirdReasonDescription);
            }
            return new ReportData(archivedJobs, surveyJobs, otherReason, decreaseType, mainReason, mainReasonDescription,
                    secondaryReason, secondaryReasonDescription, thirdReason, thirdReasonDescription);
        }

        public String effectiveEnterpriseKey() {
            return enterpriseName + "-" + periodName;
        }
    }

    @Embeddable
    public static class ProvinceAdjustment {
        @Column(name = "province_adjustment_archived_jobs")
        public int archivedJobs;
        @Column(name = "province_adjustment_survey_jobs")
        public int surveyJobs;
        @Column(name = "province_adjustment_other_reason", length = 1000)
        public String otherReason;
        @Column(name = "province_adjustment_decrease_type")
        public String decreaseType;
        @Column(name = "province_adjustment_main_reason")
        public String mainReason;
        @Column(name = "province_adjustment_main_reason_description", length = 2000)
        public String mainReasonDescription;
        @Column(name = "province_adjustment_secondary_reason")
        public String secondaryReason;
        @Column(name = "province_adjustment_secondary_reason_description", length = 2000)
        public String secondaryReasonDescription;
        @Column(name = "province_adjustment_third_reason")
        public String thirdReason;
        @Column(name = "province_adjustment_third_reason_description", length = 2000)
        public String thirdReasonDescription;
        @Column(name = "province_adjustment_adjust_reason", length = 1000)
        public String adjustReason;
        @Column(name = "province_adjustment_adjusted_by")
        public long adjustedBy;
        @Column(name = "province_adjustment_adjusted_at")
        public Instant adjustedAt;
    }

    @Entity
    @Table(name = "notice_record")
    public static class NoticeRecord {
        @Id
        public long id;
        public String title;
        @Column(length = 4000)
        public String content;
        @Column(name = "applies_to_all")
        public boolean appliesToAll;
        @ElementCollection(fetch = FetchType.EAGER)
        @CollectionTable(name = "notice_target_city", joinColumns = @JoinColumn(name = "notice_id"))
        @Column(name = "city_name")
        public List<String> targetCities = new ArrayList<>();
        @Column(name = "publisher_id")
        public long publisherId;
        @Enumerated(EnumType.STRING)
        @Column(name = "publisher_role")
        public Role publisherRole;
        @Column(name = "publisher_name")
        public String publisherName;
        @Enumerated(EnumType.STRING)
        public NoticeStatus status;
        @Column(name = "created_at")
        public Instant createdAt;
        @Column(name = "updated_at")
        public Instant updatedAt;
        @Column(name = "deleted_at")
        public Instant deletedAt;
    }

    @Entity
    @Table(name = "survey_period")
    public static class SurveyPeriod {
        @Id
        public long id;
        public String name;
        @Column(name = "start_date")
        public LocalDate startDate;
        @Column(name = "end_date")
        public LocalDate endDate;
        @Column(name = "submission_start")
        public LocalDate submissionStart;
        @Column(name = "submission_end")
        public LocalDate submissionEnd;
        public boolean active;
        @Column(name = "updated_at")
        public Instant updatedAt;

        public boolean isPreviousTo(SurveyPeriod other) {
            return endDate.plusDays(1).equals(other.startDate);
        }
    }

    @Entity
    @Table(name = "audit_log")
    public static class AuditLog {
        @Id
        public long id;
        public String action;
        @Column(name = "target_type")
        public String targetType;
        @Column(name = "target_id")
        public long targetId;
        @Column(length = 2000)
        public String description;
        @Column(name = "actor_id")
        public long actorId;
        @Column(name = "actor_name")
        public String actorName;
        @Column(name = "client_ip")
        public String clientIp;
        @Column(name = "created_at")
        public Instant createdAt;
    }

    public record LoginRequest(String username, String password, Role role, String smsCode) {}
    public record LoginResponse(boolean success, String message, String token, UserView user, boolean requiresSmsCode, String phoneHint, String smsCodeHint, Instant lockUntil) {
        static LoginResponse success(UserView user, String token) { return new LoginResponse(true, "登录成功", token, user, false, null, null, null); }
        static LoginResponse failure(String message) { return new LoginResponse(false, message, null, null, false, null, null, null); }
        static LoginResponse smsChallenge(String phoneHint, String message, String smsCodeHint) { return new LoginResponse(false, message, null, null, true, phoneHint, smsCodeHint, null); }
        static LoginResponse locked(String message, Instant lockUntil) { return new LoginResponse(false, message, null, null, false, null, null, lockUntil); }
    }
    public record UserView(long id, String username, String role, String cityName, boolean enabled, Instant lockedUntil) {}
    public record UserManageView(long id, String username, String role, String cityName, String phone,
                                 boolean enabled, Integer failedLoginCount, Instant lockedUntil, boolean hasEnterpriseBinding) {}
    public record PagedResult<T>(List<T> items, int page, int size, long total, int totalPages) {}
    public record EnterpriseRequest(Long enterpriseId, String regionProvince, String cityName, String countyName, String orgCode, String enterpriseName, String enterpriseNature, String industry, String contactName, String contactPhone, String address) {}
    public record EnterpriseView(long id, String enterpriseName, String orgCode, String cityName, String countyName, String enterpriseNature, String industry, String contactName, String contactPhone, String status, String reviewReason, Instant submittedAt, Instant reviewedAt) {
        static EnterpriseView from(EnterpriseProfile profile, String latestPeriodName) {
            profile.latestPeriodName = latestPeriodName;
            return new EnterpriseView(profile.id, profile.enterpriseName, profile.orgCode, profile.cityName, profile.countyName, profile.enterpriseNature, profile.industry, profile.contactName, profile.contactPhone, profile.status == null ? "DRAFT" : profile.status.name(), profile.reviewReason, profile.submittedAt, profile.reviewedAt);
        }
    }
    public record MonthlyReportRequest(Long reportId, Long periodId, Integer archivedJobs, Integer surveyJobs, String otherReason, String decreaseType, String mainReason, String mainReasonDescription, String secondaryReason, String secondaryReasonDescription, String thirdReason, String thirdReasonDescription, String adjustReason) {}
    public record MonthlyReportView(long id, String enterpriseName, String cityName, String periodName, Integer archivedJobs, Integer surveyJobs, String otherReason, String decreaseType, String mainReason, String mainReasonDescription, String secondaryReason, String secondaryReasonDescription, String thirdReason, String thirdReasonDescription, String status, String cityReviewReason, String provinceReviewReason, Instant submittedAt, Instant createdAt, Instant updatedAt) {
        static MonthlyReportView from(MonthlyReport report) { ReportData data = report.effectiveData(); return new MonthlyReportView(report.id, report.enterpriseName, report.cityName, report.periodName, data.archivedJobs, data.surveyJobs, data.otherReason, data.decreaseType, data.mainReason, data.mainReasonDescription, data.secondaryReason, data.secondaryReasonDescription, data.thirdReason, data.thirdReasonDescription, report.status == null ? "DRAFT" : report.status.name(), report.cityReviewReason, report.provinceReviewReason, report.submittedAt, report.createdAt, report.updatedAt); }
    }
    public record NoticeRequest(Long noticeId, String title, String content, boolean appliesToAll, List<String> targetCities) {}
    public record NoticeView(long id, String title, String content, String publisherName, String publisherRole, String status, boolean appliesToAll, List<String> targetCities, Instant createdAt, Instant updatedAt) {
        static NoticeView from(NoticeRecord notice) { return new NoticeView(notice.id, notice.title, notice.content, notice.publisherName, notice.publisherRole == null ? null : notice.publisherRole.name(), notice.status == null ? "DRAFT" : notice.status.name(), notice.appliesToAll, new ArrayList<>(notice.targetCities), notice.createdAt, notice.updatedAt); }
        String scopeText() { return appliesToAll ? "全省" : String.join(",", targetCities); }
    }
    public record SummaryView(long periodId, String periodName, long enterpriseCount, long archivedJobs, long surveyJobs, long jobChangeTotal, long jobDecreaseTotal, double changeRatio, Map<String, Long> byCity) {}
    public record SamplingView(long total, List<SamplingRow> rows) {}
    public record SamplingRow(String cityName, long enterpriseCount, double ratio) {}
    public record ComparisonRequest(Long leftPeriodId, Long rightPeriodId, List<String> dimensions, String cityName, String enterpriseNature, String industry) {}
    public record ComparisonView(String leftPeriodName, String rightPeriodName, List<String> dimensions, List<ComparisonRow> rows) {}
    public record ComparisonRow(String groupKey, long leftEnterpriseCount, long rightEnterpriseCount, long leftArchivedJobs, long rightArchivedJobs, long leftSurveyJobs, long rightSurveyJobs, long leftChangeJobs, long rightChangeJobs) {}
    public record TrendRequest(List<Long> periodIds, String cityName, String enterpriseNature, String industry, List<String> note) {}
    public record TrendView(List<TrendRow> rows, List<String> notes) {}
    public record TrendRow(long periodId, String periodName, double changeRatio, Double ringRatio) {}
    public record MonitorView(int processors, long usedMemory, long maxMemory, int activeSessions, int auditCount, long uptimeSeconds) {}
    public record SessionView(String token, long userId, String username, String role, String cityName, String clientIp, Instant createdAt, Instant expiresAt) {}
    public record ForceLogoutRequest(String sessionToken) {}
    public record SystemSettingsRequest(Integer sessionTtlMinutes, Integer smsTtlMinutes, Integer loginFailureThreshold, String systemNotice) {}
    public record SystemSettingView(String settingKey, String settingValue, Instant updatedAt) { static SystemSettingView from(SystemSetting setting) { return new SystemSettingView(setting.settingKey, setting.settingValue, setting.updatedAt); } }
    public record AuditLogView(long id, String action, String targetType, long targetId, String description, String actorName, String clientIp, Instant createdAt) { static AuditLogView from(AuditLog log) { return new AuditLogView(log.id, log.action, log.targetType, log.targetId, log.description, log.actorName, log.clientIp, log.createdAt); } }
    public record SurveyPeriodRequest(Long periodId, String name, String startDate, String endDate, String submissionStart, String submissionEnd, boolean active) {}
    public record SurveyPeriodView(long id, String name, String startDate, String endDate, String submissionStart, String submissionEnd, boolean active) { static SurveyPeriodView from(SurveyPeriod period) { return new SurveyPeriodView(period.id, period.name, period.startDate.toString(), period.endDate.toString(), period.submissionStart.toString(), period.submissionEnd.toString(), period.active); } }
    public record UserCreateRequest(String username, String password, Role role, String cityName, String phone) {}
    public record UserRoleUpdateRequest(Long userId, Role role, String cityName) {}
    public record UserEnableRequest(Long userId, boolean enabled) {}
    public record UserUnlockRequest(Long userId) {}
    public record UserAdminResetPasswordRequest(Long userId, String newPassword, String confirmPassword) {}
    public record ChangePasswordRequest(String oldPassword, String newPassword, String confirmPassword) {}
    public record PasswordResetRequest(String username, String phone, String newPassword, String confirmPassword) {}
    public record BatchCityReviewRequest(List<Long> reportIds, boolean approved, String reason) {}
    public record BatchProvinceReviewRequest(List<Long> reportIds, boolean approved, String reason) {}
    public record BatchReviewResult(List<Long> successIds, List<String> failedMessages) {}
    public record PublishResult(long periodId, String periodName, int total, int changed, Instant publishedAt) {}
    public record TransmissionView(long logId, long reportId, String enterpriseName, long periodId, String periodName,
                                   String status, String message, String operator, Instant time) {}
    public record MinistryRecord(long reportId, String enterpriseName, String orgCode, String cityName, Integer archivedJobs,
                                 Integer surveyJobs, String decreaseType, String mainReason, String mainReasonDescription) {
        static MinistryRecord from(MonthlyReport report, EnterpriseProfile profile) {
            ReportData data = report.effectiveData();
            return new MinistryRecord(report.id, report.enterpriseName, profile.orgCode, report.cityName, data.archivedJobs,
                    data.surveyJobs, data.decreaseType, data.mainReason, data.mainReasonDescription);
        }
    }
    public record MinistryExportView(long periodId, String periodName, int total, int changed, Instant publishedAt, List<MinistryRecord> records) {}
    private record MinistryExportPackage(PublishResult result, List<MonthlyReport> records) {}
    public record ReportData(Integer archivedJobs, Integer surveyJobs, String otherReason, String decreaseType, String mainReason, String mainReasonDescription, String secondaryReason, String secondaryReasonDescription, String thirdReason, String thirdReasonDescription) {}

    @Entity
    @Table(name = "system_setting")
    public static class SystemSetting {
        @Id
        @Column(name = "setting_key")
        public String settingKey;
        @Column(name = "setting_value", length = 2000)
        public String settingValue;
        @Column(name = "updated_at")
        public Instant updatedAt;
    }

    private static class ComparisonMetric {
        long enterpriseCount;
        long archivedJobs;
        long surveyJobs;
        long changeJobs;
    }
}
