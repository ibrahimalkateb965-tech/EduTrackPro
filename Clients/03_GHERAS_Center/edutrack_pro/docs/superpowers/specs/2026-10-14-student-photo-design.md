<div dir="rtl">

# مواصفة: صورة الطالب الشخصية (المرحلة 1: الخادم + لوحة التحكم + الطباعة)

- **التاريخ:** 2026-10-14
- **الحالة:** بانتظار مراجعة إبراهيم
- **المسار:** معماري (المرحلة 2 <bdi>Android</bdi> مواصفة مستقلة لاحقًا)

## 1. الهدف

تُرفع صورة الطالب مرة واحدة عند إضافته (أو تعديله) من لوحة التحكم، ثم تظهر تلقائيًا في كل مستند يحتاجها دون إعادة إدراج: بطاقة ولي الأمر، تقرير الطالب، شهادة التميز. المرحلة 2 تعرضها في تطبيق <bdi>Android</bdi>.

**معيار النجاح:** إضافة طالب جديد مع صورة، ثم فتح بطاقة ولي الأمر وتقرير الطالب وشهادة التميز ← الصورة نفسها تظهر في الثلاثة دون أي خطوة إضافية. الطالب بلا صورة ← يبقى المربع المتقطع الحالي.

## 2. الوضع الحالي

| العنصر | الحالة |
| :--- | :--- |
| عمود <bdi>`students.photo_url`</bdi> | موجود (<bdi>`012_enterprise_expansion.sql`</bdi>) ويُحفظ عبر <bdi>CRUD</bdi> العام |
| نموذج الإضافة | حقل نصي لرابط فقط (<bdi>`web/dashboard/js/views/students.js:446`</bdi>) |
| بطاقة ولي الأمر | <bdi>`.photo-box`</bdi> ثابت بنص «صورة الطالب»، و<bdi>`/print/guardian-card`</bdi> لا يُرجع أي صورة |
| رفع الملفات | <bdi>`POST /me/uploads`</bdi> للمعلم فقط، ويحفظ في مجلد عام <bdi>`/api/v1/static/uploads`</bdi> بلا مصادقة |

## 3. القرارات

1. **التخزين (الخيار أ):** مجلد خاص <bdi>`STUDENT_PHOTO_DIR`</bdi> (الافتراضي <bdi>`student_photos`</bdi>) **خارج** <bdi>`UPLOAD_DIR`</bdi> ولا يُركَّب كملفات ثابتة. صور القاصرين لا تُخدَم إلا بعد المصادقة.
2. **الطباعة:** الخادم يضمّن الصورة داخل حمولة الطباعة كـ <bdi>`data:image/...;base64`</bdi>، فلا يحتاج وسم <bdi>`<img>`</bdi> إلى ترويسة مصادقة.
3. **تقسيم المراحل:** المرحلة 1 = الخادم + الويب + 3 قوالب طباعة. المرحلة 2 = <bdi>Android</bdi> (<bdi>DTO</bdi> + هجرة <bdi>Room v3→v4</bdi> + <bdi>Coil</bdi> بترويسة <bdi>Authorization</bdi>).

## 4. الخادم

### 4.1 وحدة جديدة: <bdi>`edutrack_api/services/student_photos.py`</bdi>

مسؤولية واحدة: قراءة/كتابة/حذف ملف الصورة والتحقق منه.

- <bdi>`detect_image_type(data: bytes) -> str | None`</bdi>: يتعرف على النوع من البايتات الأولى (<bdi>JPEG</bdi>: <bdi>`FF D8 FF`</bdi>، <bdi>PNG</bdi>: <bdi>`89 50 4E 47 0D 0A 1A 0A`</bdi>). أي شيء آخر ← <bdi>`None`</bdi>.
- <bdi>`save_photo(student_id, data) -> str`</bdi>: يكتب <bdi>`{student_id}_{uuid4.hex}.{jpg|png}`</bdi> في المجلد الخاص، ويحذف أي ملف سابق لنفس الطالب، ويُرجع رمز الإصدار (أول 8 أحرف من <bdi>uuid</bdi>).
- <bdi>`find_photo(student_id) -> Path | None`</bdi> و <bdi>`delete_photo(student_id) -> None`</bdi>.
- <bdi>`photo_data_uri(student_id) -> str`</bdi>: يُرجع <bdi>data URI</bdi> أو سلسلة فارغة إن لم توجد صورة.

### 4.2 راوتر جديد: <bdi>`edutrack_api/routers/student_photos.py`</bdi>

يُسجَّل في <bdi>`main.py`</bdi> **قبل** <bdi>`crud.router`</bdi>.

| الطريقة | المسار | الصلاحية | السلوك |
| :--- | :--- | :--- | :--- |
| <bdi>`POST`</bdi> | <bdi>`/students/{id}/photo`</bdi> | مدير، أو مشرف بصلاحية <bdi>`students`</bdi> | جسم خام (<bdi>raw body</bdi>) مثل <bdi>`/me/uploads`</bdi>. حد 2 ميجابايت (فحص <bdi>`Content-Length`</bdi> ثم أثناء القراءة). النوع من البايتات لا من الاسم. يحدّث <bdi>`photo_url`</bdi> و <bdi>`updated_at`</bdi>. يُرجع <bdi>`{"photo_url": ...}`</bdi> |
| <bdi>`GET`</bdi> | <bdi>`/students/{id}/photo`</bdi> | مدير؛ مشرف بصلاحية <bdi>`students`</bdi>؛ معلم/ولي أمر/طالب إن كان <bdi>id</bdi> ضمن <bdi>`Scope.student_ids`</bdi> | يُرجع الملف بـ <bdi>`Content-Type`</bdi> الصحيح و <bdi>`Cache-Control: private, max-age=86400`</bdi>. 404 إن لم توجد صورة |
| <bdi>`DELETE`</bdi> | <bdi>`/students/{id}/photo`</bdi> | مثل <bdi>`POST`</bdi> | يحذف الملف ويضع <bdi>`photo_url = NULL`</bdi>. 204 |

- الطالب المحذوف (<bdi>`deleted_at IS NOT NULL`</bdi>) أو غير الموجود ← 404.
- رسائل الخطأ عربية عبر <bdi>`ApiError`</bdi> كالمعتاد (422 نوع غير مسموح، 413 حجم زائد).
- كل رفع/حذف يُسجَّل عبر <bdi>`write_audit(conn, user_id, "student_photo_set" | "student_photo_delete", "students", id, {})`</bdi>.

### 4.3 قيمة <bdi>`photo_url`</bdi>

- الخادم وحده يكتبها: <bdi>`/api/v1/students/{id}/photo?v={version}`</bdi>. يتغير <bdi>`v`</bdi> مع كل صورة جديدة لكسر الكاش (يحتاجه <bdi>Coil</bdi> في المرحلة 2).
- في <bdi>`crud.py`</bdi> يُحذف <bdi>`photo_url`</bdi> من بيانات <bdi>POST/PATCH</bdi> العامة لجدول <bdi>`students`</bdi> بصمت، فلا يمكن حقن رابط خارجي.
- **القيم القديمة:** أي <bdi>`photo_url`</bdi> لا يبدأ بـ <bdi>`/api/v1/students/`</bdi> يُعامَل كأنه بلا صورة في الطباعة. قبل النشر يُفحص الإنتاج: <bdi>`SELECT count(*) FROM students WHERE photo_url IS NOT NULL`</bdi>؛ إن وُجدت قيم يُبلَّغ إبراهيم قبل أي تنظيف.

### 4.4 الطباعة

<bdi>`routers/print.py`</bdi>: نقاط <bdi>`guardian-card`</bdi> و <bdi>`student-report`</bdi> و <bdi>`certificate`</bdi> تضيف <bdi>`photo_data_uri`</bdi> (فارغ إن لم توجد صورة). في الشهادة يُؤخذ الطالب من <bdi>`certificates.student_id`</bdi> عند الطباعة بمعرف الشهادة، أو من <bdi>`student_id`</bdi> مباشرة.

القوالب الثلاثة تستخدم أقسام <bdi>Mustache</bdi> المدعومة حاليًا في <bdi>`print_engine.js`</bdi>:

```html
{{#photo_data_uri}}<img class="photo-img" src="{{photo_data_uri}}" alt="صورة الطالب">{{/photo_data_uri}}
{{^photo_data_uri}}<div class="photo-box">صورة الطالب</div>{{/photo_data_uri}}
```

> ملاحظة: <bdi>`{{#key}}`</bdi> في المحرك الحالي يعيد سلسلة فارغة لغير المصفوفات. يُضاف دعم القيمة الصادقة غير المصفوفة (تُعرض مرة واحدة) كتعديل صغير في <bdi>`renderMustache`</bdi>. القيمة تمر عبر <bdi>`escapeHtml`</bdi>، و <bdi>data URI</bdi> بصيغة <bdi>base64</bdi> لا تتأثر بالتهريب.

<bdi>`print.css`</bdi>: <bdi>`.photo-img`</bdi> بنفس أبعاد <bdi>`.photo-box`</bdi> (30×40 مم) مع <bdi>`object-fit: cover`</bdi>. موضع الصورة في التقرير والشهادة يُحدد عند قراءة القالبين في الخطة، بنفس الأبعاد.

## 5. لوحة التحكم (الويب)

في <bdi>`views/students.js`</bdi>:

- يُستبدل الحقل النصي بمكوّن: معاينة (الصورة الحالية أو المربع الفارغ) + زر «اختيار صورة» (<bdi>`<input type="file" accept="image/jpeg,image/png">`</bdi>) + زر «حذف الصورة» عند التعديل.
- **التصغير في المتصفح:** <bdi>canvas</bdi> إلى ضلع أقصى 600 بكسل، ثم <bdi>`toBlob('image/jpeg', 0.85)`</bdi>. بلا مكتبات جديدة.
- **عرض الصورة الحالية:** <bdi>`fetch`</bdi> بترويسة <bdi>Bearer</bdi> ← <bdi>`URL.createObjectURL`</bdi>، مع <bdi>`revokeObjectURL`</bdi> عند إغلاق النافذة.
- **ترتيب الحفظ عند الإضافة:** إنشاء الطالب أولًا ← رفع الصورة بالمعرف الجديد. فشل الرفع لا يلغي الطالب: رسالة «تم حفظ الطالب، لكن تعذر رفع الصورة. أعد المحاولة من نافذة التعديل».
- **عند التعديل:** الصورة تُرفع/تُحذف فقط إن غيّرها المستخدم.
- في <bdi>`api.js`</bdi> تُضاف دالة رفع ثنائي (الدالة الحالية تُحوّل الجسم إلى <bdi>JSON</bdi> دائمًا).

## 6. خارج النطاق (المرحلة 1)

- <bdi>Android</bdi> بالكامل (المرحلة 2).
- صورة مصغرة في قائمة الطلاب بلوحة التحكم (لم تُطلب).
- قص الصورة يدويًا، أو رفع صور جماعي من <bdi>Excel</bdi>.

## 7. الاختبارات

**الخادم (<bdi>pytest</bdi> على قاعدة <bdi>pgserver</bdi> المضمنة):**

1. رفع <bdi>JPEG</bdi> صالح ← 200، يتحدث <bdi>`photo_url`</bdi> بصيغة <bdi>`?v=`</bdi>، والملف موجود في المجلد الخاص.
2. ملف <bdi>`.jpg`</bdi> محتواه نص/<bdi>HTML</bdi> ← 422.
3. حجم أكبر من 2 ميجابايت ← 413 ولا يبقى ملف.
4. الاستبدال يحذف الملف القديم (ملف واحد فقط للطالب).
5. <bdi>`GET`</bdi>: ولي أمر الطالب ← 200؛ ولي أمر طالب آخر ← 403؛ معلم خارج القاعة ← 403؛ مشرف بلا صلاحية <bdi>`students`</bdi> ← 403.
6. <bdi>`DELETE`</bdi> ← 204 و <bdi>`photo_url`</bdi> فارغ و <bdi>`GET`</bdi> ← 404.
7. <bdi>`PATCH /students/{id}`</bdi> بقيمة <bdi>`photo_url`</bdi> خارجية ← تُتجاهل.
8. <bdi>`/print/guardian-card`</bdi> و <bdi>`/print/student-report`</bdi> و <bdi>`/print/certificate`</bdi> تُرجع <bdi>`photo_data_uri`</bdi> يبدأ بـ <bdi>`data:image/jpeg;base64,`</bdi>، وفارغًا بلا صورة.
9. الملف غير متاح عبر <bdi>`/api/v1/static/uploads/`</bdi>.

**الويب (<bdi>e2e</bdi> محلي):** إضافة طالب مع صورة ← فتح البطاقة ← وجود <bdi>`img.photo-img`</bdi> بمصدر <bdi>`data:`</bdi>. ثم إعادة تشغيل الحزمة الكاملة للخادم قبل حكم <bdi>[APPROVED]</bdi>.

## 8. النشر

- متغير <bdi>`STUDENT_PHOTO_DIR=/app/student_photos`</bdi> مع <bdi>volume</bdi> دائم جديد <bdi>`gheras_student_photos`</bdi> في <bdi>`deploy/docker-compose.prod.yml`</bdi> (بجانب <bdi>`gheras_uploads`</bdi>)، وإلا تضيع الصور عند إعادة بناء الحاوية.
- <bdi>`deploy/backup.sh`</bdi> يضم المجلد الجديد إن كان ينسخ <bdi>`gheras_uploads`</bdi>.
- لا هجرة قاعدة بيانات (العمود موجود).

</div>
