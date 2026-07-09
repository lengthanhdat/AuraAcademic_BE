# Ke hoach Thiet ke lai Mau Email He thong - AuraAcademic

## Tong quan

**Muc tieu:** Thiet ke lai toan bo 4 mau email gui toi nguoi dung theo phong cach Light Theme toi gian - nen trang, chu den/xam, vien mong, nut bam phang - dam bao day du thong tin, chuyen nghiep, tuong thich tot voi moi email client (Gmail, Outlook, Apple Mail).

**Loai du an:** BACKEND (Spring Boot + Thymeleaf HTML Template)

**Pham vi thay doi:**
- Xoa toan bo Dark Theme trong file HTML Thymeleaf hien co.
- Trich xuat 2 template viet inline trong EmailService.java ra file .html rieng.
- Thong nhat layout 1 cot, toi gian, de doc tren ca desktop & mobile.
- Bo sung thong tin footer day du: email ho tro, hotline, dieu khoan dich vu.

---

## Tieu chi thanh cong

1. 4 mau email deu su dung Light Theme nhat quan: nen #ffffff, vien #e2e8f0, chu #1e293b.
2. 0 inline CSS phuc tap - chi su dung style="" don gian, duoc email client ho tro rong rai.
3. 2 mau moi (2fa-otp.html, security-alert.html) duoc tao thanh Thymeleaf template.
4. Footer day du tren tat ca mau: ten he thong, email ho tro, hotline, ban quyen.
5. Du an backend bien dich thanh cong sau khi chinh sua.
6. Khong can Google Fonts - dung font-stack system an toan.

---

## Tech Stack

| Thanh phan | Cong nghe | Ly do |
|---|---|---|
| Template Engine | Thymeleaf 3.x | Da tich hop trong Spring Boot |
| Styling | Inline CSS (table-based layout) | Tuong thich moi email client |
| Font | Arial, Helvetica, sans-serif | An toan, khong phu thuoc CDN |
| Mau chu dao | #0C2E5E (xanh Navy doanh nghiep) | Mau thuong hieu AuraAcademic |

---

## Cac file bi anh huong

```
AuraAcademic_BE/
+-- src/main/
    +-- resources/templates/
    |   +-- email-verification.html      <- Thiet ke lai (hien co)
    |   +-- password-reset.html          <- Thiet ke lai (hien co)
    |   +-- 2fa-otp.html                 <- Tao moi (trich tu EmailService.java)
    |   +-- security-alert.html          <- Tao moi (trich tu EmailService.java)
    +-- java/com/auracademic/backend/
        +-- service/EmailService.java    <- Cap nhat 2 phuong thuc
```

---

## Thiet ke tham chieu (Design Reference)

### Layout chuan

```
+------------------------------------------+
|  [Header]  AuraAcademic  |  Tieu de      | <- Nen trang, border-bottom #e2e8f0
+------------------------------------------+
|                                          |
|  Xin chao, [Ten nguoi dung]!             |
|                                          |
|  [Noi dung chinh]                        |
|                                          |
|  [Khoi hanh dong - OTP / Button]         |
|                                          |
|  [Luu y / Canh bao]                      |
|                                          |
|  Tran trong,                             |
|  Doi ngu AuraAcademic                    |
+------------------------------------------+
|  [Footer] support@  |  Hotline  |  TOS   | <- Nen #f8fafc, chu #64748b
+------------------------------------------+
```

### Bang mau Light Theme

| Token | Mau | Su dung |
|---|---|---|
| Background | #ffffff | Nen email chinh |
| Surface | #f8fafc | Header, Footer |
| Border | #e2e8f0 | Vien bao quanh |
| Text Primary | #1e293b | Tieu de, chu chinh |
| Text Secondary | #64748b | Mo ta, chu thich |
| Brand | #0C2E5E | Ten thuong hieu, nut bam |
| Accent | #0ea5e9 | Lien ket, highlight nhe |
| Warning | #b45309 | Canh bao |
| OTP Box | #f1f5f9 | Khung hien thi ma OTP |

---

## Chi tiet cac Task trien khai

### Task 1 (P0): Thiet ke lai email-verification.html

- **Mo ta:** Thay toan bo Dark Theme bang Light Theme. Giu nguyen bien Thymeleaf ${name}, ${otp}, ${appName}.
- **Agent:** backend-specialist
- **INPUT:** templates/email-verification.html (hien co - Dark Theme)
- **OUTPUT:** File HTML moi - Light Theme, khung OTP noi bat, footer day du.
- **VERIFY:** Mo file HTML trong trinh duyet, kiem tra layout 1 cot, khong thay mau nen toi.

### Task 2 (P0): Thiet ke lai password-reset.html

- **Mo ta:** Thay toan bo Dark Theme bang Light Theme. Giu nguyen bien ${name}, ${resetUrl}, ${appName}.
- **Agent:** backend-specialist
- **INPUT:** templates/password-reset.html (hien co - Dark Theme)
- **OUTPUT:** File HTML moi - nut "Dat Lai Mat Khau" dang phang mau Brand, link du phong ro rang, footer.
- **VERIFY:** Mo file HTML trong trinh duyet, kiem tra nut bam, link fallback hien thi dung.

### Task 3 (P1): Tao moi 2fa-otp.html (Thymeleaf Template)

- **Mo ta:** Trich xuat HTML inline cua sendTwoFactorOtpEmail() trong EmailService.java ra thanh file template rieng.
- **Bien Thymeleaf:** ${name}, ${otp}, ${ttlMinutes}
- **Agent:** backend-specialist
- **INPUT:** Noi dung inline HTML trong EmailService.java (lines 99-106)
- **OUTPUT:** File templates/2fa-otp.html moi voi Light Theme, khung OTP, footer.
- **VERIFY:** File ton tai, khong co loi cu phap HTML.

### Task 4 (P1): Tao moi security-alert.html (Thymeleaf Template)

- **Mo ta:** Trich xuat HTML inline cua sendSecurityAlertEmail() ra thanh file template rieng.
- **Bien Thymeleaf:** ${name}, ${ip}, ${device}, ${loginTime}
- **Agent:** backend-specialist
- **INPUT:** Noi dung inline HTML trong EmailService.java (lines 76-87)
- **OUTPUT:** File templates/security-alert.html moi voi Light Theme, bang thong tin chi tiet (IP, thiet bi, thoi gian), canh bao noi bat, footer.
- **VERIFY:** File ton tai, thong tin day du, khong co loi cu phap HTML.

### Task 5 (P2): Cap nhat EmailService.java

- **Mo ta:** Cap nhat 2 phuong thuc de goi templateEngine.process() thay vi HTML inline.
- **Agent:** backend-specialist
- **Bien Context cho sendTwoFactorOtpEmail:**
  - name -> fullName
  - otp -> otp
  - ttlMinutes -> ttlMinutes
- **Bien Context cho sendSecurityAlertEmail:**
  - name -> fullName
  - ip -> ipAddress
  - device -> userAgent
  - loginTime -> formatted string
- **INPUT:** EmailService.java hien tai
- **OUTPUT:** EmailService.java khong con HTML inline, goi templateEngine.process("2fa-otp", ctx) va templateEngine.process("security-alert", ctx)
- **VERIFY:** Chay ./mvnw compile - khong co loi bien dich.

---

## Phase X: Quy trinh Nghiem thu & Xac minh

### 1. Kiem tra bien dich
```
./mvnw compile
```
Ket qua mong doi: BUILD SUCCESS

### 2. Kiem tra HTML visual
- Mo tung file .html trong trinh duyet
- Kiem tra: khong co mau nen toi, layout 1 cot gon gang, OTP/nut bam noi bat

### 3. Checklist per template

| Mau | Ten nguoi dung | Noi dung chinh | Khoi hanh dong | Footer |
|---|---|---|---|---|
| email-verification | ok | Mo ta OTP | Khung OTP 6 chu so | ok |
| password-reset | ok | Mo ta dat lai | Nut + Link fallback | ok |
| 2fa-otp | ok | Mo ta OTP | Khung OTP + TTL | ok |
| security-alert | ok | Canh bao | Bang IP/thiet bi/thoi gian | ok |

---

## PHASE X COMPLETE (Se cap nhat sau khi hoan thanh)

- [x] Task 1 - email-verification.html redesigned
- [x] Task 2 - password-reset.html redesigned
- [x] Task 3 - 2fa-otp.html created
- [x] Task 4 - security-alert.html created
- [x] Task 5 - EmailService.java updated
- [x] Compile: ./mvnw compile -> BUILD SUCCESS
- [x] Visual check: 4 templates OK in browser
- Date: 05/06/2026 13:57

