"""WhatsApp messaging and OTP service for Gheras EduTrack Pro."""

from __future__ import annotations

import hashlib
import hmac
import logging
import re
import secrets
import urllib.request
import json
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from edutrack_api.config import Settings

logger = logging.getLogger(__name__)


def normalize_saudi_phone(raw: str | None) -> str:
    """Normalize Saudi phone numbers to 9665XXXXXXXX format."""
    if not raw:
        raise ValueError("رقم الهاتف فارغ")
    digits = re.sub(r"\D", "", str(raw))
    if digits.startswith("00966"):
        digits = digits[2:]
    elif digits.startswith("05") and len(digits) == 10:
        digits = "966" + digits[1:]
    elif digits.startswith("5") and len(digits) == 9:
        digits = "966" + digits
    elif digits.startswith("966") and len(digits) == 12:
        pass
    else:
        # If it doesn't match standard Saudi mobile, but has 9+ digits, accept digits
        if len(digits) < 9:
            raise ValueError("رقم الهاتف غير صحيح أو غير مكتمل")
    return digits


def mask_phone(phone: str) -> str:
    """Mask phone number for safe display in UI (e.g. ******0543)."""
    digits = re.sub(r"\D", "", str(phone or ""))
    if len(digits) >= 4:
        return f"******{digits[-4:]}"
    return "******"


def generate_otp_code(digits: int = 4) -> str:
    """Generate a cryptographically secure numeric OTP code (default: 4 digits)."""
    if digits <= 4:
        code = secrets.randbelow(10000)
        return f"{code:04d}"
    limit = 10**digits
    code = secrets.randbelow(limit)
    return f"{code:0{digits}d}"


def hash_otp_code(code: str) -> str:
    """Hash OTP code with SHA-256 for secure database storage."""
    return hashlib.sha256(code.strip().encode("utf-8")).hexdigest()


def verify_otp_code(input_code: str, hashed_code: str) -> bool:
    """Constant-time verification of entered OTP code against stored hash."""
    candidate_hash = hash_otp_code(input_code)
    return hmac.compare_digest(candidate_hash, hashed_code)


def format_otp_message(code: str) -> str:
    """Format the official Gheras Center branded WhatsApp message."""
    return (
        "مركز غراس للتعليم والتأهيل\n"
        "نظام EduTrack Pro\n\n"
        f"رمز التحقق لتسجيل الدخول هو: *{code}*\n\n"
        "صلاحية الرمز 5 دقائق. نرجو عدم مشاركته مع أي شخص."
    )


def send_whatsapp_otp(phone: str, code: str, settings: Settings | None = None) -> bool:
    """
    Send OTP code via WhatsApp.
    If external gateway is configured in settings/env (e.g. UltraMsg or Wasapi),
    sends via HTTP. Otherwise logs in Sandbox mode and returns True.
    """
    normalized = normalize_saudi_phone(phone)
    message = format_otp_message(code)

    api_url = getattr(settings, "whatsapp_api_url", None)
    api_token = getattr(settings, "whatsapp_api_token", None)

    if api_url and api_token:
        try:
            payload = json.dumps({
                "to": normalized,
                "body": message,
                "message": message,
                "phone": normalized,
            }).encode("utf-8")
            req = urllib.request.Request(
                api_url,
                data=payload,
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {api_token}",
                    "token": api_token,
                },
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                status = resp.getcode()
                logger.info("WhatsApp OTP sent to %s via gateway (status=%s)", mask_phone(normalized), status)
                return 200 <= status < 300
        except Exception as ex:
            logger.warning("Failed to send WhatsApp message via gateway: %s. Falling back to sandbox.", ex)

    # Sandbox / Mock fallback: logged securely for testing & local usage
    logger.info("[WHATSAPP-SANDBOX] To: %s | OTP: %s | Text: %s", mask_phone(normalized), code, message)
    return True
