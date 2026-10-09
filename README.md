# Call Agent

ثبت تماس‌های تلفنی کارشناس‌ها و تبدیل صحبت‌ها به متن فارسی با Whisper.
محصول مستقل است و از طریق API به نرم‌افزارهای CRM (مثل APERP) وصل می‌شود.

## اجزا

| بخش | مسیر | وضعیت |
|---|---|---|
| سرور (API + worker تبدیل صدا) | `server/` | نسخه ۰.۱ |
| اپ اندروید جمع‌آوری تماس‌ها | `android/` | شروع نشده |

## راه‌اندازی سرور (ویندوز)

```bash
cd server
py -3.11 -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
copy .env.example .env      # و CALLAGENT_API_KEY را عوض کنید
```

اجرا (دو پنجره‌ی جدا):

```bash
.venv/Scripts/python -m callagent            # API روی پورت 8100
.venv/Scripts/python -m callagent.worker     # تبدیل صدا به متن
```

در اولین اجرای worker، مدل Whisper large-v3 (حدود ۳ گیگ) دانلود می‌شود.
مستندات تعاملی API: `http://localhost:8100/docs`

## API

همه‌ی درخواست‌ها هدر `X-API-Key` می‌خواهند.

- `POST /api/calls` (multipart): `id`، `direction` (`incoming`/`outgoing`)، `phone_number`،
  `started_at` (ISO 8601 با منطقه‌ی زمانی، مثل `2026-10-09T10:30:00+03:30`)، `duration_sec`، `audio` (اختیاری).
  ارسال دوباره‌ی همان `id` تماس تکراری نمی‌سازد.
- `GET /api/calls?phone_number=&status=&limit=&offset=`
- `GET /api/calls/{id}`

وضعیت‌ها: `queued` → `processing` → `done` / `failed`، و `no_audio` برای تماس بدون فایل ضبط.

## تست

```bash
cd server
.venv/Scripts/python -m pytest -q
```
