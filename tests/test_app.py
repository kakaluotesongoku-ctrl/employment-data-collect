import os
import pytest

os.environ["TESTING"] = "1"

from app import app, db, EmploymentRecord  # noqa: E402


@pytest.fixture()
def client():
    app.config["TESTING"] = True
    app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///:memory:"
    app.config["WTF_CSRF_ENABLED"] = False

    with app.app_context():
        db.create_all()
        yield app.test_client()
        db.session.remove()
        db.drop_all()


def _add_record(client, overrides=None):
    data = {
        "name": "张三",
        "company": "测试科技有限公司",
        "position": "软件工程师",
        "industry": "IT/互联网",
        "location": "北京",
        "salary": "15000",
        "employment_type": "全职/Full-time",
        "education": "本科/Bachelor",
        "start_date": "2024-01-15",
    }
    if overrides:
        data.update(overrides)
    return client.post("/add", data=data, follow_redirects=True)


class TestIndex:
    def test_dashboard_loads(self, client):
        resp = client.get("/")
        assert resp.status_code == 200
        assert "就业数据采集系统" in resp.data.decode()

    def test_dashboard_shows_stats(self, client):
        _add_record(client)
        resp = client.get("/")
        assert resp.status_code == 200
        assert b"15,000" in resp.data or b"15000" in resp.data


class TestAddRecord:
    def test_add_form_renders(self, client):
        resp = client.get("/add")
        assert resp.status_code == 200
        assert "添加就业记录" in resp.data.decode()

    def test_create_record_success(self, client):
        resp = _add_record(client)
        assert resp.status_code == 200
        assert "张三" in resp.data.decode()

    def test_create_record_missing_required(self, client):
        resp = client.post("/add", data={"name": ""}, follow_redirects=True)
        assert resp.status_code == 200
        # Should stay on add page with error flash
        assert "required" in resp.data.decode().lower() or "添加" in resp.data.decode()

    def test_create_record_invalid_salary(self, client):
        resp = client.post(
            "/add",
            data={
                "name": "李四",
                "company": "ABC",
                "position": "分析师",
                "industry": "金融/Finance",
                "location": "上海",
                "salary": "not-a-number",
                "employment_type": "全职/Full-time",
                "education": "硕士/Master",
            },
            follow_redirects=True,
        )
        assert resp.status_code == 200
        assert "Salary" in resp.data.decode() or "salary" in resp.data.decode().lower()

    def test_create_record_no_salary(self, client):
        resp = _add_record(client, {"salary": ""})
        assert resp.status_code == 200
        with app.app_context():
            record = EmploymentRecord.query.filter_by(name="张三").first()
            assert record is not None
            assert record.salary is None


class TestListRecords:
    def test_list_page_loads(self, client):
        resp = client.get("/list")
        assert resp.status_code == 200
        assert "数据列表" in resp.data.decode()

    def test_list_shows_record(self, client):
        _add_record(client)
        resp = client.get("/list")
        assert b"\xe5\xbc\xa0\xe4\xb8\x89" in resp.data  # UTF-8 for 张三
        assert b"IT/\xe4\xba\x92\xe8\x81\x94\xe7\xbd\x91" in resp.data  # IT/互联网

    def test_search_filter(self, client):
        _add_record(client)
        _add_record(client, {"name": "王五", "company": "另一家公司"})
        resp = client.get("/list?search=张三")
        assert b"\xe5\xbc\xa0\xe4\xb8\x89" in resp.data  # 张三 present
        assert b"\xe7\x8e\x8b\xe4\xba\x94" not in resp.data  # 王五 absent

    def test_industry_filter(self, client):
        _add_record(client, {"industry": "IT/互联网"})
        _add_record(client, {"name": "赵六", "industry": "金融/Finance"})
        resp = client.get("/list?industry=IT%2F%E4%BA%92%E8%81%94%E7%BD%91")
        assert b"IT/" in resp.data
        assert b"\xe8\xb5\xb5\xe5\x85\xad" not in resp.data  # 赵六 absent


class TestExportCSV:
    def test_export_csv_empty(self, client):
        resp = client.get("/export/csv")
        assert resp.status_code == 200
        assert resp.content_type.startswith("text/csv")
        assert b"Name" in resp.data or b"ID" in resp.data

    def test_export_csv_with_data(self, client):
        _add_record(client)
        resp = client.get("/export/csv")
        assert resp.status_code == 200
        assert resp.content_type.startswith("text/csv")
        content = resp.data.decode("utf-8-sig")
        assert "张三" in content
        assert "测试科技有限公司" in content
        assert "15000" in content

    def test_export_csv_with_filter(self, client):
        _add_record(client, {"name": "张三", "industry": "IT/互联网"})
        _add_record(client, {"name": "王五", "industry": "金融/Finance"})
        resp = client.get("/export/csv?industry=IT%2F%E4%BA%92%E8%81%94%E7%BD%91")
        content = resp.data.decode("utf-8-sig")
        assert "张三" in content
        assert "王五" not in content


class TestDeleteRecord:
    def test_delete_existing_record(self, client):
        _add_record(client)
        with app.app_context():
            record = EmploymentRecord.query.first()
            record_id = record.id

        resp = client.post(f"/delete/{record_id}", follow_redirects=True)
        assert resp.status_code == 200
        with app.app_context():
            assert db.session.get(EmploymentRecord, record_id) is None

    def test_delete_nonexistent_record(self, client):
        resp = client.post("/delete/99999", follow_redirects=True)
        assert resp.status_code == 200
        assert "not found" in resp.data.decode().lower() or "未找到" in resp.data.decode()
