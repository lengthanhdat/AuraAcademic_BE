# Register Form Enhancement

## Goal
Hoàn thiện form đăng ký: thêm confirmPassword, Terms & Conditions checkbox, validate password ≥ 6 ký tự real-time, và chuyển hướng về /login sau khi đăng ký thành công.

## Tasks

- [x] Task 1: Thêm state `confirmPassword` + `agreedToTerms` vào `page.tsx`, validate real-time (≥6 ký tự, khớp với confirm) → Verify: typing vào password field thấy indicator lỗi ngay lập tức
- [x] Task 2: Thêm field Confirm Password UI (input + icon lock_clock) vào form → Verify: trường hiển thị đúng style với các trường hiện có
- [x] Task 3: Thêm Terms & Conditions checkbox với link điều khoản → Verify: checkbox hiện, không tick thì button Submit disabled
- [x] Task 4: Chặn submit nếu validation fail (password <6 ký tự, confirmPassword không khớp, chưa tick terms) → Verify: thử submit với data lỗi thấy thông báo lỗi inline
- [x] Task 5: Sau đăng ký thành công (cả 2 flow: có/không email verify) → `router.push('/login')` thay vì `/verify-email` → Verify: đăng ký thành công thấy alert rồi redirect về /login

## Done When
- [x] Confirm password field hiển thị và validate real-time
- [x] Password strength indicator (≥6 ký tự) hoạt động
- [x] Terms & Conditions checkbox bắt buộc trước khi submit
- [x] Sau đăng ký thành công → redirect về /login
