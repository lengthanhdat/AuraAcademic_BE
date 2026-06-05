# Kế hoạch Tích hợp Trang Thông báo vào Sidebar & Hệ thống thực tế (Notifications Integration Plan)

Bản kế hoạch này hướng dẫn chi tiết các bước thêm liên kết "Thông báo" vào sidebar cho tất cả các đối tượng người dùng (Admin, Giảng viên, Sinh viên) trên hệ thống AuraAcademic, tạo trang danh sách thông báo thực tế và đồng bộ với API backend (thay vì mock data), đồng thời áp dụng giao diện premium, nhất quán và tuân thủ luật **Purple Ban** (loại bỏ màu tím/violet).

---

## 📌 Tổng quan dự án (Overview)
- **Mục tiêu**: 
  - Thêm mục "Thông báo" kèm số lượng chưa đọc (nếu cần) vào Sidebar của Admin, Giảng viên, Sinh viên.
  - Xây dựng/Cập nhật các trang `/admin/notifications`, `/teacher/notifications`, `/student/notifications` để gọi API thực tế của hệ thống (`/api/notifications`), hỗ trợ tìm kiếm, lọc theo loại, lọc theo trạng thái đọc, đánh dấu đọc, tải thêm trang (pagination), xóa thông báo.
  - Đảm bảo thiết kế cao cấp, đồng bộ, hỗ trợ đa ngôn ngữ (vi/en) và không chứa mã màu tím.
- **Loại dự án**: WEB (Next.js 14)

---

## 🎯 Tiêu chí thành công (Success Criteria)
1. **Tích hợp Sidebar**:
   - Menu "Thông báo" xuất hiện ở vị trí phù hợp trên thanh Sidebar của cả 3 phân hệ: Admin, Teacher, Student.
   - Hỗ trợ đổi màu active đúng trạng thái, hiển thị tooltip khi sidebar thu nhỏ (collapsed).
2. **Kết nối API thực tế**:
   - Không sử dụng `MOCK_NOTIFS` ở bất kỳ trang thông báo nào.
   - Đồng bộ hoàn hảo với backend API: GET `/api/notifications`, PUT `/api/notifications/{id}/read`, PUT `/api/notifications/read-all`, DELETE `/api/notifications/{id}`.
3. **Chức năng tối thiểu trên trang**:
   - Tìm kiếm nội dung thông báo.
   - Lọc theo loại (Hệ thống, Kỳ thi, Lớp học, Tài liệu...) và lọc theo trạng thái (Đã đọc / Chưa đọc).
   - Nút "Đánh dấu tất cả đã đọc" hoạt động tức thì.
   - Tải thêm thông báo bằng nút "Xem thêm" (Pagination).
   - Xóa thông báo khỏi danh sách.
4. **Không vi phạm Purple Ban**: Tất cả các màu nền, icon, viền, shadow cho loại thông báo Kỳ thi (EXAM) sẽ chuyển sang tông màu `indigo`, `blue` thay vì `violet`/`purple`.
5. **Đảm bảo biên dịch**: `npm run build` chạy thành công không có lỗi TypeScript hay Lint.

---

## ⚙️ Công nghệ & API Sử dụng (Tech Stack)
- **Frontend**: Next.js (TypeScript), Tailwind CSS.
- **Backend API**: 
  - `GET /api/notifications?page={page}&limit={limit}&type={type}&readState={readState}&query={query}`
  - `PUT /api/notifications/{id}/read`
  - `PUT /api/notifications/read-all`
  - `DELETE /api/notifications/{id}`

---

## 📁 Các file bị ảnh hưởng (Affected Files)
- `messages/vi.json` & `messages/en.json`: Thêm nhãn dịch thuật cho menu sidebar.
- `src/components/layout/TeacherSidebar.tsx`: Thêm mục "Thông báo" vào menuItems.
- `src/components/layout/StudentSidebar.tsx`: Thêm mục "Thông báo" vào menuItems.
- `src/app/[locale]/admin/notifications/page.tsx`: Cập nhật mã nguồn để kết nối API thực tế.
- `src/app/[locale]/teacher/notifications/page.tsx`: Tạo mới trang thông báo dành cho giảng viên.
- `src/app/[locale]/student/notifications/page.tsx`: Tạo mới trang thông báo dành cho sinh viên.

---

## 📋 Chi tiết các Task triển khai (Task Breakdown)

### 🔹 Task 1: Cập nhật File Dịch thuật (Localization Support)
- **Mô tả**: Thêm nhãn `"notifications"` vào mục dịch thuật menu sidebar để hiển thị đúng ngôn ngữ tiếng Anh và tiếng Việt.
- **Người thực hiện**: `frontend-specialist`
- **Skills**: `i18n-localization`, `clean-code`
- **Độ ưu tiên**: P0
- **INPUT**: `vi.json` và `en.json`.
- **OUTPUT**:
  - `vi.json` có `"notifications": "Thông báo"` trong `"Sidebar.menu"` và `"StudentSidebar.menu"`.
  - `en.json` có `"notifications": "Notifications"` trong `"Sidebar.menu"` and `"StudentSidebar.menu"`.
- **VERIFY**: Xem nội dung 2 file JSON sau khi sửa, đảm bảo cú pháp JSON chuẩn, không có lỗi dấu phẩy hay đóng mở ngoặc.

### 🔹 Task 2: Cập nhật Sidebar cho Giảng viên & Sinh viên
- **Mô tả**: Thêm mục Thông báo (icon: `"notifications"`) vào danh sách menu của `TeacherSidebar.tsx` và `StudentSidebar.tsx`.
- **Người thực hiện**: `frontend-specialist`
- **Skills**: `clean-code`
- **Độ ưu tiên**: P0
- **INPUT**: `TeacherSidebar.tsx` và `StudentSidebar.tsx`.
- **OUTPUT**:
  - `TeacherSidebar.tsx` chứa menu item trỏ đến `/teacher/notifications` với nhãn `t("menu.notifications")` và icon `"notifications"`.
  - `StudentSidebar.tsx` chứa menu item trỏ đến `/student/notifications` với nhãn `t("menu.notifications")` và icon `"notifications"`.
- **VERIFY**: Chạy dev server và kiểm tra giao diện Sidebar của giảng viên và học sinh xem đã xuất hiện mục "Thông báo" hay chưa.

### 🔹 Task 3: Cải tạo trang Thông báo Admin kết nối API thực tế
- **Mô tả**: Sửa file `src/app/[locale]/admin/notifications/page.tsx` từ việc dùng dữ liệu giả sang việc gọi các API thật của hệ thống.
- **Người thực hiện**: `frontend-specialist`
- **Skills**: `nextjs-react-expert`, `clean-code`
- **Độ ưu tiên**: P1
- **INPUT**: `src/app/[locale]/admin/notifications/page.tsx`.
- **OUTPUT**: Trang thông báo Admin gọi API và cho phép lọc, tìm kiếm, đánh dấu đọc, tải thêm trang, xóa thông báo. Không sử dụng màu tím cho bất kỳ loại thông báo nào.
- **VERIFY**: Đăng nhập tài khoản Admin, truy cập `/admin/notifications` kiểm tra xem thông báo có khớp với thông báo ở chuông thông báo (NotificationBell) không.

### 🔹 Task 4: Tạo trang Thông báo cho Giảng viên & Sinh viên
- **Mô tả**: Tạo hai file trang thông báo cho giảng viên `/teacher/notifications/page.tsx` và sinh viên `/student/notifications/page.tsx`. Để tối ưu hóa, ta có thể xây dựng một component dùng chung cho cả 3 đối tượng hoặc tạo các trang tương đương với cấu trúc UI/UX thống nhất.
- **Người thực hiện**: `frontend-specialist`
- **Skills**: `frontend-design`, `clean-code`
- **Độ ưu tiên**: P1
- **INPUT**: Component giao diện thông báo chuẩn API.
- **OUTPUT**:
  - File `src/app/[locale]/teacher/notifications/page.tsx`.
  - File `src/app/[locale]/student/notifications/page.tsx`.
- **VERIFY**: Kiểm tra hoạt động của trang thông báo của cả giảng viên và sinh viên, xác minh việc tải trang, đánh dấu đọc, và xóa thông báo.

---

## 🏁 Phase X: Quy trình Nghiệm thu & Xác minh (Verification Suite)

### 1. Kiểm tra tĩnh & Biên dịch (Static Checks)
```bash
# Chạy lint và typecheck để chắc chắn code frontend không có lỗi biên dịch
npm run lint && npx tsc --noEmit
```

### 2. Kiểm tra thiết kế & Luật Purple Ban (Manual Audit)
- Kiểm tra toàn bộ mã nguồn của các file vừa chỉnh sửa và tạo mới, đảm bảo **không chứa** từ khóa `violet` hoặc `purple`.
- Kiểm tra độ hiển thị của danh sách thông báo trên cả màn hình di động (responsive) và Desktop ở cả hai chế độ Light và Dark Mode.

### 3. Kiểm tra Runtime
- Gửi thử một thông báo từ hệ thống hoặc thực hiện hành động phát sinh thông báo (ví dụ: tạo lớp học, gán bài thi).
- Xác nhận trang thông báo cập nhật thông tin chính xác theo thời gian thực và đồng bộ trạng thái đọc với chuông thông báo (NotificationBell).

---

## ✅ PHASE X COMPLETE
- Localization: [ ] Chưa thực hiện
- Sidebar Update: [ ] Chưa thực hiện
- API Synchronization: [ ] Chưa thực hiện
- Notification Pages: [ ] Chưa thực hiện
- Date: [Chưa hoàn thành]
