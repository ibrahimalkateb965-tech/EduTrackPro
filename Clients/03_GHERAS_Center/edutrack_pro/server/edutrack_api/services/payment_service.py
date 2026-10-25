from ..errors import ApiError
from ..repositories.payment_repo import PaymentRepository
from .finance import _money, allocate_payment, issue_receipt, default_cash_account_id, post_ledger_entry


class PaymentService:
    """Layer 3: Pure domain business service for payments, fee validations, and settlement flows."""

    @classmethod
    def validate_and_prepare_payment(cls, conn, data: dict, actor: dict | None = None) -> None:
        """
        Validate payment amounts, bus fees, tuition caps, and installment targets
        according to Gheras business rules before inserting into the database.
        """
        bus_amount = _money(data.get("bus_amount") or 0)
        pay_amount = _money(data.get("amount") or 0)

        if pay_amount <= 0:
            raise ApiError(400, "validation_error", "مبلغ الدفعة يجب أن يكون أكبر من الصفر")
        if bus_amount < 0 or bus_amount > pay_amount:
            raise ApiError(400, "validation_error", "مبلغ سداد الباص غير صالح أو يتجاوز المبلغ الإجمالي")

        student_id = data.get("student_id")
        if not student_id:
            raise ApiError(400, "validation_error", "معرف الطالب مطلوب")

        # 1. Bus Subscription Activation / Renewal Flow
        new_bus_fee = data.pop("new_bus_fee", None)
        if new_bus_fee is not None:
            if _money(new_bus_fee) <= 0:
                raise ApiError(400, "validation_error", "رسوم اشتراك الباص المقررة غير صالحة")
            if bus_amount <= 0:
                raise ApiError(400, "validation_error", "يجب سداد دفعة عند تفعيل أو تجديد اشتراك الباص")
            if bus_amount > _money(new_bus_fee):
                raise ApiError(400, "validation_error", "المبلغ المسدد للباص يتجاوز رسوم الاشتراك المقررة")

            st = PaymentRepository.get_student_with_lock(conn, student_id)
            if not st:
                raise ApiError(404, "not_found", "الطالب غير موجود")

            if st.get("has_bus_subscription"):
                if _money(st["bus_fee"]) > _money(st["bus_paid"]):
                    raise ApiError(400, "validation_error", "لا يمكن تجديد أو تفعيل اشتراك جديد قبل سداد الرسوم السابقة المتبقية")
                target_fee = _money(st["bus_fee"]) + _money(new_bus_fee)
            else:
                target_fee = _money(new_bus_fee)

            PaymentRepository.set_bus_subscription_fee(conn, student_id, target_fee)

        # 2. Standard Bus Payment Validation
        if bus_amount > 0:
            st = PaymentRepository.get_student_with_lock(conn, student_id)
            if not st:
                raise ApiError(404, "not_found", "الطالب غير موجود")
            if not st.get("has_bus_subscription"):
                raise ApiError(400, "validation_error", "الطالب غير مشترك في خدمة الباص")
            if _money(st["bus_paid"]) + bus_amount > _money(st["bus_fee"]):
                raise ApiError(400, "validation_error", "المبلغ المسدد للباص يتجاوز المتبقي من رسوم الباص")

        # 3. Tuition Cap Validation (Q3)
        tuition_amount = pay_amount - bus_amount
        if tuition_amount > 0:
            bal_row = PaymentRepository.get_student_balance(conn, student_id)
            if bal_row and bal_row.get("total_planned") is not None and _money(bal_row["total_planned"]) > 0:
                rem_tuition = _money(bal_row["balance"])
                if rem_tuition <= 0:
                    raise ApiError(400, "validation_error", "تم سداد كامل الرسوم الدراسية للطالب مسبقاً")
                if tuition_amount > rem_tuition:
                    raise ApiError(400, "validation_error", f"المبلغ المسدد للدراسة ({tuition_amount}) يتجاوز الرسوم الدراسية المتبقية ({rem_tuition})")

        # 4. Installment Ownership & Target Overflow Check (Q1 & Q2)
        installment_id = data.get("installment_id")
        if installment_id:
            inst = PaymentRepository.get_installment_details(conn, installment_id)
            if not inst:
                raise ApiError(404, "not_found", "القسط المحدد غير موجود")
            if str(inst["student_id"]) != str(student_id):
                raise ApiError(400, "validation_error", "القسط المحدد لا يتبع للطالب المحدد")
            inst_rem = _money(inst["amount"]) - _money(inst["paid_amount"])
            if tuition_amount > inst_rem:
                raise ApiError(400, "validation_error", f"المبلغ المسدد للدراسة ({tuition_amount}) يتجاوز المتبقي من هذا القسط ({inst_rem})")

    @classmethod
    def finalize_payment_settlement(cls, conn, row: dict, actor: dict | None = None) -> dict:
        """
        Finalize post-insertion financial accounting:
        - Increment student's bus_paid if bus portion exists
        - Allocate tuition portion across installments
        - Issue official receipt
        - Post cash ledger entry
        """
        bus_amount = _money(row.get("bus_amount") or 0)
        if bus_amount > 0:
            PaymentRepository.increment_bus_paid(conn, row["student_id"], bus_amount)

        touched = allocate_payment(conn, row, row.get("installment_id"))
        row["installments_touched"] = touched
        issued_by = actor.get("id") if actor else None
        row["receipt"] = issue_receipt(conn, row["id"], issued_by=issued_by)

        cash_acc_id = row.get("account_id") or default_cash_account_id(conn, row["branch_id"])
        post_ledger_entry(
            conn,
            cash_acc_id,
            "in",
            row["amount"],
            "payments",
            row["id"],
            row["paid_on"],
            row["branch_id"],
        )
        return row
