# MySQL Setup

## 1. Create Database

```sql
CREATE DATABASE IF NOT EXISTS yunnan_data_collect
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

## 2. Configure Credentials

Edit `main/resources/application.properties` if your MySQL account is not `root/root`.

## 3. Run Application

When the application starts, Flyway will execute:

- `V1__init_schema.sql` to create all tables and indexes.

If tables are empty, the service will auto-seed demo users and demo data.

## 4. Demo Accounts

- province_admin / P@ssw0rd1
- kunming_city / P@ssw0rd1
- alpha_corp / P@ssw0rd1
