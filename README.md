# 就业数据采集系统 / Employment Data Collection System

软件项目管理大作业 — 就业数据采集与管理平台

A bilingual (Chinese/English) web application for collecting and managing employment data.

## Features / 功能

- **Dashboard / 首页** — Statistics overview: total records, companies, average salary, employment type distribution, and industry bar chart
- **Add Record / 添加数据** — Form to add a new employment record with validation
- **View / Search / Filter / 数据列表** — Table of all records with search by name/company/position, filter by industry and employment type
- **Export CSV / 导出** — Download filtered data as a CSV file (BOM-encoded for Excel)
- **Delete / 删除** — Remove individual records

## Tech Stack / 技术栈

- **Backend**: Python 3 + Flask + Flask-SQLAlchemy
- **Database**: SQLite (file: `instance/employment.db`)
- **Frontend**: Bootstrap 5.3 + Chart.js + Jinja2 templates

## Quick Start / 快速开始

```bash
# Install dependencies
pip install flask flask-sqlalchemy

# Run the app (database is auto-created on first launch)
python app.py
# Open http://localhost:5000 in your browser
```

Set `FLASK_DEBUG=1` for debug mode. Set `SECRET_KEY` env var in production.

## Running Tests / 运行测试

```bash
pip install pytest
python -m pytest tests/ -v
```

## Data Model / 数据模型

| Field | Type | Description |
|-------|------|-------------|
| name | str | 姓名 / Person name |
| company | str | 公司 / Company name |
| position | str | 职位 / Job title |
| industry | str | 行业 / Industry |
| location | str | 地点 / Work city |
| salary | int (nullable) | 薪资 / Monthly salary (CNY) |
| employment_type | str | 就业类型 / Full-time / Part-time / etc. |
| education | str | 学历 / Degree level |
| start_date | date (nullable) | 入职日期 / Start date |
| created_at | datetime | 创建时间 / Record creation time |

## Project Structure / 项目结构

```
employment-data-collect/
├── app.py              # Flask application (routes, model, config)
├── requirements.txt    # Python dependencies
├── templates/
│   ├── base.html       # Base template with Bootstrap navbar
│   ├── index.html      # Dashboard with stats & chart
│   ├── add.html        # Add record form
│   └── list.html       # Records table with search/filter/export
└── tests/
    └── test_app.py     # Pytest test suite (16 tests)
```