# PLAN: Teacher Verification System (Hybrid Sandbox Model)
> **Created:** 2026-05-27 | **Status:** ✅ DONE — Backend compile success + Frontend UI complete

---

## Overview

AuraAcademic hướng đến giáo viên tự do / gia sư / trung tâm (B2C), không theo mô hình trường học (B2B).

**Rủi ro cốt lõi:** Học sinh giả mạo tài khoản giáo viên để xem đề thi và đáp án trong ngân hàng đề chung.
**Yêu cầu UX:** Không gây ma sát khi đăng ký; giáo viên phải dùng được ngay.

**Giải pháp:** Mô hình **Hybrid Sandbox** — Cho phép đăng ký tự do, phân cấp quyền hạn rõ ràng theo trạng thái xác thực.

---

## Project Type
**FULL-STACK** — Backend (Spring Boot + MongoDB) + Frontend (Next.js)

---

## Trạng thái xác thực (Verification States)

```
Đăng ký GV mới
      ↓
  [STANDARD]  ←── Mặc định, giới hạn tính năng
      ↓ (Gửi yêu cầu xác thực)
  [PENDING]   ←── Đang chờ Admin duyệt
      ↓                    ↓
 [VERIFIED]           [REJECTED]
 (Mở khóa 100%)    (Có thể gửi lại)
```

### Quyền hạn theo trạng thái

| Tính năng | STANDARD | PENDING | VERIFIED |
|-----------|----------|---------|----------|
| Tạo lớp học | ≤ 2 lớp | ≤ 2 lớp | Không giới hạn |
| Học sinh/lớp | Không giới hạn | Không giới hạn | Không giới hạn |
| Tự soạn đề thi | ✅ | ✅ | ✅ |
| Ngân hàng đề thi chung | ❌ | ❌ | ✅ |

---

## Success Criteria

- [ ] Giáo viên đăng ký xong sử dụng ngay được (trạng thái STANDARD).
- [ ] Giáo viên STANDARD bị giới hạn: tối đa 2 lớp, KHÔNG truy cập Ngân hàng đề chung.
- [ ] Giáo viên gửi được yêu cầu xác thực từ Dashboard (link hoặc ảnh tài liệu).
- [ ] Admin duyệt/từ chối từ trang Admin Dashboard.
- [ ] Khi được duyệt → tài khoản VERIFIED → mở khóa 100% tính năng.
- [ ] Banner thông báo trạng thái hiện trên Dashboard giáo viên STANDARD/PENDING/REJECTED.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3 + Java 17 |
| Database | MongoDB (thêm fields vào `User` collection) |
| Frontend | Next.js 14 + TypeScript |
| Auth | JWT + Spring Security (hiện có) |
| Notification | Hệ thống notification hiện có |

---

## File Structure

```
AuraAcademic_BE/
└── src/main/java/com/auracademic/backend/
    ├── model/User.java                             [SỬA] +7 verification fields
    ├── dto/TeacherVerificationRequest.java         [MỚI]
    ├── dto/TeacherVerificationResponse.java        [MỚI]
    ├── controller/UserController.java              [SỬA] +verification endpoints
    ├── controller/AdminController.java             [SỬA] +approve/reject endpoints
    └── service/UserService.java                    [SỬA] +sandbox limits logic

AuraAcademic/ (Frontend)
└── src/app/[locale]/
    ├── teacher/
    │   ├── dashboard/page.tsx                      [SỬA] +VerificationBanner
    │   └── verify/page.tsx                         [MỚI] Form gửi yêu cầu
    └── admin/
        └── verifications/page.tsx                  [MỚI] Admin review page
```

---

## Task Breakdown

### PHASE 0 — Backend Data Model

#### TASK-01: Thêm fields xác thực vào User.java
- **Priority:** P0 (Blocker)
- **Agent:** `backend-specialist` | **Skill:** `database-design`
- **Input:** `User.java` hiện tại
- **Output:** Thêm vào User entity:
  - `verificationStatus: String` — `"STANDARD"` | `"PENDING"` | `"VERIFIED"` | `"REJECTED"` (default: `"STANDARD"` khi role=teacher)
  - `verificationProofUrl: String`
  - `verificationProofType: String` — `"LINK"` | `"DOCUMENT"`
  - `verificationNote: String` — Ghi chú từ Admin
  - `verificationRequestedAt: LocalDateTime`
  - `verifiedAt: LocalDateTime`
- **Verify:** `./mvnw compile` thành công; có thể set/get các fields mới.

#### TASK-02: Tạo DTOs Verification
- **Priority:** P0
- **Dependencies:** TASK-01
- **Input:** Không
- **Output:**
  - `TeacherVerificationRequest.java`: `{ proofType, proofUrl, description }`
  - `TeacherVerificationResponse.java`: `{ verificationStatus, submittedAt, note }`
- **Verify:** `./mvnw compile` thành công.

---

### PHASE 1 — Core Backend Logic & APIs

#### TASK-03: Sandbox limits trong ClassroomService / UserService
- **Priority:** P1
- **Dependencies:** TASK-01
- **Agent:** `backend-specialist` | **Skill:** `api-patterns`
- **Input:** `ClassroomController.java`, service layer
- **Output:**
  - Tạo lớp thứ 3 với GV STANDARD → HTTP 403 + `"Vui lòng xác thực tài khoản để tạo thêm lớp học."`
  - Thêm học sinh thứ 16 → HTTP 403 + message tương tự
- **Verify:** Test API với Postman/curl; STANDARD GV tạo lớp thứ 3 nhận 403.

#### TASK-04: API gửi yêu cầu xác thực
- **Priority:** P1
- **Dependencies:** TASK-01, TASK-02
- **Input:** `UserController.java`
- **Output:** `POST /api/users/verification-request`
  - Auth: JWT (role=teacher, status=STANDARD hoặc REJECTED)
  - Body: `TeacherVerificationRequest`
  - Cập nhật DB: `verificationStatus = PENDING`, `verificationRequestedAt = now()`
  - Response: `TeacherVerificationResponse`
- **Verify:** Gọi API → DB có `verificationStatus = "PENDING"`.

#### TASK-05: Admin APIs duyệt/từ chối
- **Priority:** P1
- **Dependencies:** TASK-04
- **Input:** `AdminController.java`
- **Output:**
  - `GET /api/admin/verification-requests?status=PENDING` — Danh sách GV cần duyệt
  - `POST /api/admin/verification-requests/{userId}/approve` → status = VERIFIED, `verifiedAt = now()`
  - `POST /api/admin/verification-requests/{userId}/reject` body `{ note }` → status = REJECTED, lưu note
- **Verify:** Approve → user VERIFIED; Reject → user REJECTED với note.

#### TASK-06: Bảo vệ API Ngân hàng đề thi chung
- **Priority:** P1
- **Dependencies:** TASK-01
- **Input:** `ExamBankController.java`
- **Output:** Thêm check `verificationStatus == "VERIFIED"` trước khi trả kết quả đề thi chung. STANDARD GV → 403 + hướng dẫn xác thực.
- **Verify:** Gọi exam bank API với STANDARD token → 403; VERIFIED token → 200.

---

### PHASE 2 — Frontend UI/UX

#### TASK-07: VerificationBanner component
- **Priority:** P2
- **Dependencies:** TASK-04
- **Agent:** `frontend-specialist` | **Skill:** `frontend-design`
- **Input:** Teacher Dashboard layout
- **Output:** Banner hiển thị theo trạng thái:
  - **STANDARD:** Nền amber — "⚡ Tài khoản dùng thử — [Xác thực ngay →]"
  - **PENDING:** Nền xanh lam — "⏳ Đang chờ xét duyệt (24h)"
  - **REJECTED:** Nền đỏ — "❌ Bị từ chối: {note}. [Gửi lại →]"
  - **VERIFIED:** Không hiển thị
- **Verify:** Kiểm tra từng trạng thái trên UI, responsive.

#### TASK-08: Trang `/teacher/verify` — Form gửi xác thực
- **Priority:** P2
- **Dependencies:** TASK-04, TASK-07
- **Input:** Không có (trang mới)
- **Output:** Trang bao gồm:
  - Giải thích quyền lợi được mở khóa khi xác thực
  - Radio: `[Link dạy học công khai]` / `[Tải ảnh tài liệu]`
  - Input URL hoặc File upload (JPG/PNG, < 5MB)
  - Textarea mô tả tùy chọn
  - Nút "Gửi yêu cầu" → toast success → redirect về Dashboard
- **Verify:** Submit thành công → API nhận đúng data → DB cập nhật → Banner thay đổi sang PENDING.

#### TASK-09: Trang Admin `/admin/verifications`
- **Priority:** P2
- **Dependencies:** TASK-05
- **Input:** Trang Admin hiện có
- **Output:**
  - Bảng danh sách yêu cầu: Tên GV, Email, Ngày gửi, Loại, Link xem tài liệu
  - Nút [✓ Duyệt] [✗ Từ chối] → Modal nhập lý do từ chối
  - Filter tab: Tất cả / Đang chờ / Đã duyệt / Đã từ chối
  - Badge đỏ đếm số PENDING trên sidebar Admin
- **Verify:** Admin duyệt → user nhận notification + trạng thái thay đổi ngay.

---

### PHASE 3 — Notifications

#### TASK-10: Notification khi trạng thái thay đổi
- **Priority:** P3
- **Dependencies:** TASK-05
- **Agent:** `backend-specialist`
- **Input:** `Notification.java` (hệ thống hiện có)
- **Output:**
  - Duyệt → notification GV: "🎉 Tài khoản của bạn đã được xác thực! Toàn bộ tính năng đã được mở khóa."
  - Từ chối → notification GV với lý do từ chối.
- **Verify:** Notification xuất hiện trên chuông thông báo của giáo viên.

---

## Phase X: Verification Checklist

- [ ] TASK-01: `User.java` compile thành công với đủ fields mới
- [ ] TASK-02: DTOs compile thành công
- [ ] TASK-03: API tạo lớp thứ 3 của STANDARD GV → 403
- [ ] TASK-04: POST verification-request → DB status = PENDING
- [ ] TASK-05: Admin approve/reject hoạt động đúng
- [ ] TASK-06: Exam bank API chặn STANDARD GV → 403
- [ ] TASK-07: VerificationBanner hiển thị đúng 4 trạng thái
- [ ] TASK-08: Form xác thực end-to-end thành công
- [ ] TASK-09: Admin page duyệt được request
- [ ] TASK-10: Notification gửi được sau khi duyệt/từ chối
- [ ] `./mvnw package` — build thành công
- [ ] `npm run build` — không có lỗi TypeScript
- [ ] Manual E2E: Đăng ký GV → STANDARD → Gửi xác thực → Admin duyệt → VERIFIED → Truy cập Exam Bank ✅

---

## Risks & Mitigation

| Rủi ro | Mitigation |
|--------|-----------|
| File upload lớn | Giới hạn 5MB, validate MIME type server-side |
| Admin bỏ sót yêu cầu | Badge đếm PENDING trên Admin nav |
| Bypass sandbox qua API | Kiểm tra server-side trong Service layer |
