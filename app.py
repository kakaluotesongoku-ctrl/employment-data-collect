import csv
import io
import os
from datetime import datetime, date, timezone

from flask import Flask, render_template, request, redirect, url_for, flash, Response
from flask_sqlalchemy import SQLAlchemy

app = Flask(__name__)
app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "change-me-in-production")
app.config["SQLALCHEMY_DATABASE_URI"] = os.environ.get(
    "DATABASE_URL", "sqlite:///employment.db"
)
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False

db = SQLAlchemy(app)


class EmploymentRecord(db.Model):
    __tablename__ = "employment_record"

    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(50), nullable=False)
    company = db.Column(db.String(100), nullable=False)
    position = db.Column(db.String(100), nullable=False)
    industry = db.Column(db.String(50), nullable=False)
    location = db.Column(db.String(50), nullable=False)
    salary = db.Column(db.Integer, nullable=True)
    employment_type = db.Column(db.String(30), nullable=False)
    education = db.Column(db.String(50), nullable=False)
    start_date = db.Column(db.Date, nullable=True)
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))


INDUSTRIES = [
    "IT/互联网",
    "金融/Finance",
    "制造业/Manufacturing",
    "教育/Education",
    "医疗/Healthcare",
    "其他/Other",
]

EMPLOYMENT_TYPES = [
    "全职/Full-time",
    "兼职/Part-time",
    "实习/Internship",
    "自由职业/Freelance",
]

EDUCATION_LEVELS = [
    "本科/Bachelor",
    "硕士/Master",
    "博士/PhD",
    "大专/Associate",
]


def _apply_filters(query):
    """Apply search/filter parameters from the request to a query."""
    search = request.args.get("search", "").strip()
    industry = request.args.get("industry", "").strip()
    emp_type = request.args.get("employment_type", "").strip()

    if search:
        like = f"%{search}%"
        query = query.filter(
            db.or_(
                EmploymentRecord.name.ilike(like),
                EmploymentRecord.company.ilike(like),
                EmploymentRecord.position.ilike(like),
            )
        )
    if industry:
        query = query.filter(EmploymentRecord.industry == industry)
    if emp_type:
        query = query.filter(EmploymentRecord.employment_type == emp_type)

    return query


@app.route("/")
def index():
    total = db.session.query(db.func.count(EmploymentRecord.id)).scalar() or 0
    companies = (
        db.session.query(db.func.count(db.func.distinct(EmploymentRecord.company))).scalar() or 0
    )
    avg_salary_row = db.session.query(db.func.avg(EmploymentRecord.salary)).scalar()
    avg_salary = round(avg_salary_row) if avg_salary_row else 0

    industry_rows = (
        db.session.query(EmploymentRecord.industry, db.func.count(EmploymentRecord.id))
        .group_by(EmploymentRecord.industry)
        .all()
    )
    industry_labels = [r[0] for r in industry_rows]
    industry_counts = [r[1] for r in industry_rows]

    type_rows = (
        db.session.query(EmploymentRecord.employment_type, db.func.count(EmploymentRecord.id))
        .group_by(EmploymentRecord.employment_type)
        .all()
    )

    return render_template(
        "index.html",
        total=total,
        companies=companies,
        avg_salary=avg_salary,
        type_rows=type_rows,
        industry_labels=industry_labels,
        industry_counts=industry_counts,
    )


@app.route("/add", methods=["GET", "POST"])
def add():
    if request.method == "POST":
        name = request.form.get("name", "").strip()
        company = request.form.get("company", "").strip()
        position = request.form.get("position", "").strip()
        industry = request.form.get("industry", "").strip()
        location = request.form.get("location", "").strip()
        salary_raw = request.form.get("salary", "").strip()
        employment_type = request.form.get("employment_type", "").strip()
        education = request.form.get("education", "").strip()
        start_date_raw = request.form.get("start_date", "").strip()

        errors = []
        if not name:
            errors.append("姓名/Name is required.")
        if not company:
            errors.append("公司/Company is required.")
        if not position:
            errors.append("职位/Position is required.")
        if not industry:
            errors.append("行业/Industry is required.")
        if not location:
            errors.append("地点/Location is required.")
        if not employment_type:
            errors.append("就业类型/Employment Type is required.")
        if not education:
            errors.append("学历/Education is required.")

        salary = None
        if salary_raw:
            try:
                salary = int(salary_raw)
                if salary < 0:
                    errors.append("薪资/Salary must be a non-negative number.")
            except ValueError:
                errors.append("薪资/Salary must be a valid integer.")

        start_date = None
        if start_date_raw:
            try:
                start_date = date.fromisoformat(start_date_raw)
            except ValueError:
                errors.append("入职日期/Start Date must be a valid date (YYYY-MM-DD).")

        if errors:
            for err in errors:
                flash(err, "danger")
            return render_template(
                "add.html",
                industries=INDUSTRIES,
                employment_types=EMPLOYMENT_TYPES,
                education_levels=EDUCATION_LEVELS,
                form_data=request.form,
            )

        record = EmploymentRecord(
            name=name,
            company=company,
            position=position,
            industry=industry,
            location=location,
            salary=salary,
            employment_type=employment_type,
            education=education,
            start_date=start_date,
        )
        db.session.add(record)
        db.session.commit()
        flash("记录已成功添加！/ Record added successfully!", "success")
        return redirect(url_for("list_records"))

    return render_template(
        "add.html",
        industries=INDUSTRIES,
        employment_types=EMPLOYMENT_TYPES,
        education_levels=EDUCATION_LEVELS,
        form_data={},
    )


@app.route("/list")
def list_records():
    query = EmploymentRecord.query.order_by(EmploymentRecord.created_at.desc())
    query = _apply_filters(query)
    records = query.all()

    return render_template(
        "list.html",
        records=records,
        industries=INDUSTRIES,
        employment_types=EMPLOYMENT_TYPES,
        search=request.args.get("search", ""),
        selected_industry=request.args.get("industry", ""),
        selected_type=request.args.get("employment_type", ""),
    )


@app.route("/export/csv")
def export_csv():
    query = EmploymentRecord.query.order_by(EmploymentRecord.created_at.desc())
    query = _apply_filters(query)
    records = query.all()

    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow([
        "ID", "姓名/Name", "公司/Company", "职位/Position",
        "行业/Industry", "地点/Location", "薪资/Salary(CNY)",
        "就业类型/Employment Type", "学历/Education",
        "入职日期/Start Date", "创建时间/Created At",
    ])
    for r in records:
        writer.writerow([
            r.id, r.name, r.company, r.position,
            r.industry, r.location, r.salary if r.salary is not None else "",
            r.employment_type, r.education,
            r.start_date.isoformat() if r.start_date else "",
            r.created_at.strftime("%Y-%m-%d %H:%M:%S") if r.created_at else "",
        ])

    output.seek(0)
    filename = f"employment_data_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}.csv"
    return Response(
        "\ufeff" + output.getvalue(),  # BOM for Excel compatibility
        mimetype="text/csv; charset=utf-8",
        headers={"Content-Disposition": f"attachment; filename={filename}"},
    )


@app.route("/delete/<int:record_id>", methods=["POST"])
def delete_record(record_id):
    record = db.session.get(EmploymentRecord, record_id)
    if record is None:
        flash("记录未找到 / Record not found.", "warning")
        return redirect(url_for("list_records"))
    db.session.delete(record)
    db.session.commit()
    flash("记录已删除 / Record deleted.", "success")
    return redirect(url_for("list_records"))


with app.app_context():
    db.create_all()


@app.context_processor
def inject_now():
    return {"now": datetime.now(timezone.utc)}


if __name__ == "__main__":
    debug = os.environ.get("FLASK_DEBUG", "0") == "1"
    app.run(debug=debug)
