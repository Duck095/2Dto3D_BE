# Hướng dẫn chạy Morphia 3D Backend từ đầu (DB trên Railway)

Tài liệu này hướng dẫn bạn chạy project **Morphia 3D Backend** trên máy local, kết nối tới **PostgreSQL đang chạy trên Railway**, khi các bảng trong database **chưa được tạo**.

> ⚠️ Quan trọng: Ứng dụng tắt Flyway khi khởi động (`spring.flyway.enabled=false`) và chỉ **validate** schema (`spring.jpa.hibernate.ddl-auto=validate`). Do đó, bạn **phải chạy migration tạo bảng trước khi khởi động Spring Boot**, nếu không ứng dụng sẽ bị lỗi `missing table [ai_generation_sessions]` và không khởi động được.

---

## 1. Điều kiện tiên quyết

Trên máy cài đặt đầy đủ:

| Công cụ | Phiên bản tối thiểu |
|---------|---------------------|
| Java | 17+ (khuyên dùng JDK 17 Temurin) |
| Maven Wrapper | Có sẵn trong project (`mvnw.cmd`, `mvnw`) |
| Git Bash / WSL / PowerShell | Để chạy script |
| Docker (tùy chọn) | Chỉ nếu bạn muốn chạy PostgreSQL local thay vì Railway |

---

## 2. Chuẩn bị PostgreSQL trên Railway

Nếu bạn chưa có database trên Railway:

1. Truy cập [Railway Dashboard](https://railway.app/).
2. Tạo dự án mới (hoặc chọn dự án hiện có).
3. Thêm dịch vụ **PostgreSQL** (Add service → Database → PostgreSQL).
4. Vào tab **Connect** của dịch vụ PostgreSQL, lấy **External Connection** (Public connection string).
   - VD: `postgres://postgres:<password>@<host>:<port>/<db>`
5. Chuyển đổi sang định dạng JDBC để điền vào `.env.local`:
   ```
   jdbc:postgresql://<host>:<port>/<db>
   ```
6. Lưu ý `DB_PASSWORD` (mật khẩu postgres).

> 💡 Đảm bảo Railway cho phép kết nối từ IP máy của bạn (bật Public Endpoint nếu cần).

---

## 3. Chuẩn bị biến môi trường local

Trong thư mục `2Dto3D_BE`, tạo file `.env.local` từ mẫu đã có sẵn:

### Trên Windows PowerShell

```powershell
Copy-Item .env.local .env.local
```

Mở file `.env.local` và điền thông tin Railway:

```ini
DB_URL=jdbc:postgresql://<railway-host>:<railway-port>/<railway-db>
DB_PASSWORD=<railway-password>
JWT_SECRET=thisisaverysecretkeythirtytwocharacters!!!
```

| Biến | Mô tả | Ví dụ |
|------|-------|-------|
| `DB_URL` | JDBC URL kết nối PostgreSQL Railway | `jdbc:postgresql://containers-us-west-1.railway.app:5432/railway` |
| `DB_PASSWORD` | Mật khẩu user `postgres` trên Railway | `abcd1234...` |
| `JWT_SECRET` | Chuỗi bí mật JWT (ít nhất 32 ký tự) | `thisisaverysecretkeythirtytwocharacters!!!` |

> 🔒 Không commit file `.env.local` lên Git (đã có trong `.gitignore`).

---

## 4. Chạy Flyway migration (tạo bảng trên Railway)

Lần đầu chạy, database trên Railway **trống**, chưa có bảng nào. Bạn phải chạy migration để tạo toàn bộ schema (bao gồm bảng `ai_generation_sessions` gây lỗi nếu thiếu).

### Cách A: Dùng script PowerShell (khuyên dùng cho Windows)

Mở **PowerShell** trong thư mục `2Dto3D_BE`, chạy:

```powershell
.\run-local.ps1
```

Script này sẽ tự động:
1. Đọc biến từ `.env.local`
2. Chạy **Flyway migration** lên Railway DB (tạo bảng)
3. Khởi động Spring Boot local (port 8080)

Nếu chỉ muốn chạy migration (không khởi động app), dùng lệnh dưới.

### Cách B: Chạy lệnh Flyway thủ công

#### Trên PowerShell

```powershell
$env:DB_URL="jdbc:postgresql://<railway-host>:<railway-port>/<railway-db>"
$env:DB_PASSWORD="<railway-password>"

.\mvnw.cmd flyway:migrate `
  "-Dflyway.url=$env:DB_URL" `
  "-Dflyway.user=postgres" `
  "-Dflyway.password=$env:DB_PASSWORD" `
  "-Dflyway.locations=filesystem:src/main/resources/db/migration" `
  "-Dflyway.baseline-on-migrate=false" `
  "-Dflyway.clean-disabled=true"
```

#### Trên Git Bash / WSL / Linux

```bash
source .env.local
export $(grep -v '^#' .env.local | xargs)

./deploy-migrations.sh
```

Hoặc chạy trực tiếp:

```bash
./mvnw flyway:migrate \
  -Dflyway.url="${DB_URL}" \
  -Dflyway.user=postgres \
  -Dflyway.password="${DB_PASSWORD}" \
  -Dflyway.locations=filesystem:src/main/resources/db/migration \
  -Dflyway.baseline-on-migrate=false \
  -Dflyway.clean-disabled=true
```

Khi thành công, bạn sẽ thấy thông báo tương tự:
```
Successfully applied 3 migrations (execution time 00:00.XXX)
```

Các migration được áp dụng:
- `V1__legacy_schema_baseline.sql` — tạo bảng users, printers, colors, materials, cart, **ai_generation_sessions**, model_3d, v.v.
- `V2__media_asset.sql` — tạo bảng media_asset
- `V3__staff_management.sql` — tạo bảng staff_profiles, staff_profile_audit

---

## 5. Khởi động Spring Boot local

Sau khi migration xong (bảng đã có trên Railway), khởi động ứng dụng.

### Cách A: Dùng script PowerShell (tự động migrate + run)

```powershell
.\run-local.ps1
```

### Cách B: Chạy riêng bằng PowerShell (nếu đã migration ở bước 4)

```powershell
$env:DB_URL="jdbc:postgresql://<railway-host>:<railway-port>/<railway-db>"
$env:DB_PASSWORD="<railway-password>"
$env:JWT_SECRET="thisisaverysecretkeythirtytwocharacters!!!"

.\mvnw.cmd spring-boot:run `
  "-Dspring-boot.run.arguments=--server.port=8080 --spring.profiles.active=local --spring.flyway.enabled=false --spring.jpa.hibernate.ddl-auto=validate"
```

### Cách C: Dùng Git Bash / WSL

```bash
source .env.local
export $(grep -v '^#' .env.local | xargs)

./run-local.sh
```

---

## 6. Kiểm tra ứng dụng chạy thành công

Mở trình duyệt truy cập:

| Truy cập | Mục đích |
|----------|----------|
| `http://localhost:8080/swagger-ui.html` | Xem tài liệu API và test endpoint |
| `http://localhost:8080/v3/api-docs` | Xem JSON spec của OpenAPI |

Nếu thấy giao diện Swagger UI hiện nghĩa là backend đã khởi động thành công và kết nối được DB Railway.

---

## 7. Xử lý lỗi thường gặp

### Lỗi 1: `missing table [ai_generation_sessions]`

**Nguyên nhân:** Chưa chạy Flyway migration lên DB Railway. Bảng chưa tồn tại.

**Cách khắc phục:** Chạy lại bước 4 (Flyway migrate) trước khi khởi động Spring Boot.

### Lỗi 2: `DB_URL must point to a loopback PostgreSQL host`

**Nguyên nhân:** Phiên bản cũ của `dev-env.sh` chặn DB_URL không phải localhost. Đã sửa trong bản mới nhất.

**Cách khắc phục:** Đảm bảo bạn đã pull bản mới nhất của `dev-env.sh` (đã cho phép Railway URL).

### Lỗi 3: Kết nối đến Railway bị timeout / từ chối

**Nguyên nhân:** Railway chặn IP máy bạn hoặc DB_URL sai.

**Cách khắc phục:**
- Vào Railway Dashboard → PostgreSQL → Connect → bật **Public Endpoint**
- Kiểm tra `DB_URL` trong `.env.local` có đúng host/port/db không
- Kiểm tra tường lửa máy bạn cho phép kết nối ra cổng 5432

### Lỗi 4: `JWT_SECRET must contain at least 32 characters`

**Nguyên nhân:** JWT_SECRET quá ngắn.

**Cách khắc phục:** Điền chuỗi ≥32 ký tự vào `.env.local`.

### Lỗi 5: Flyway báo bảng đã tồn tại (nếu chạy migration nhiều lần)

**Nguyên nhân:** Bảng `flyway_schema_history` đã ghi nhận migration trước đó.

**Cách khắc phục:** Không cần chạy lại migration. Chỉ khởi động Spring Boot trực tiếp.

---

## 8. Tóm tắt lệnh nhanh (Windows PowerShell)

```powershell
# 1. Tạo .env.local (chỉ làm lần đầu)
Copy-Item .env.example .env.local
# Mở .env.local và điền DB_URL, DB_PASSWORD, JWT_SECRET của Railway

# 2. Chạy migration + khởi động app
.\run-local.ps1

# 3. Hoặc chỉ chạy migration (nếu đã chạy app bằng cách khác)
.\mvnw.cmd flyway:migrate "-Dflyway.url=$env:DB_URL" "-Dflyway.user=postgres" "-Dflyway.password=$env:DB_PASSWORD" "-Dflyway.locations=filesystem:src/main/resources/db/migration" "-Dflyway.baseline-on-migrate=false" "-Dflyway.clean-disabled=true"
```

---

## 9. Lưu ý khi deploy production

- Trên production (Railway hoặc cloud khác), bạn cũng **phải chạy migration trước khi khởi động container backend**, vì `spring.flyway.enabled=false`.
- Sử dụng script `deploy-migrations.sh` hoặc chạy `flyway:migrate` trong pipeline CI/CD trước khi khởi động service.
- Không bao giờ đặt `spring.jpa.hibernate.ddl-auto=create` hoặc `create-drop` trên production.

---

Nếu cần hỗ trợ thêm, hãy kiểm tra log lỗi và gửi lại để được hỗ trợ cụ thể hơn.
