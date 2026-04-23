# 云南省企业就业失业数据采集系统

企业就业失业数据采集与审核平台，支持企业填报、市级审核、省级审核、通知公告、统计分析与导出。

## 1. 核心功能

- 企业备案与信息维护
- 企业调查期数据填报、提交与退回重报
- 市级审核、批量审核
- 省级审核、批量审核、代填修正
- 通知管理与定向发布
- 系统日志、在线会话与系统参数管理
- 统计分析（汇总/取样/对比/趋势/监控）与 CSV/Excel/XML/JSON 导出

## 2. 填报周期规则（最新）

- 1-3月执行半月报：
    - 上半月：1日-15日
    - 下半月：16日-月末
- 4-12月执行整月报：
    - 每月1日-月末

系统已在调查期管理中对该规则进行校验，不符合规则的调查期将被拒绝保存。

## 3. 技术栈

- Java 17
- Spring Boot 3.3.5（Web / JPA / Validation / WebSocket / Thymeleaf）
- Flyway
- H2（默认）/ MySQL（可切换）
- Apache POI（Excel导出）
- 前端：原生 HTML/CSS/JS + Chart.js

## 4. 项目结构

```text
src/
├── main/
│   ├── java/com/yunnan/datacollect/
│   │   ├── service/            # 核心业务逻辑
│   │   ├── web/                # API控制器与WebSocket
│   │   └── repository/         # JPA仓储
│   └── resources/
│       ├── application.properties
│       ├── db/migration/       # Flyway脚本
│       └── static/             # 前端静态页面
├── pom.xml
└── README.md
```

## 5. 环境要求

- JDK 17+
- Maven 3.9+

如果系统 Maven 不可用，可使用仓库内便携版：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -v
```

## 6. 配置说明

主要配置在 [main/resources/application.properties](main/resources/application.properties)。

- 服务端口：8082
- 默认数据库：H2 内存库
- Flyway 默认关闭（通过环境变量开启）

建议运行时开启 Flyway：

```powershell
$env:FLYWAY_ENABLED='true'
```

## 7. 本地启动

### 7.1 构建

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -q -DskipTests package
```

### 7.2 运行

```powershell
$env:FLYWAY_ENABLED='true'
& "C:\Program Files\Java\jdk-24\bin\java.exe" -jar target\datacollect-1.0.0.jar
```

### 7.3 健康检查

```text
GET http://localhost:8082/api/health
```

返回示例：

```json
{"success":true,"message":"OK","data":{"status":"UP","time":"2026-04-23"}}
```

## 8. 默认账号（演示）

- 省级：province_admin / P@ssw0rd1
- 市级：kunming_city / P@ssw0rd1
- 企业：alpha_corp / P@ssw0rd1

## 9. 变更文档

计划变更一（1-3月半月报、4-12月整月报）变更单见：

- [main/docs/项目变更单_计划变更一_半月报调整.md](main/docs/%E9%A1%B9%E7%9B%AE%E5%8F%98%E6%9B%B4%E5%8D%95_%E8%AE%A1%E5%88%92%E5%8F%98%E6%9B%B4%E4%B8%80_%E5%8D%8A%E6%9C%88%E6%8A%A5%E8%B0%83%E6%95%B4.md)

计划变更二（企业上报+市省审核支持手机端、工期由2个月调整为2个月+2周）变更单见：

- [main/docs/项目变更单_计划变更二_移动端与工期调整.md](main/docs/%E9%A1%B9%E7%9B%AE%E5%8F%98%E6%9B%B4%E5%8D%95_%E8%AE%A1%E5%88%92%E5%8F%98%E6%9B%B4%E4%BA%8C_%E7%A7%BB%E5%8A%A8%E7%AB%AF%E4%B8%8E%E5%B7%A5%E6%9C%9F%E8%B0%83%E6%95%B4.md)

## 10. 常见问题

1. 打包时报 Unable to rename ...jar to ...jar.original
     - 原因：JAR 正在被运行中的 Java 进程占用。
     - 处理：先停止占用进程，再执行 package。

2. 启动时报表/用户表不存在
     - 原因：Flyway 未启用，迁移脚本未执行。
     - 处理：设置环境变量 FLYWAY_ENABLED=true 后重启。