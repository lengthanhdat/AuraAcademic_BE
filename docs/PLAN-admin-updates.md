# Kế hoạch Rà soát và Cập nhật Hệ thống Quản trị (Admin Updates)

Bản kế hoạch này hướng dẫn chi tiết các bước rà soát, đồng bộ tính năng backend mới, nâng cấp bảo mật và tối ưu hóa UI/UX sang chuẩn thiết kế cao cấp cho phân hệ Quản trị viên (Admin) trên hệ thống AuraAcademic.

---

## 📌 Tổng quan dự án (Overview)
- **Mục tiêu**: Nâng cấp phân hệ Admin, loại bỏ hoàn toàn các màu tím/violet (vi phạm quy tắc thiết kế hệ thống), cập nhật trạng thái SMTP/Google Client ID tĩnh từ file cấu hình máy chủ, và thiết lập cơ chế bảo vệ tài khoản admin chống tự xóa, tự khóa hoặc hạ quyền trên cả frontend và backend.
- **Loại dự án**: WEB & BACKEND (Next.js 14 + Spring Boot)

---

## 🎯 Tiêu chí thành công (Success Criteria)
1. **Loại bỏ vi phạm thiết kế**: 100% các class màu tím (`violet`, `purple`) trong thư mục Admin được thay thế bằng các màu cao cấp hơn (như `indigo`, `blue`, `cyan`, `slate`).
2. **Trạng thái chẩn đoán (Health Check)**: Admin có thể xem trực quan trạng thái cấu hình của SMTP (Email) và Google Client ID từ trang cài đặt hệ thống (`/admin/settings`) để hỗ trợ kiểm tra nhanh.
3. **Bảo vệ tài khoản (Self-Action Protection)**:
   - Backend chặn tuyệt đối yêu cầu tự xóa hoặc tự thay đổi trạng thái của chính admin đang đăng nhập.
   - Frontend vô hiệu hóa (disable) các nút Xóa, Khóa, Đổi vai trò trên chính tài khoản của Admin đó.
4. **Trải nghiệm Premium**: Cập nhật các bảng quản trị với các hiệu ứng kính (glassmorphism), vi chuyển động mượt mà, căn lề và sử dụng font chữ hiện đại (Inter/Outfit).
5. **Đảm bảo vận hành**: Hệ thống biên dịch và khởi chạy 100% không lỗi (cả backend Spring Boot và frontend Next.js).

---

## ⚙️ Công nghệ sử dụng (Tech Stack)
- **Backend**: Spring Boot, Java, Spring Security, MongoDB, JavaMailSender.
- **Frontend**: Next.js, React, Tailwind CSS, TypeScript, Material Symbols.

---

## 📁 Danh sách các file bị ảnh hưởng (File Structure)

### Backend (Spring Boot)
- [AdminController.java](file:///c:/AuAc/AuraAcademic_BE/src/main/java/com/auracademic/backend/controller/AdminController.java) - Cập nhật logic API chặn tự xóa/khóa/đổi quyền admin, tạo endpoint chẩn đoán cấu hình hệ thống.

### Frontend (Next.js)
- [AdminSidebar.tsx](file:///c:/AuAc/AuraAcademic/src/components/layout/AdminSidebar.tsx) - Thay thế các style màu tím.
- [AdminHeader.tsx](file:///c:/AuAc/AuraAcademic/src/components/layout/AdminHeader.tsx) - Tối ưu hóa UI/UX header admin.
- [admin/dashboard/page.tsx](file:///c:/AuAc/AuraAcademic/src/app/[locale]/admin/dashboard/page.tsx) - Nâng cấp biểu đồ, giao diện hiển thị, loại bỏ màu tím.
- [admin/settings/page.tsx](file:///c:/AuAc/AuraAcademic/src/app/[locale]/admin/settings/page.tsx) - Hiển thị Health Check SMTP/Google Client ID, loại bỏ màu tím.
- [admin/users/page.tsx](file:///c:/AuAc/AuraAcademic/src/app/[locale]/admin/users/page.tsx) - Cập nhật disable nút thao tác trên chính tài khoản của mình.
- [Các trang khác của Admin (verifications, audit-logs, sessions, ai-tokens, v.v.)] - Rà soát và loại bỏ các class Tailwind màu tím.

---

## 📋 Chi tiết các Task (Task Breakdown)

### 🔹 Task 1: Rà soát & Loại bỏ vi phạm Purple Ban trên Frontend Admin
- **Mô tả**: Thay thế tất cả các class Tailwind và mã màu liên quan đến `violet` hoặc `purple` bằng tông màu `indigo`, `blue`, hoặc `slate`.
- **Người thực hiện**: `frontend-specialist`
- **Skills**: `clean-code`, `frontend-design`
- **Độ ưu tiên**: P0
- **INPUT**: Các file frontend trong thư mục `src/app/[locale]/admin/` và thư mục `src/components/layout/`.
- **OUTPUT**: Các file không còn chứa class `violet` hay `purple`.
- **VERIFY**: Chạy lệnh tìm kiếm `grep` từ khóa `violet` và `purple` trong các thư mục liên quan, kết quả trả về rỗng.

### 🔹 Task 2: Triển khai API kiểm tra cấu hình SMTP & Google Client ID (Backend & Frontend)
- **Mô tả**:
  - **Backend**: Thêm endpoint `/api/admin/settings/health` để kiểm tra:
    - Kết nối với SMTP Mail Server (`spring.mail.host`).
    - Trạng thái cấu hình của Google Client ID (`app.google.client-id` có giá trị không).
  - **Frontend**: Gọi API trên tại màn hình `/admin/settings` và hiển thị trạng thái động (nhãn badge xanh/đỏ hoặc biểu tượng chẩn đoán).
- **Người thực hiện**: `backend-specialist` & `frontend-specialist`
- **Skills**: `api-patterns`, `clean-code`
- **Độ ưu tiên**: P1
- **INPUT**:
  - Backend: Cấu hình mail/oauth trong `application.properties`.
  - Frontend: Giao diện `/admin/settings/page.tsx`.
- **OUTPUT**: Giao diện hiển thị trạng thái "SMTP: Connected" / "Google OAuth: Configured".
- **VERIFY**: Kiểm tra phản hồi API `/api/admin/settings/health` và xác nhận badge hiển thị đúng trạng thái tương ứng.

### 🔹 Task 3: Triển khai Cơ chế chống tự hủy hoại tài khoản của Admin (Self-Action Protection)
- **Mô tả**:
  - **Backend**: Trong `AdminController.java`, cập nhật các phương thức:
    - `deleteUser`: Trả về `400 Bad Request` nếu `userId` trùng với id của Admin đang đăng nhập.
    - Đảm bảo kiểm tra chéo ở cả `toggleLock` và `updateRole` để chặn toàn diện các hành động này.
  - **Frontend**:
    - Trong danh sách người dùng (`/admin/users/page.tsx` và `/admin/dashboard/page.tsx`), kiểm tra xem `user.email === currentUser.email` (hoặc ID).
    - Vô hiệu hóa nút Xóa, Khóa, Đổi vai trò (disabled), đổi màu xám mờ và hiển thị chú thích (tooltip) nếu rê chuột vào.
- **Người thực hiện**: `backend-specialist` & `frontend-specialist`
- **Skills**: `clean-code`, `vulnerability-scanner`
- **Độ ưu tiên**: P0
- **INPUT**: `AdminController.java` và `users/page.tsx`.
- **OUTPUT**: Cơ chế khóa chặn hoàn thiện ở cả hai phía.
- **VERIFY**: Đăng nhập tài khoản Admin, thử xóa hoặc đổi quyền chính mình ở UI xem các nút có bị vô hiệu hóa không. Gửi request trực tiếp đến API xem có bị trả về mã lỗi 400 không.

### 🔹 Task 4: Nâng cấp và Tối ưu hóa UI/UX các màn hình Admin sang chuẩn Premium
- **Mô tả**: Thiết kế lại giao diện Dashboard, Settings, Users theo hướng cao cấp:
  - Thêm hiệu ứng hover, kính mờ (glassmorphism/backdrop-blur).
  - Sử dụng layout căn lề chuẩn xác, hạn chế dùng các viền bảng thô cứng.
  - Micro-animations khi nhấn các nút hành động, thông báo Toast đẹp mắt.
  - Đồng bộ và tối ưu hóa tuyệt đối chế độ tối (Dark Mode).
- **Người thực hiện**: `frontend-specialist`
- **Skills**: `frontend-design`, `clean-code`
- **Độ ưu tiên**: P1
- **INPUT**: Các màn hình chính của Admin.
- **OUTPUT**: Giao diện giao diện Admin hiện đại, bóng bẩy và chuyên nghiệp hơn.
- **VERIFY**: Kiểm tra giao diện trên trình duyệt ở cả chế độ Light và Dark Mode.

---

## 🏁 Phase X: Quy trình nghiệm thu & Xác minh (Verification Suite)

### 1. Kiểm tra tĩnh & Biên dịch (Static Checks)
```bash
# Kiểm tra lỗi cú pháp và types
npm run lint && npx tsc --noEmit (Frontend)
mvn clean compile (Backend)
```

### 2. Quét bảo mật & Thiết kế
- Chạy quét bảo mật backend:
```bash
python .agent/skills/vulnerability-scanner/scripts/security_scan.py .
```
- Đảm bảo tuân thủ **Purple Ban** bằng cách chạy tìm kiếm từ khóa `violet` và `purple` trong mã nguồn.

### 3. Kiểm tra Runtime
- Chạy ứng dụng và tiến hành kiểm tra trên giao diện trình duyệt:
  - Đăng nhập bằng tài khoản Admin.
  - Kiểm tra xem badge trạng thái cấu hình SMTP & Google OAuth có hiển thị chính xác không.
  - Kiểm tra dòng thông tin của tài khoản Admin hiện tại để đảm bảo các nút khóa, xóa, chuyển quyền bị vô hiệu hóa hoàn toàn.
  - Chuyển đổi giữa Light/Dark mode trên Dashboard, trang Cài đặt để đảm bảo độ tương phản màu sắc đạt chuẩn WCAG.

---

## ✅ PHASE X COMPLETE
- Lint: [ ] Chưa thực hiện
- Security: [ ] Chưa thực hiện
- Build: [ ] Chưa thực hiện
- Date: [Chưa hoàn thành]
