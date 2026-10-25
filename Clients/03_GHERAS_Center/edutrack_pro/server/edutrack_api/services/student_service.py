from datetime import date
from decimal import Decimal, InvalidOperation
from uuid import UUID

from ..errors import ApiError
from ..repositories.student_repo import StudentRepository, MAIN_BRANCH_ID
from .finance import (
    PAYMENT_METHODS,
    _money,
    allocate_payment,
    issue_receipt,
    default_cash_account_id,
    post_ledger_entry,
    generate_installments,
)


class StudentService:
    """Layer 3: Pure domain business service for student onboarding, guardian synchronization, and fee flows."""

    @classmethod
    def parse_registration_finance(cls, data: dict) -> dict | None:
        """Parse registration-fee plan inputs (request-only keys, never student columns).

        Returns None when no fee applies (fee empty/0), otherwise a dict with:
        fee, paid, installments_count, next_due_date, interval_days.
        Raises 400 validation errors before anything is created.
        """
        method = data.get("payment_method")
        if method not in (None, ""):
            if method not in PAYMENT_METHODS:
                raise ApiError(400, "validation_error", "طريقة الدفع غير مدعومة")
        try:
            fee = _money(data.get("registration_fee"))
            paid = _money(data.get("initial_payment_amount"))
        except InvalidOperation:
            raise ApiError(400, "validation_error", "مبلغ الرسوم أو الدفعة الأولى غير صالح")
        if fee <= 0 and paid <= 0:
            return None
        if paid > fee:
            raise ApiError(400, "validation_error", "المبلغ المسدد أكبر من رسوم التسجيل المقررة")
        installments_count = data.get("installments_count")
        next_due_date = data.get("next_due_date")
        interval_days = data.get("interval_days")
        if paid < fee:
            raw_count = installments_count if installments_count not in (None, "") else 0
            try:
                count_value = int(raw_count)
            except (TypeError, ValueError):
                raise ApiError(400, "validation_error", "يجب تحديد عدد الدفعات المتبقية")
            if count_value < 1:
                raise ApiError(400, "validation_error", "يجب تحديد عدد الدفعات المتبقية")
            if not next_due_date:
                raise ApiError(400, "validation_error", "يجب تحديد تاريخ الدفعة القادمة بعد تاريخ اليوم")
            try:
                due_value = date.fromisoformat(str(next_due_date)[:10])
            except ValueError:
                raise ApiError(400, "validation_error", "يجب تحديد تاريخ الدفعة القادمة بعد تاريخ اليوم")
            if due_value <= date.today():
                raise ApiError(400, "validation_error", "يجب تحديد تاريخ الدفعة القادمة بعد تاريخ اليوم")
            try:
                interval_value = int(interval_days if interval_days not in (None, "") else 30)
            except (TypeError, ValueError):
                raise ApiError(400, "validation_error", "الفاصل بين الدفعات يجب أن يكون 7 أو 14 أو 30 يوماً")
            if interval_value not in (7, 14, 30):
                raise ApiError(400, "validation_error", "الفاصل بين الدفعات يجب أن يكون 7 أو 14 أو 30 يوماً")
            installments_count, next_due_date, interval_days = count_value, due_value, interval_value
        else:
            installments_count, next_due_date, interval_days = None, None, 30
        return {
            "fee": fee,
            "paid": paid,
            "installments_count": installments_count,
            "next_due_date": next_due_date,
            "interval_days": interval_days,
        }

    @classmethod
    def validate_and_prepare_student_create(cls, data: dict, actor: dict | None = None) -> tuple[dict | None, str]:
        """Validate student creation payload including finance actions, allergies, and bus subscription.

        Pops transient keys from data and returns (registration_plan, chosen_method).
        """
        bus_fee_raw = data.get("bus_fee")
        bus_paid_raw = data.get("bus_paid")
        has_finance_action = bool(
            data.get("registration_fee") or data.get("initial_payment_amount") or
            (bus_fee_raw not in (None, "", 0, "0", 0.0)) or
            (bus_paid_raw not in (None, "", 0, "0", 0.0))
        )
        if has_finance_action:
            perms = (actor or {}).get("permissions") or {}
            role = (actor or {}).get("role")
            if role != "manager" and not perms.get("finance", False) and not perms.get("students", False):
                raise ApiError(403, "forbidden", "ليس لديك صلاحية تسجيل العمليات المالية أو شؤون الطلاب")

        # Allergies validation
        has_allergies = data.get("has_allergies")
        if has_allergies is not None:
            has_allergies = bool(has_allergies)
            data["has_allergies"] = has_allergies
            if has_allergies:
                notes = (data.get("allergy_notes") or "").strip()
                if not notes:
                    raise ApiError(400, "validation_error", "يرجى توضيح نوع الحساسية (غذائية أو دوائية)")
                data["allergy_notes"] = notes
            else:
                data["allergy_notes"] = None

        # Bus subscription validation
        has_bus = bool(data.get("has_bus_subscription"))
        data["has_bus_subscription"] = has_bus
        bus_fee = _money(data.get("bus_fee", 0)) if data.get("bus_fee") is not None else Decimal("0.00")
        bus_paid = _money(data.get("bus_paid", 0)) if data.get("bus_paid") is not None else Decimal("0.00")

        if not has_bus:
            if bus_fee > 0 or bus_paid > 0:
                raise ApiError(400, "validation_error", "يجب تفعيل اشتراك الباص قبل إدخال رسومه")
            data["bus_fee"] = Decimal("0.00")
            data["bus_paid"] = Decimal("0.00")
        else:
            if bus_fee < 0 or bus_paid < 0:
                raise ApiError(400, "validation_error", "رسوم اشتراك الباص غير صالحة")
            if bus_paid > bus_fee:
                raise ApiError(400, "validation_error", "المبلغ المسدد للباص أكبر من رسوم الاشتراك")
            data["bus_fee"] = bus_fee
            data["bus_paid"] = bus_paid

        raw_method = data.get("payment_method")
        chosen_method = str(raw_method).strip() if raw_method not in (None, "") else "كاش"
        if chosen_method not in PAYMENT_METHODS:
            raise ApiError(400, "validation_error", "طريقة الدفع غير مدعومة")

        registration_plan = cls.parse_registration_finance(data)
        if registration_plan:
            registration_plan["method"] = chosen_method

        for request_key in ("installments_count", "next_due_date", "interval_days", "payment_method"):
            data.pop(request_key, None)

        return registration_plan, chosen_method

    @classmethod
    def finalize_student_onboarding(cls, conn, row: dict, raw_data: dict, registration_plan: dict | None,
                                    actor: dict | None = None, payment_method: str = "كاش") -> None:
        """Handle post-creation workflows: guardian linking and initial financial settlement."""
        g_phone = raw_data.get("guardian_phone") or raw_data.get("father_phone") or raw_data.get("mother_phone")
        g_name = raw_data.get("father_name") or raw_data.get("mother_name") or f"ولي أمر {raw_data.get('name', '')}"
        g_rel = raw_data.get("guardian_relation") or "ولي الأمر"
        if g_phone:
            branch_id = row.get("branch_id") or MAIN_BRANCH_ID
            gid = StudentRepository.find_or_create_guardian(conn, branch_id, g_name, g_phone, g_rel)
            StudentRepository.link_student_guardian(conn, branch_id, row["id"], gid, is_primary=True)

        # Automatic registration fee / installment plan / bus payment processing
        if registration_plan or (row.get("has_bus_subscription") and _money(row.get("bus_paid", 0)) > 0):
            cls.process_students_finance_flow(conn, row, registration_plan, actor=actor, payment_method=payment_method)

    @classmethod
    def process_students_finance_flow(cls, conn, row: dict, plan: dict | None, actor: dict | None = None,
                                      payment_method: str = "كاش") -> None:
        """Create fee plan, generate installments, and when down payment or bus payment exists,
        issue payment, receipt, and ledger entry on the same connection."""
        bus_paid = _money(row.get("bus_paid", 0)) if row.get("has_bus_subscription") else Decimal("0.00")
        reg_paid = _money(plan["paid"]) if plan else Decimal("0.00")
        total_paid = reg_paid + bus_paid

        if plan:
            count = (1 if plan["paid"] > 0 else 0) + (plan["installments_count"] if plan["paid"] < plan["fee"] else 0)
            plan_row = StudentRepository.create_fee_plan(
                conn, row["branch_id"], row["id"], plan["fee"], count, plan["interval_days"]
            )
            row["fee_plan"] = plan_row
            row["installments"] = generate_installments(
                conn,
                plan_row,
                down_payment=plan["paid"] or None,
                first_due=plan["next_due_date"],
            )

        if total_paid > 0:
            method = (plan.get("method") if plan else None) or payment_method or "كاش"
            issued_by = actor.get("id") if actor else None
            pay_row = StudentRepository.create_student_payment(
                conn, row["branch_id"], row["id"], total_paid, bus_paid, method, "رسوم تسجيل الطالب / دفعة أولى"
            )
            allocate_payment(conn, pay_row, pay_row.get("installment_id"))
            row["initial_payment"] = pay_row
            row["initial_receipt"] = issue_receipt(conn, pay_row["id"], issued_by=issued_by)
            cash_acc = default_cash_account_id(conn, row["branch_id"])
            post_ledger_entry(conn, cash_acc, "in", total_paid, "payments", pay_row["id"], pay_row["paid_on"], row["branch_id"])

    @classmethod
    def validate_and_prepare_student_update(cls, conn, record_id: UUID | str, data: dict, actor: dict | None = None) -> None:
        """Validate student update payload including G4 bus lock, bus fee updates, and allergies notes."""
        if "has_bus_subscription" in data or "bus_fee" in data or "bus_paid" in data:
            curr = StudentRepository.get_student_for_bus_update(conn, record_id)
            if curr:
                raw_has_bus = data.get("has_bus_subscription", curr["has_bus_subscription"])
                if isinstance(raw_has_bus, str):
                    has_bus = raw_has_bus.lower() in ("true", "1")
                else:
                    has_bus = bool(raw_has_bus)

                # G4 Lock: Non-managers cannot cancel bus subscription or lower fees/paid
                if actor and actor.get("role") != "manager":
                    if curr.get("has_bus_subscription") and not has_bus:
                        raise ApiError(403, "forbidden", "إلغاء اشتراك الباص مخصص للمدير العام فقط")
                    if "bus_fee" in data and data["bus_fee"] not in (None, ""):
                        if _money(data["bus_fee"]) < _money(curr.get("bus_fee", 0)):
                            raise ApiError(403, "forbidden", "تعديل أو تخفيض رسوم الباص مخصص للمدير العام فقط")
                    if "bus_paid" in data and data["bus_paid"] not in (None, ""):
                        if _money(data["bus_paid"]) < _money(curr.get("bus_paid", 0)):
                            raise ApiError(403, "forbidden", "تخفيض المبلغ المسدد للباص مخصص للمدير العام فقط")

                data["has_bus_subscription"] = has_bus

                if not has_bus:
                    data["bus_fee"] = Decimal("0.00")
                    data["bus_paid"] = Decimal("0.00")
                else:
                    bus_fee = _money(data["bus_fee"]) if "bus_fee" in data and data["bus_fee"] not in (None, "") else _money(curr["bus_fee"])
                    bus_paid = _money(data["bus_paid"]) if "bus_paid" in data and data["bus_paid"] not in (None, "") else _money(curr["bus_paid"])
                    if bus_fee < 0 or bus_paid < 0:
                        raise ApiError(400, "validation_error", "رسوم اشتراك الباص غير صالحة")
                    if bus_paid > bus_fee:
                        raise ApiError(400, "validation_error", "المبلغ المسدد للباص أكبر من رسوم الاشتراك")
                    data["bus_fee"] = bus_fee
                    data["bus_paid"] = bus_paid

                    old_bus_paid = _money(curr["bus_paid"])
                    if bus_paid > old_bus_paid:
                        diff = bus_paid - old_bus_paid
                        branch_id = curr["branch_id"] or MAIN_BRANCH_ID
                        cash_acc = default_cash_account_id(conn, branch_id)
                        pay_row = StudentRepository.create_student_payment(
                            conn, branch_id, record_id, diff, diff, "كاش", "سداد اشتراك الباص / دفعة إضافية"
                        )
                        issued_by = actor.get("id") if actor else None
                        issue_receipt(conn, pay_row["id"], issued_by=issued_by)
                        post_ledger_entry(conn, cash_acc, "in", diff, "payments", pay_row["id"], pay_row["paid_on"], branch_id)

        if "has_allergies" in data:
            has_allergies = bool(data["has_allergies"])
            data["has_allergies"] = has_allergies
            if has_allergies:
                if "allergy_notes" in data:
                    notes = (data.get("allergy_notes") or "").strip()
                    if not notes:
                        raise ApiError(400, "validation_error", "يرجى توضيح نوع الحساسية (غذائية أو دوائية)")
                    data["allergy_notes"] = notes
                else:
                    curr = StudentRepository.get_student_allergy_state(conn, record_id)
                    if not (curr and (curr["allergy_notes"] or "").strip()):
                        raise ApiError(400, "validation_error", "يرجى توضيح نوع الحساسية (غذائية أو دوائية)")
            else:
                data["allergy_notes"] = None
        elif "allergy_notes" in data:
            notes = (data.get("allergy_notes") or "").strip()
            curr = StudentRepository.get_student_allergy_state(conn, record_id)
            if curr and curr["has_allergies"]:
                if not notes:
                    raise ApiError(400, "validation_error", "يرجى توضيح نوع الحساسية (غذائية أو دوائية)")
                data["allergy_notes"] = notes
            else:
                data["allergy_notes"] = None

    @classmethod
    def finalize_student_update(cls, conn, record_id: UUID | str, row: dict | None, data: dict) -> None:
        """Handle post-update synchronization such as national_id propagation to users table."""
        if row and "national_id" in data:
            StudentRepository.sync_user_national_id(conn, record_id, row.get("national_id"))
