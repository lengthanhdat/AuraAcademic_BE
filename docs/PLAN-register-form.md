# PLAN - Cải tiến Form Đăng Ký Tài Khoản (AuraAcademic)

## 📋 Tổng Quan (Overview)
Form đăng ký tài khoản hiện tại của AuraAcademic mới chỉ ở mức cơ bản (Họ tên, Email, Mật khẩu). Để nâng cao tính an toàn bảo mật và cải thiện trải nghiệm người dùng (UX), kế hoạch này sẽ bổ sung thêm các trường quan trọng (Nhập lại mật khẩu, Đồng ý điều khoản), thực hiện xác thực (validation) chặt chẽ cả ở Client-side và Server-side với độ dài mật khẩu tối thiểu là 6 ký tự, đồng thời định cấu hình luồng chuyển hướng về trang đăng nhập sau khi hoàn thành.

**Loại dự án (Project Type)**: WEB & BACKEND (Full-stack)

---

## 🔄 So Sánh Luồng Đăng Ký (Flow Comparison)

Theo yêu cầu của bạn, dưới đây là so sánh chi tiết giữa việc đăng ký **Theo Quy Trình Xác Thực** và **Không Theo Quy Trình**:

### 1. Theo Quy Trình Xác Thực (Hiện tại của Backend)
*   **Mô tả**: Sau khi điền form đăng ký, tài khoản được tạo ở trạng thái chưa kích hoạt (`verified = false`). Hệ thống tự động gửi một email chứa link/token kích hoạt đến địa chỉ email của người dùng.
*   **Trải nghiệm người dùng**:
    1. Người dùng điền form và nhấn "Đăng ký".
    2. Giao diện hiển thị thông báo: *"Đăng ký thành công. Vui lòng kiểm tra email để xác thực tài khoản."*
    3. Trình duyệt tự động chuyển hướng người dùng về trang **Login**.
    4. Người dùng mở hộp thư, click vào link xác thực.
    5. Trình duyệt hiển thị kích hoạt thành công, người dùng có thể đăng nhập vào hệ thống tại trang **Login**.
*   **Ưu điểm**: Bảo mật cực cao, chống spam tài khoản ảo, đảm bảo email nhập vào là có thật.

### 2. Không Theo Quy Trình (Bỏ qua Xác thực Email)
*   **Mô tả**: Tài khoản được tạo và kích hoạt trực tiếp ở trạng thái hoạt động (`verified = true`). Bỏ qua bước gửi email xác thực.
*   **Trải nghiệm người dùng**:
    1. Người dùng điền form và nhấn "Đăng ký".
    2. Giao diện hiển thị thông báo: *"Đăng ký thành công!"*
    3. Trình duyệt tự động chuyển hướng người dùng về trang **Login**.
    4. Người dùng có thể điền thông tin đăng nhập trực tiếp để vào hệ thống ngay lập tức.
*   **Ưu điểm**: Đăng ký cực nhanh, tiện lợi cho môi trường phát triển (development) hoặc chạy thử nghiệm cục bộ.

---

## 🎯 Tiêu Chí Thành Công (Success Criteria)
1.  Form đăng ký trên Frontend hiển thị đầy đủ thêm trường:
    *   **Nhập lại mật khẩu** (`confirmPassword`)
    *   **Checkbox Đồng ý điều khoản dịch vụ & Chính sách bảo mật** (`agreeTerms`)
2.  Mật khẩu được ràng buộc độ dài **tối thiểu 6 ký tự** ở cả Client-side và Server-side.
3.  Form validate chuẩn: Báo lỗi đỏ ngay trên giao diện nếu mật khẩu dưới 6 ký tự, nhập lại mật khẩu không khớp, hoặc chưa tích chọn đồng ý điều khoản.
4.  Nút "Đăng ký" bị vô hiệu hóa (disabled) hoặc hiển thị trạng thái loading khi đang gửi request.
5.  Sau khi đăng ký thành công, hiển thị Alert/Toast thông báo và tự động chuyển hướng người dùng về trang **Login** sau 2 giây.
6.  Backend validate chặt chẽ trường `password` tối thiểu 6 ký tự và kiểm tra tính hợp lệ của payload.

---

## 🛠️ Công Nghệ Sử Dụng (Tech Stack)
*   **Frontend**: Next.js (App Router), React, Tailwind CSS.
*   **Backend**: Spring Boot, Spring Data MongoDB.
*   **Validation**:
    *   *Frontend*: React State / Native HTML5 Validation hoặc Formik/Zod nếu có sẵn.
    *   *Backend*: `jakarta.validation` (`@Size`, `@NotBlank`).

---

## 📂 Danh Sách Tệp Thay Đổi (File Structure Impact)
```plaintext
C:\AuAc\AuraAcademic\
└── src\app\[locale]\register\page.tsx                   # Frontend: Giao diện form & validate client-side

C:\AuAc\AuraAcademic_BE\
└── src\main\java\com\auracademic\backend\
    ├── dto\RegisterRequest.java                        # Backend: Thêm validate @Size(min=6) cho mật khẩu
    └── service\AuthService.java                        # Backend (tùy chọn): Kiểm tra logic kích hoạt / tạo tài khoản
```

---

## 📝 Phân Rã Công Việc (Task Breakdown)

### 📌 GIAI ĐOẠN 1: BACKEND ENHANCEMENT (P1)
**Người thực hiện**: `backend-specialist` | **Skills**: `clean-code`, `api-patterns`

#### **Task 1: Cập nhật ràng buộc mật khẩu trong `RegisterRequest.java`**
*   **Mô tả**: Sử dụng annotation `@Size(min = 6, message = "Mật khẩu phải có tối thiểu 6 ký tự")` trên thuộc tính `password`.
*   **INPUT**: `c:\AuAc\AuraAcademic_BE\src\main\java\com\auracademic\backend\dto\RegisterRequest.java`
*   **OUTPUT**: File `RegisterRequest.java` đã thêm annotation xác thực hợp lệ.
*   **VERIFY**: Chạy `./mvnw compile` thành công.
*   **Hành động khôi phục**: Git checkout về trạng thái trước đó.

---

### 📌 GIAI ĐOẠN 2: FRONTEND UI & CLIENT VALIDATION (P2)
**Người thực hiện**: `frontend-specialist` | **Skills**: `frontend-design`, `react-best-practices`

#### **Task 2: Cập nhật giao diện Form Đăng ký trong `register/page.tsx`**
*   **Mô tả**: Bổ sung trường input "Nhập lại mật khẩu" và Checkbox "Tôi đồng ý với điều khoản sử dụng và chính sách bảo mật".
*   **INPUT**: `C:\AuAc\AuraAcademic\src\app\[locale]\register\page.tsx`
*   **OUTPUT**: Tệp `page.tsx` mới có chứa 2 phần tử giao diện này với thiết kế Tailwind CSS cao cấp, mượt mà và đồng bộ.
*   **VERIFY**: Chạy thử `npm run dev` và quan sát giao diện trực quan.

#### **Task 3: Cấu hình Validation và logic chuyển hướng trên Frontend**
*   **Mô tả**:
    *   Kiểm tra mật khẩu nhập vào phải `length >= 6`.
    *   Kiểm tra `confirmPassword === password`.
    *   Kiểm tra Checkbox `agreeTerms` đã được tích chọn.
    *   Khi nhấn đăng ký thành công, hiển thị Alert thông báo màu xanh và gọi `setTimeout` chuyển hướng người dùng về `/${locale}/login` sau 2 giây.
*   **INPUT**: `C:\AuAc\AuraAcademic\src\app\[locale]\register\page.tsx`
*   **OUTPUT**: Logic xử lý form đã hoàn thiện với đầy đủ thông báo lỗi trực quan (error state).
*   **VERIFY**: Thử gõ mật khẩu < 6 ký tự hoặc nhập sai password nhập lại, kiểm tra xem giao diện có hiển thị báo lỗi đỏ chuẩn xác không.

---

## 🏁 PHASE X: DANH SÁCH KIỂM TRA SAU CÙNG (Verification Checklist)

### 1. Kiểm tra tĩnh & Quy tắc thiết kế (Manual Check)
- [ ] Không sử dụng mã màu tím/violet bị cấm (Bảo đảm đúng tông màu xanh da trời/xanh dương chủ đạo của AuraAcademic).
- [ ] Mật khẩu tối thiểu là 6 ký tự (Kiểm tra cả 2 phía).
- [ ] Sau khi nhấn Đăng ký thành công, giao diện chuyển hướng về trang `/login` mượt mà.

### 2. Kiểm tra chất lượng code & build
- [ ] Chạy `./mvnw clean compile` phía Backend thành công.
- [ ] Chạy `npm run build` phía Frontend thành công mà không có lỗi TypeScript hay Lint.
- [ ] Đã chạy thử nghiệm thực tế luồng đăng ký trên trình duyệt thành công.

---
## ✅ PHASE X COMPLETE
- Lint: ⬜ Pending
- Security: ⬜ Pending
- Build: ⬜ Pending
- Date: 2026-05-27
