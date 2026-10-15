"""Server-side PDF of the print templates (web/print) for the toolbar's download button.

Headless Chromium loads the template and print_engine.js exactly as the browser preview
does, so Arabic shaping, bidi and the self-hosted Cairo font come from the same text engine
as window.print(), and the PDF keeps real (selectable) text. The page runs on a private
origin with no network: template assets are served from PRINT_WEB_DIR, the engine's
GET /api/v1/print/* data call is dispatched in-process to this app with the caller's token
(so every role, permission and scope check still applies), and every other request is aborted.

The finished file is handed to the browser as a short-lived capability URL rather than as a
fetch() body: download managers such as IDM (common on the center's Windows PCs) strip PDF
bodies out of browser fetches, leaving a 0-byte blob, but they can re-request a URL that
carries its own authorization. Files live in this process's memory, so the API must run as a
single uvicorn process (it does: see server/Dockerfile).
"""

from __future__ import annotations

import asyncio
import logging
import os
import secrets
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit

import httpx
from playwright.async_api import Error as PlaywrightError
from playwright.async_api import Route, async_playwright

from edutrack_api.errors import ApiError

logger = logging.getLogger(__name__)

TEMPLATES = frozenset({
    "receipt", "guardian_card", "excellence_certificate", "student_report", "admin_report",
    "monthly_report", "schedule", "attendance_report", "student_receipt", "lesson_log",
    "statistics_report",
})
_ORIGIN = "http://edutrack-print.invalid"
_STATIC_PREFIX = "/web/print/"
_STATIC_SUFFIXES = {".html", ".css", ".js", ".png", ".jpg", ".svg", ".woff2"}
_TIMEOUT_MS = 20_000
# Each render is a short-lived Chromium (~150 MB); cap them so a burst cannot exhaust the VPS.
_slots = asyncio.Semaphore(2)
DOWNLOAD_TTL_SECONDS = 120
_MAX_DOWNLOADS = 32
_downloads: dict[str, tuple[float, RenderedPdf]] = {}

_WAIT_FOR_ASSETS = """async () => {
  await document.fonts.ready;
  await Promise.all([...document.images].map((img) => img.complete ? null
    : new Promise((done) => { img.onload = img.onerror = done; })));
}"""


@dataclass(frozen=True)
class RenderedPdf:
    template: str
    title: str
    content: bytes


def print_web_dir() -> Path:
    default = Path(__file__).resolve().parents[3] / "web" / "print"
    return Path(os.getenv("PRINT_WEB_DIR") or default).resolve()


def _static_file(root: Path, path: str) -> Path | None:
    target = (root / unquote(path[len(_STATIC_PREFIX):])).resolve()
    if target.is_relative_to(root) and target.suffix.lower() in _STATIC_SUFFIXES and target.is_file():
        return target
    return None


def _api_error(res: httpx.Response) -> ApiError:
    try:
        error = res.json().get("error") or {}
    except (ValueError, AttributeError):
        error = {}
    return ApiError(res.status_code, error.get("code") or "print_failed", error.get("message"))


async def render_pdf(app, template: str, query: str, authorization: str) -> RenderedPdf:
    if template not in TEMPLATES:
        raise ApiError(404, "not_found", "قالب الطباعة غير معروف")
    root = print_web_dir()
    failed: list[httpx.Response] = []

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://edutrack-api") as api:

        async def handle(route: Route) -> None:
            request = route.request
            url = urlsplit(request.url)
            if f"{url.scheme}://{url.netloc}" != _ORIGIN:
                await route.abort()
            elif request.method == "GET" and url.path.startswith("/api/v1/print/"):
                res = await api.get(
                    url.path + (f"?{url.query}" if url.query else ""),
                    headers={"Authorization": authorization, "Accept": "application/json"},
                )
                if res.status_code >= 400:
                    failed.append(res)
                await route.fulfill(
                    status=res.status_code,
                    body=res.content,
                    content_type=res.headers.get("content-type", "application/json"),
                )
            elif url.path.startswith(_STATIC_PREFIX) and (file := _static_file(root, url.path)):
                await route.fulfill(path=file)
            else:
                await route.abort()

        async with _slots:
            try:
                async with async_playwright() as pw:
                    browser = await pw.chromium.launch(channel=os.getenv("PDF_BROWSER_CHANNEL") or None)
                    try:
                        page = await browser.new_page(service_workers="block")
                        page.set_default_timeout(_TIMEOUT_MS)
                        await page.route("**/*", handle)
                        await page.goto(f"{_ORIGIN}/web/print/templates/{template}.html" + (f"?{query}" if query else ""))
                        await page.wait_for_selector(".print-toolbar, .print-error-banner", state="attached")
                        if failed:
                            raise _api_error(failed[0])
                        banner = await page.query_selector(".print-error-banner")
                        if banner is not None:
                            raise ApiError(502, "print_failed", (await banner.inner_text()).strip() or None)
                        await page.evaluate(_WAIT_FOR_ASSETS)
                        content = await page.pdf(print_background=True, prefer_css_page_size=True)
                        return RenderedPdf(template=template, title=await page.title(), content=content)
                    finally:
                        await browser.close()
            except PlaywrightError as exc:
                logger.exception("PDF render failed for %s", template)
                raise ApiError(503, "pdf_unavailable", "تعذر تجهيز ملف PDF حالياً، حاول مرة أخرى") from exc


def content_disposition(doc: RenderedPdf) -> str:
    """ASCII fallback (the template key) plus the Arabic document title per RFC 6266."""
    return f"attachment; filename=\"{doc.template}.pdf\"; filename*=UTF-8''{quote(f'{doc.title}.pdf', safe='')}"


def store_download(doc: RenderedPdf) -> str:
    """Keep a rendered file for DOWNLOAD_TTL_SECONDS behind an unguessable token."""
    now = time.monotonic()
    for token in [t for t, (expires, _) in _downloads.items() if expires <= now]:
        del _downloads[token]
    while len(_downloads) >= _MAX_DOWNLOADS:
        del _downloads[next(iter(_downloads))]
    token = secrets.token_urlsafe(32)
    _downloads[token] = (now + DOWNLOAD_TTL_SECONDS, doc)
    return token


def get_download(token: str) -> RenderedPdf | None:
    """The file behind a token; valid for repeated GETs (a download manager re-requests it) until it expires."""
    entry = _downloads.get(token)
    if entry is None or entry[0] <= time.monotonic():
        return None
    return entry[1]
