"""POST /api/v1/print/pdf/{template} + GET /api/v1/print/pdf/download/{token}: the print toolbar's PDF download.

These tests launch headless Chromium (`playwright install --only-shell chromium`, or set
PDF_BROWSER_CHANNEL=msedge to use the installed Edge).
"""

from __future__ import annotations

import io
import unicodedata
from urllib.parse import unquote

from pypdf import PdfReader

from edutrack_api.services import pdf
from tests.conftest import login
from tests.test_print import _seed_basic_data

# A5 landscape in PDF points (210 mm x 148 mm).
A5_LANDSCAPE = (595.3, 419.5)


def _text(reader: PdfReader) -> str:
    """Page text with whitespace collapsed to single spaces."""
    return " ".join(unicodedata.normalize("NFKC", reader.pages[0].extract_text()).split())


def _render(client, path: str, headers: dict) -> dict:
    res = client.post(path, headers=headers)
    assert res.status_code == 200, res.text
    return res.json()


def _pdf(res) -> PdfReader:
    assert res.status_code == 200, res.text
    assert res.headers["content-type"] == "application/pdf"
    assert res.content.startswith(b"%PDF-")
    return PdfReader(io.BytesIO(res.content))


def test_receipt_pdf_is_one_a5_landscape_page_with_real_arabic_text(client, db, manager):
    data = _seed_basic_data(db)
    body = _render(client, f"/api/v1/print/pdf/receipt?payment={data['payment_id']}", manager)
    filename = f"سند_قبض_{data['receipt_no']}_عمر إبراهيم الكاتب.pdf"
    assert body["filename"] == filename
    assert body["expires_in"] == pdf.DOWNLOAD_TTL_SECONDS
    assert body["url"].startswith("/api/v1/print/pdf/download/")

    # The download URL needs no Authorization header: a download manager re-requests it on its own.
    res = client.get(body["url"])
    reader = _pdf(res)

    assert len(reader.pages) == 1
    box = reader.pages[0].mediabox
    assert abs(float(box.width) - A5_LANDSCAPE[0]) < 2 and abs(float(box.height) - A5_LANDSCAPE[1]) < 2

    # Vector text, not a raster image: the student's name and the amount in words are extractable,
    # with the word spaces html2canvas used to drop.
    text = _text(reader)
    assert "عمر إبراهيم الكاتب" in text
    assert "خمسمائة ريال فقط لا غير" in text
    assert "سند قبض" in text
    assert "من غراس تبدأ المعرفة وتصنع القمة" in text

    # The self-hosted static Cairo is embedded as a real font (a variable font would become Type3 outlines).
    fonts = [f.get_object() for f in reader.pages[0]["/Resources"]["/Font"].get_object().values()]
    assert all(f["/Subtype"] != "/Type3" for f in fonts), [f["/Subtype"] for f in fonts]
    assert {str(f["/BaseFont"]).split("+")[-1] for f in fonts} <= {"Cairo-Regular", "Cairo-SemiBold", "Cairo-Bold"}

    disposition = res.headers["content-disposition"]
    assert disposition.startswith('attachment; filename="receipt.pdf"')
    assert unquote(disposition.split("filename*=UTF-8''")[1]) == filename
    assert res.headers["cache-control"] == "no-store"


def test_download_url_can_be_fetched_again_until_it_expires(client, db, manager, monkeypatch):
    data = _seed_basic_data(db)
    url = _render(client, f"/api/v1/print/pdf/receipt?id={data['receipt_no']}", manager)["url"]
    first = client.get(url)
    assert _text(_pdf(first)).count("عمر إبراهيم الكاتب") == 1
    assert client.get(url).content == first.content

    now = pdf.time.monotonic()
    monkeypatch.setattr(pdf.time, "monotonic", lambda: now + pdf.DOWNLOAD_TTL_SECONDS + 1)
    expired = client.get(url)
    assert expired.status_code == 404
    assert expired.json()["error"]["message"] == "انتهت صلاحية رابط التنزيل، اضغط زر التنزيل مرة أخرى"


def test_unknown_download_token_is_404(client, db):
    assert client.get("/api/v1/print/pdf/download/not-a-token").status_code == 404


def test_download_store_is_bounded(monkeypatch):
    monkeypatch.setattr(pdf, "_downloads", {})
    doc = pdf.RenderedPdf(template="receipt", title="t", content=b"%PDF-")
    tokens = [pdf.store_download(doc) for _ in range(pdf._MAX_DOWNLOADS + 5)]
    assert len(pdf._downloads) == pdf._MAX_DOWNLOADS
    assert pdf.get_download(tokens[0]) is None  # oldest evicted first
    assert pdf.get_download(tokens[-1]) is doc


def test_a4_template_pdf(client, db, manager):
    data = _seed_basic_data(db)
    url = _render(client, f"/api/v1/print/pdf/student_report?id={data['student_id']}", manager)["url"]
    reader = _pdf(client.get(url))
    box = reader.pages[0].mediabox
    assert abs(float(box.width) - 595.3) < 2 and abs(float(box.height) - 841.9) < 2  # A4 portrait
    assert "عمر إبراهيم الكاتب" in _text(reader)


def test_pdf_requires_bearer_token(client, db):
    res = client.post("/api/v1/print/pdf/receipt?payment=1")
    assert res.status_code == 401
    assert res.json()["error"]["code"] == "unauthorized"


def test_pdf_rejects_invalid_token(client, db):
    res = client.post("/api/v1/print/pdf/receipt?payment=1", headers={"Authorization": "Bearer not-a-jwt"})
    assert res.status_code == 401


def test_pdf_enforces_the_template_permission(client, db, manager):
    data = _seed_basic_data(db)
    client.post(
        "/api/v1/users",
        json={
            "username": "sup_pdf_nofin",
            "password": "Password123!",
            "role": "supervisor",
            "permissions": {"students": True, "attendance": True, "finance": False},
        },
        headers=manager,
    )
    sup = login(client, "sup_pdf_nofin", "Password123!")
    res = client.post(f"/api/v1/print/pdf/receipt?payment={data['payment_id']}", headers=sup)
    assert res.status_code == 403
    assert res.json()["error"]["message"] == "طباعة سند القبض تتطلب صلاحية المالية"


def test_pdf_passes_through_not_found(client, db, manager):
    res = client.post("/api/v1/print/pdf/receipt?id=999999", headers=manager)
    assert res.status_code == 404
    assert res.json()["error"]["message"] == "السند غير موجود"


def test_pdf_unknown_template_is_404(client, db, manager):
    for template in ("nope", "..%2F..%2Fserver%2Fpyproject", "NOTES"):
        res = client.post(f"/api/v1/print/pdf/{template}", headers=manager)
        assert res.status_code == 404, template
