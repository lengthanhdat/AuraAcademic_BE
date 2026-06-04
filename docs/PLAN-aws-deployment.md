# Kế hoạch Triển khai Hệ thống AuraAcademic lên AWS (AWS Deployment Plan)

Bản kế hoạch này hướng dẫn chi tiết các bước thiết lập kiến trúc cloud, tích hợp và triển khai hệ thống AuraAcademic (Frontend Next.js, Backend Spring Boot, AI Service Python, MongoDB) lên AWS. Kế hoạch này áp dụng tối thiểu **3 dịch vụ AWS** nhằm đáp ứng yêu cầu của dự án thực tập, đồng thời tối ưu hóa chi phí trong hạn mức **$200 credits**.

---

## 📌 Tổng quan dự án (Overview)
- **Mục tiêu**: Deploy toàn bộ hệ thống lên AWS, chuyển đổi cơ chế lưu trữ file base64 (trong MongoDB) sang lưu trữ đám mây, bảo mật cấu hình bằng các dịch vụ quản lý key của AWS, cấu hình CI/CD cơ bản và tên miền HTTPS.
- **Loại dự án**: DEPLOY & INFRASTRUCTURE (AWS Cloud)
- **Hạn mức tài chính**: $200 AWS Credits (Đảm bảo vận hành tối thiểu 6-12 tháng không vượt định mức).

---

## 🎯 Tiêu chí thành công (Success Criteria)
1. **Áp dụng tối thiểu 3 dịch vụ AWS**:
   - **AWS EC2**: Máy chủ ảo chạy ứng dụng (Backend Spring Boot & AI FastAPI).
   - **AWS S3**: Lưu trữ tài liệu học tập (PDF, DOCX, Video) thay thế cho lưu base64 trực tiếp vào database.
   - **AWS Systems Manager (SSM) Parameter Store** hoặc **AWS Secrets Manager**: Quản lý tập trung các biến môi trường và thông tin bảo mật (SMTP, Google Client ID, DB URI).
2. **Tối ưu chi phí (Cost Optimization)**: Tổng hóa đơn AWS hàng tháng nhỏ hơn **$18/tháng**, đảm bảo tài khoản hoạt động ổn định trong suốt thời gian thực tập và chấm điểm.
3. **Bảo mật và kết nối**: 
   - Sử dụng HTTPS/SSL cho cả Frontend và Backend.
   - Cơ sở dữ liệu MongoDB được bảo vệ bằng tài khoản/mật khẩu và chỉ mở port nội bộ trong Docker network.
4. **Vận hành tự động**: 
   - CI/CD tự động build & deploy frontend Next.js qua Vercel hoặc AWS Amplify khi push code lên nhánh `main`.
   - Backend và AI Service chạy ổn định trên EC2 bằng Docker Compose.

---

## ⚙️ Kiến trúc đám mây & Dịch vụ AWS sử dụng (Tech Stack)

### 1. AWS EC2 (Elastic Compute Cloud) - *Dịch vụ 1*
- **Loại instance**: `t3.small` (2 vCPUs, 2 GB RAM) hoặc `t3.micro` (nếu muốn dùng Free Tier). Khuyến nghị dùng `t3.small` (khoảng ~$15/tháng) để có đủ 2GB RAM chạy cả Spring Boot JVM, Python FastAPI và MongoDB.
- **Hệ điều hành**: Ubuntu Server 22.04 LTS.
- **Vai trò**: Chạy Docker Compose chứa 3 container:
  - Container 1: `auracademic-backend` (Spring Boot).
  - Container 2: `auracademic-ai` (FastAPI).
  - Container 3: `mongodb` (Lưu dữ liệu hệ thống).

### 2. AWS S3 (Simple Storage Service) - *Dịch vụ 2*
- **Vai trò**: Lưu trữ toàn bộ tài liệu được upload từ giảng viên/admin.
- **Cải tiến kỹ thuật**: Thay vì truyền file base64 và lưu trữ chuỗi base64 khổng lồ vào MongoDB, backend Spring Boot sẽ nhận file từ client, tải trực tiếp lên bucket AWS S3 và chỉ lưu URL của file trên S3 vào database. Điều này giúp tối ưu hóa dung lượng DB và tăng tốc độ tải trang.

### 3. AWS Systems Manager Parameter Store (SSM) - *Dịch vụ 3*
- **Vai trò**: Lưu trữ các thông tin cấu hình nhạy cảm (Credentials):
  - Mật khẩu SMTP Mail Server (`yfpg udtk dykk iull`).
  - Google Client ID (`850775217149-...`).
  - AWS Access Key / Secret Key phục vụ S3.
  - MongoDB connection string.
- **Lý do lựa chọn**: Hoàn toàn miễn phí cho các parameter tiêu chuẩn (Standard Parameters), giúp tích hợp trực tiếp vào ứng dụng Spring Boot thông qua thư viện `aws-secretsmanager-jdbc` hoặc nạp trực tiếp qua biến môi trường của EC2 tại startup.

---

## 📁 Cấu trúc file cấu hình mới (Deployment Files)

Để chuẩn bị deploy, chúng ta sẽ cần tạo thêm các file cấu hình sau trong thư mục dự án:
- `/docker-compose.yml` - File cấu hình chạy đa container trên EC2.
- `/nginx.conf` - Cấu hình Nginx làm Reverse Proxy và SSL.
- `/.github/workflows/deploy.yml` - File cấu hình tự động CI/CD cho dự án.

---

## 📋 Chi tiết các Task triển khai (Task Breakdown)

### 🔹 Task 1: Thiết lập Infrastructure trên AWS Console
- **Mô tả**: Tạo các tài nguyên cơ bản trên AWS bao gồm EC2, S3 Bucket và SSM Parameters.
- **Người thực hiện**: `devops-engineer`
- **Skills**: `server-management`, `powershell-windows`
- **Độ ưu tiên**: P0
- **INPUT**: Tài khoản AWS có $200 credits.
- **OUTPUT**:
  - 1 S3 Bucket (ví dụ: `auraacademic-materials-bucket`) được cấu hình Block Public Access hợp lý và có IAM Policy cho phép Backend đọc/ghi.
  - 1 EC2 Instance (`t3.small`) chạy Ubuntu, đã mở port 80 (HTTP), 443 (HTTPS), và 22 (SSH).
  - Các tham số bảo mật được lưu trong AWS SSM Parameter Store.
- **VERIFY**:
  - SSH thành công vào EC2 instance.
  - Test upload thử một file lên S3 thông qua AWS CLI hoặc AWS Console thành công.

### 🔹 Task 2: Cấu hình Docker & Nginx Reverse Proxy trên EC2
- **Mô tả**: Tạo file `docker-compose.yml` để đóng gói Spring Boot, FastAPI và MongoDB. Cấu hình Nginx và Let's Encrypt SSL để chạy domain HTTPS.
- **Người thực hiện**: `devops-engineer`
- **Skills**: `server-management`, `bash-linux`
- **Độ ưu tiên**: P0
- **INPUT**: EC2 instance đã khởi tạo ở Task 1.
- **OUTPUT**:
  - File `docker-compose.yml` ở thư mục gốc của EC2.
  - Nginx chạy thành công, trỏ domain (ví dụ: `api.auraacademic.com`) về cổng `8088` của Spring Boot và `/ai` về cổng của FastAPI.
  - SSL tự động gia hạn qua Certbot.
- **VERIFY**: 
  - Chạy lệnh `docker ps` trên EC2 thấy cả 3 container (backend, ai, mongodb) đều đang ở trạng thái `Up`.
  - Truy cập URL backend qua HTTPS (ví dụ: `https://api.auraacademic.com/api/admin/settings/health`) trả về JSON hợp lệ.

### 🔹 Task 3: Tích hợp AWS S3 SDK vào Backend Spring Boot
- **Mô tả**: Cập nhật mã nguồn backend để chuyển đổi API upload tài liệu. File upload sẽ được lưu vào AWS S3 thay vì lưu base64.
- **Người thực hiện**: `backend-specialist`
- **Skills**: `api-patterns`, `clean-code`
- **Độ ưu tiên**: P1
- **INPUT**: Dự án `AuraAcademic_BE`, thông tin AWS S3 bucket và IAM credentials.
- **OUTPUT**:
  - Dependency `software.amazon.awssdk:s3` được thêm vào `pom.xml`.
  - `MaterialService.java` được sửa đổi: nhận file, upload lên S3, lấy URL dạng `https://s3.amazonaws.com/...` và lưu URL đó vào trường `fileUrl`.
- **VERIFY**:
  - Chạy unit test upload file trên local thành công.
  - Kiểm tra database MongoDB xem trường `fileUrl` đã lưu URL của S3 hay chưa (không còn lưu chuỗi base64 dài).

### 🔹 Task 4: Triển khai Frontend Next.js lên AWS Amplify hoặc Vercel
- **Mô tả**: Deploy mã nguồn frontend `AuraAcademic` lên cloud, liên kết với domain chính và trỏ endpoint API về địa chỉ EC2 mới.
- **Người thực hiện**: `frontend-specialist`
- **Skills**: `frontend-design`, `clean-code`
- **Độ ưu tiên**: P1
- **INPUT**: Repo Github frontend, URL API HTTPS của backend EC2.
- **OUTPUT**:
  - Dự án frontend chạy thành công trên AWS Amplify hoặc Vercel.
  - Biến môi trường `NEXT_PUBLIC_API_URL` trỏ về API EC2.
- **VERIFY**:
  - Truy cập website thành công qua HTTPS. Các chức năng đăng nhập, xem danh sách tài liệu, gọi API chẩn đoán hoạt động mượt mà.

---

## 🏁 Phase X: Quy trình Nghiệm thu & Xác minh (Verification Suite)

### 1. Kiểm tra tĩnh & Bảo mật (Security & Static Audits)
```bash
# Kiểm tra lỗ hổng bảo mật các gói phụ thuộc và cấu hình AWS IAM
python .agent/skills/vulnerability-scanner/scripts/security_scan.py .
```
- Xác nhận các thông tin bí mật (AWS Keys, Mail Password) đã được nạp qua biến môi trường hoặc SSM Parameter Store, không bị lộ lọt trên file `application.properties` hay code repo trên GitHub.

### 2. Kiểm tra Runtime & Chi phí
- Đăng nhập vào AWS Billing Console để kiểm tra xem chi phí hàng ngày có phát sinh bất thường không (giữ ở mức nhỏ hơn $0.6/ngày).
- Thực hiện một luồng hoàn chỉnh trên website:
  1. Đăng nhập tài khoản giáo viên.
  2. Upload tài liệu (PDF).
  3. Xác nhận hệ thống gửi file lên AWS S3 thành công và Gemini AI phân tích bình thường.
  4. Đăng nhập tài khoản sinh viên và tải xuống tài liệu đó từ link AWS S3.

---

## ✅ PHASE X COMPLETE
- Infrastructure: [ ] Chưa thực hiện
- Docker Setup: [ ] Chưa thực hiện
- S3 Integration: [ ] Chưa thực hiện
- Deployment Complete: [ ] Chưa thực hiện
- Date: [Chưa hoàn thành]
