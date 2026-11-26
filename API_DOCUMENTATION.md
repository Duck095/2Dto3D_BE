# 📘 TỔNG HỢP API DOCUMENTATION – MORPHIA3D BACKEND

> **Mục đích:** File này tổng hợp toàn bộ API backend (request & response) để đội frontend sử dụng làm tài liệu tham chiếu khi code.
>
> **Dự án:** 2Dto3D_BE (Java Spring Boot)
> **Ngôn ngữ:** Tiếng Việt
> **Cập nhật:** Wed Nov 25 2026

---

## 1. Thông tin chung

### 1.1 Base URL
```
BASE_URL = http://localhost:8080   # dev local (cấu hình qua env `SERVER_PORT`)
```
Tất cả endpoint dưới đây đều bắt đầu bằng `BASE_URL`. Ví dụ đầy đủ: `POST http://localhost:8080/api/auth/login`.

### 1.2 Định dạng dữ liệu
- **Content-Type request:** `application/json` (trừ các endpoint upload file dùng `multipart/form-data`)
- **Content-Type response:** `application/json`
- **Mã hóa:** UTF-8

### 1.3 Xác thực (Authentication)
Hầu hết các API cần gửi header:
```
Authorization: Bearer <access_token>
```
Token nhận được sau khi `login` hoặc `register` (trong trường `AuthResponse.token`).

### 1.4 Mã trạng thái HTTP phổ biến
| Mã | Ý nghĩa |
|----|---------|
| 200 | OK – yêu cầu thành công |
| 201 | Created – tạo tài nguyên thành công |
| 204 | No Content – thành công, không trả body |
| 400 | Bad Request – dữ liệu gửi lên không hợp lệ |
| 401 | Unauthorized – chưa đăng nhập / token không hợp lệ |
| 403 | Forbidden – không đủ quyền truy cập |
| 404 | Not Found – không tìm thấy tài nguyên |
| 500 | Internal Server Error – lỗi server |

---

## 2. Danh sách Enum (giá trị hợp lệ)

Dùng cho các trường kiểu enum trong request/response.

### 2.1 Role (Vai trò người dùng)
```
ADMIN, USER, DESIGNER
```

### 2.2 UserStatus (Trạng thái tài khoản)
```
ACTIVE, UNACTIVE
```

### 2.3 OrderStatus (Trạng thái đơn hàng)
```
PENDING, PROCESSING, SUCCEEDED, CANCELED
```

### 2.4 PaymentMethod (Phương thức thanh toán)
```
VNPAY, MOMO
```

### 2.5 PaymentStatus (Trạng thái thanh toán)
```
PENDING, SUCCESS, FAILED
```

### 2.6 ModelType (Loại mô hình 3D)
```
DESIGNER_MODEL, AI_MODEL, CUSTOMER_CUSTOMIZED
```

### 2.7 ModelStatus (Trạng thái mô hình)
```
PENDING, ACTIVE, INACTIVE
```

### 2.8 MaterialStatus (Trạng thái nguyên vật liệu)
```
AVAILABLE, UNAVAILABLE
```

### 2.9 StaffStatus (Trạng thái nhân viên)
```
PENDING, ACTIVE, SUSPENDED, TERMINATED
```

---

## 3. Chi tiết từng nhóm API

---

### 3.1 Auth (`/api/auth`)

#### 3.1.1 Đăng ký tài khoản
`POST /api/auth/register`

**Request Body** (`RegisterRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `name` | String | Có | Họ tên |
| `email` | String | Có | Email (định dạng email) |
| `phone` | String | Không | Số điện thoại |
| `password` | String | Có | Mật khẩu, tối thiểu 6 ký tự |

**Ví dụ request:**
```json
{
  "name": "Nguyen Van A",
  "email": "nguyenvana@example.com",
  "phone": "0901234567",
  "password": "Abc12345"
}
```

**Response** (`AuthResponse`) – HTTP 200
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `token` | String | JWT access token |
| `email` | String | Email người dùng |
| `name` | String | Họ tên |
| `role` | String | Vai trò (ADMIN/USER/DESIGNER) |
| `status` | String | Trạng thái tài khoản (ACTIVE/UNACTIVE) |

**Ví dụ response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "email": "nguyenvana@example.com",
  "name": "Nguyen Van A",
  "role": "USER",
  "status": "ACTIVE"
}
```

---

#### 3.1.2 Đăng nhập
`POST /api/auth/login`

**Request Body** (`LoginRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `email` | String | Có | Email |
| `password` | String | Có | Mật khẩu |

**Ví dụ request:**
```json
{
  "email": "nguyenvana@example.com",
  "password": "Abc12345"
}
```

**Response** (`AuthResponse`) – HTTP 200 (cùng cấu trúc như đăng ký)

---

#### 3.1.3 Kiểm tra hệ thống (Ping)
`GET /api/auth/ping`

**Request:** Không body

**Response:** `String` – HTTP 200
```
"ok"
```
(Header tùy chọn `X-Backend-Build-Id` nếu có cấu hình)

---

### 3.2 Sản phẩm (`/api/products`)

#### 3.2.1 Lấy danh sách sản phẩm (phân trang, tìm kiếm)
`GET /api/products`

**Query Params**
| Tham số | Kiểu | Mặc định | Bắt buộc | Mô tả |
|---------|------|----------|----------|-------|
| `page` | int | `0` | Không | Trang (bắt đầu từ 0) |
| `size` | int | `12` | Không | Số item/trang |
| `q` | String | `null` | Không | Từ khóa tìm kiếm |
| `category` | String | `null` | Không | Lọc theo thể loại |
| `sort` | String | `"newest"` | Không | Tiêu chí sắp xếp |

**Response** (`PaginatedProductResponse`) – HTTP 200
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `items` | List<ProductSummaryResponse> | Danh sách sản phẩm trang hiện tại |
| `page` | int | Số trang hiện tại |
| `size` | int | Kích thước trang |
| `totalElements` | long | Tổng số sản phẩm |
| `totalPages` | int | Tổng số trang |

`ProductSummaryResponse`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID sản phẩm |
| `name` | String | Tên sản phẩm |
| `slug` | String | Slug (đường dẫn thân thiện) |
| `description` | String | Mô tả ngắn |
| `category` | String | Thể loại |
| `thumbnailUrl` | String | URL ảnh đại diện |

**Ví dụ response:**
```json
{
  "items": [
    {
      "id": 101,
      "name": "Mô hình túi xách 3D",
      "slug": "mo-hinh-tui-xach-3d",
      "description": "Túi xách in 3D chất lượng cao",
      "category": "Phụ kiện",
      "thumbnailUrl": "https://cdn.example.com/images/tui-xach-thumb.jpg"
    }
  ],
  "page": 0,
  "size": 12,
  "totalElements": 45,
  "totalPages": 4
}
```

---

#### 3.2.2 Lấy chi tiết sản phẩm
`GET /api/products/{id}`

**Path Variable:** `id` (Integer)

**Response** (`ProductDetailResponse`) – HTTP 200
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID sản phẩm |
| `name` | String | Tên sản phẩm |
| `slug` | String | Slug |
| `description` | String | Mô tả |
| `category` | String | Thể loại |
| `images` | List<ProductImageResponse> | Danh sách ảnh sản phẩm |
| `model3dUrl` | String | URL file mô hình 3D |
| `sizeXMm` | Double | Kích thước X (mm) |
| `sizeYMm` | Double | Kích thước Y (mm) |
| `sizeZMm` | Double | Kích thước Z (mm) |
| `modelParts` | List<ProductPartResponse> | Danh sách các bộ phận / phần |

`ProductImageResponse`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID ảnh |
| `url` | String | URL ảnh |
| `primary` | boolean | Có phải ảnh chính không |

`ProductPartResponse`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `name` | String | Tên phần |
| `key` | String | Mã phần |

**Ví dụ response:**
```json
{
  "id": 101,
  "name": "Mô hình túi xách 3D",
  "slug": "mo-hinh-tui-xach-3d",
  "description": "Túi xách in 3D chất lượng cao",
  "category": "Phụ kiện",
  "images": [
    { "id": 1, "url": "https://cdn.example.com/images/tui-1.jpg", "primary": true },
    { "id": 2, "url": "https://cdn.example.com/images/tui-2.jpg", "primary": false }
  ],
  "model3dUrl": "https://cdn.example.com/models/tui-xach.glb",
  "sizeXMm": 120.0,
  "sizeYMm": 80.0,
  "sizeZMm": 40.0,
  "modelParts": [
    { "name": "Mềm túi", "key": "body" },
    { "name": "Vây đeo", "key": "strap" }
  ]
}
```

---

### 3.3 Mô hình 3D (`/api`)

> Các endpoint này dùng `Authentication` (header `Authorization: Bearer <token>`) khi yêu cầu đăng nhập.

#### 3.3.1 Tải lên mô hình của nhà thiết kế (Designer)
`POST /api/designer-models`  
**Content-Type:** `multipart/form-data`

**Request Parts**
| Phần | Kiểu | Bắt buộc | Mô tả |
|------|------|----------|-------|
| `data` | `DesignerModelRequest` (JSON) | Có | Thông tin mô hình |
| `images` | List<MultipartFile> | Có | Ảnh minh họa |
| `model` | MultipartFile | Có | File mô hình 3D (`.glb`, `.obj`, …) |

`DesignerModelRequest`:
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `designerId` | Integer | Không | ID nhà thiết kế |
| `name` | String | Không | Tên mô hình |
| `slug` | String | Không | Slug |
| `description` | String | Không | Mô tả |
| `filePath3d` | String | Không | Đường dẫn file 3D |
| `sizeXMm` | Double | Không | Kích thước X |
| `sizeYMm` | Double | Không | Kích thước Y |
| `sizeZMm` | Double | Không | Kích thước Z |
| `weight` | Double | Không | Trọng lượng |
| `modelParts` | List<ModelPartRequest> | Không | Danh sách bộ phận |
| `images` | List<ModelImageRequest> | Không | Danh sách ảnh |
| `category` | String | Không | Thể loại |

`ModelPartRequest`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `partName` | String | Tên bộ phận |
| `partKey` | String | Mã bộ phận |
| `colorId` | Integer | ID màu sắc |

`ModelImageRequest`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `imagePath` | String | Đường dẫn ảnh |
| `isPrimary` | Boolean | Có phải ảnh chính |

**Response** (`ModelResponse`) – HTTP 200 (xem cấu trúc ở 3.3.5)

---

#### 3.3.2 Xuất bản mô hình nhà thiết kế (Admin)
`POST /api/admin/designer-models/{id}/publish`

**Path Variable:** `id` (Integer)  
**Request:** Không body  
**Response:** HTTP 204 No Content (không body)

> Yêu cầu quyền ADMIN.

---

#### 3.3.3 Lấy danh sách mô hình nhà thiết kế
`GET /api/designer-models`

**Response:** `List<ModelResponse>` – HTTP 200

---

#### 3.3.4 Lấy chi tiết mô hình nhà thiết kế
`GET /api/designer-models/{id}`

**Path Variable:** `id` (Integer)

**Response:** `ModelResponse` – HTTP 200

---

#### 3.3.5 Tạo mô hình AI
`POST /api/ai-models`  
**Content-Type:** `multipart/form-data`  
**Yêu cầu:** Đăng nhập

**Request Parts** (giống designer model, dùng `AIModelRequest`)
| Phần | Kiểu | Bắt buộc | Mô tả |
|------|------|----------|-------|
| `data` | `AIModelRequest` (JSON) | Có | Thông tin mô hình AI |
| `images` | List<MultipartFile> | Có | Ảnh |
| `model` | MultipartFile | Có | File mô hình 3D |

`AIModelRequest` có các trường tương tự `DesignerModelRequest` thay `designerId` bằng `ownerId`.

**Response:** `ModelResponse` – HTTP 200

---

#### 3.3.6 Lấy chi tiết mô hình AI
`GET /api/ai-models/{id}`  
**Yêu cầu:** Đăng nhập

**Path Variable:** `id` (Integer)

**Response:** `ModelResponse` – HTTP 200

---

#### 3.3.7 Cấu trúc `ModelResponse` (dùng chung)

| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID mô hình |
| `sourceType` | String | Loại nguồn (DESIGNER_MODEL / AI_MODEL / CUSTOMER_CUSTOMIZED) |
| `userId` | Integer | ID người dùng sở hữu |
| `slug` | String | Slug |
| `name` | String | Tên mô hình |
| `description` | String | Mô tả |
| `filePath3d` | String | Đường dẫn file 3D |
| `sizeXMm` | Double | Kích thước X (mm) |
| `sizeYMm` | Double | Kích thước Y (mm) |
| `sizeZMm` | Double | Kích thước Z (mm) |
| `volumeCm3` | Double | Thể tích (cm³) |
| `modelParts` | List<ModelPartResponse> | Danh sách bộ phận |
| `modelImages` | List<ModelImageResponse> | Danh sách ảnh |
| `category` | String | Thể loại |
| `status` | String | Trạng thái (PENDING/ACTIVE/INACTIVE) |
| `createdAt` | LocalDateTime | Ngày tạo |

`ModelPartResponse`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID bộ phận |
| `partName` | String | Tên bộ phận |
| `partKey` | String | Mã bộ phận |
| `color` | ColorResponse | Màu sắc (xem 3.7) |

`ModelImageResponse`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID ảnh |
| `imagePath` | String | Đường dẫn ảnh |
| `isPrimary` | Boolean | Ảnh chính |
| `uploadedAt` | LocalDateTime | Ngày tải lên |

**Ví dụ response (ModelResponse):**
```json
{
  "id": 501,
  "sourceType": "DESIGNER_MODEL",
  "userId": 10,
  "slug": "ghe-lam-viec-3d",
  "name": "Ghế làm việc in 3D",
  "description": "Ghế ergonomic thiết kế by AI",
  "filePath3d": "/models/ghe-lam-viec.glb",
  "sizeXMm": 500.0,
  "sizeYMm": 500.0,
  "sizeZMm": 900.0,
  "volumeCm3": 12500.0,
  "category": "Đồ nội thất",
  "status": "ACTIVE",
  "createdAt": "2026-11-25T08:30:00",
  "modelParts": [
    {
      "id": 1,
      "partName": "Đế ghế",
      "partKey": "base",
      "color": { "colorId": 3, "name": "Xám", "hexCode": "#808080" }
    }
  ],
  "modelImages": [
    { "id": 7, "imagePath": "/images/ghe-1.jpg", "isPrimary": true, "uploadedAt": "2026-11-25T08:30:00" }
  ]
}
```

---

### 3.4 Đơn hàng (`/api/orders`)

> Toàn bộ endpoint nhóm này yêu cầu đăng nhập.

#### 3.4.1 Tạo đơn hàng từ toàn bộ giỏ hàng
`POST /api/orders`

**Request:** Không body

**Response** (`OrderResponse`) – HTTP 201

---

#### 3.4.2 Tạo đơn hàng từ 1 item trong giỏ
`POST /api/orders/from-cart-item/{cartItemId}`

**Path Variable:** `cartItemId` (Integer)

**Request:** Không body

**Response** (`OrderResponse`) – HTTP 201

---

#### 3.4.3 Cập nhật trạng thái đơn hàng
`PATCH /api/orders/{id}/status`

**Path Variable:** `id` (Integer)

**Request Body** (`UpdateOrderStatusRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `status` | `OrderStatus` | Có | Trạng thái mới (PENDING/PROCESSING/SUCCEEDED/CANCELED) |

**Ví dụ request:**
```json
{
  "status": "PROCESSING"
}
```

**Response** (`OrderResponse`) – HTTP 200

---

#### 3.4.4 Lấy chi tiết đơn hàng
`GET /api/orders/{id}`

**Path Variable:** `id` (Integer)

**Response** (`OrderResponse`) – HTTP 200

---

#### 3.4.5 Cấu trúc `OrderResponse`

| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID đơn hàng |
| `status` | `OrderStatus` | Trạng thái đơn |
| `totalAmount` | Double | Tổng tiền |
| `orderDate` | LocalDateTime | Ngày đặt hàng |
| `completedDate` | LocalDateTime | Ngày hoàn thành (nếu có) |
| `items` | List<OrderItemResponse> | Danh sách item trong đơn |

`OrderItemResponse`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID item |
| `modelId` | Integer | ID mô hình 3D |
| `quantity` | Integer | Số lượng |
| `priceSnapshot` | Double | Giá hình ảnh khi đặt |

**Ví dụ response:**
```json
{
  "id": 2001,
  "status": "PENDING",
  "totalAmount": 450000.0,
  "orderDate": "2026-11-25T09:15:00",
  "completedDate": null,
  "items": [
    { "id": 1, "modelId": 101, "quantity": 2, "priceSnapshot": 225000.0 }
  ]
}
```

---

### 3.5 Thanh toán (`/api/payments`)

#### 3.5.1 Tạo yêu cầu thanh toán
`POST /api/payments`  
**Yêu cầu:** Đăng nhập

**Request Body** (`CreatePaymentRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `orderId` | Integer | Có | ID đơn hàng |
| `method` | `PaymentMethod` | Có | `VNPAY` hoặc `MOMO` |

**Ví dụ request:**
```json
{
  "orderId": 2001,
  "method": "VNPAY"
}
```

**Response** (`PaymentUrlResponse`) – HTTP 200
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `paymentUrl` | String | URL chuyển hướng khách hàng để thanh toán |
| `paymentId` | Integer | ID bản ghi thanh toán |

**Ví dụ response:**
```json
{
  "paymentUrl": "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_...",
  "paymentId": 3001
}
```
> Frontend: redirect trình duyệt của người dùng đến `paymentUrl`.

---

#### 3.5.2 VNPay return URL (khi người dùng quay về sau thanh toán)
`GET /api/payments/vnpay/return`

**Query Params:** Tất cả tham số VNPay gửi về (`Map<String, String>`)

**Response:** `String` – HTTP 200  
```
"Thanh toán thành công"
```
hoặc lỗi:
```
"Thanh toán thất bại: <message>"
```

---

#### 3.5.3 MoMo return URL (khi người dùng quay về từ app MoMo)
`GET /api/payments/momo/return`

**Query Params:** Tất cả tham số MoMo gửi về

**Response:** `String` – HTTP 200

---

#### 3.5.4 MoMo IPN (server-to-server, đáng tin cậy hơn return URL)
`POST /api/payments/momo/ipn`

**Request Body:** `Map<String, String>` (thông báo từ MoMo)

**Response:** HTTP 200/204, không body (MoMo yêu cầu)

---

### 3.6 Nguyên vật liệu (`/api/materials`)

#### 3.6.1 Lấy tất cả nguyên vật liệu
`GET /api/materials/`

**Response:** `List<MaterialResponse>` – HTTP 200

#### 3.6.2 Tạo nguyên vật liệu mới
`POST /api/materials/create`

**Request Body** (`MaterialRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `name` | String | Không | Tên vật liệu |
| `type` | String | Không | Loại vật liệu |
| `colors` | List<ColorQuantityRequest> | Không | Danh sách màu + số lượng |
| `pricePerGram` | Double | Không | Giá/gam |
| `stockQuantity` | Double | Không | Số lượng tồn kho |
| `unit` | String | Không | Đơn vị |

`ColorQuantityRequest`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `colorId` | Integer | ID màu |
| `quantity` | Double | Số lượng |

**Response:** HTTP 200 (không body)

---

#### 3.6.3 Xoá (ngừng hoạt động) nguyên vật liệu
`DELETE /api/materials/{id}/remove`

**Path Variable:** `id` (Integer)

**Response:** HTTP 200 (không body)

---

#### 3.6.4 Thêm màu sắc cho nguyên vật liệu
`POST /api/materials/colors/{id}`

**Path Variable:** `id` (Integer) – ID nguyên vật liệu

**Request Body** (`ColorQuantityRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `colorId` | Integer | Có | ID màu |
| `quantity` | Double | Có | Số lượng |

**Response:** HTTP 201 (không body)

---

#### 3.6.5 Xoá màu sắc của nguyên vật liệu
`DELETE /api/materials/{id}/remove-color/{colorId}`

**Path Variable:** `id` (ID vật liệu), `colorId` (ID màu)

**Response:** HTTP 204 (không body)

---

#### 3.6.6 Cấu trúc `MaterialResponse`

| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID vật liệu |
| `name` | String | Tên |
| `type` | String | Loại |
| `pricePerGram` | Double | Giá/gam |
| `stockQuantity` | Double | Tồn kho |
| `unit` | String | Đơn vị |
| `isAvailable` | `MaterialStatus` | `AVAILABLE` / `UNAVAILABLE` |
| `colors` | List<ColorResponse> | Danh sách màu |

`ColorResponse`:
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `colorId` | Integer | ID màu |
| `name` | String | Tên màu |
| `hexCode` | String | Mã màu hex (ví dụ `#FF5733`) |

---

### 3.7 Màu sắc (`/api/colors`)

#### 3.7.1 Tạo màu mới
`POST /api/colors/create`

**Request Body** (`ColorRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `name` | String | Có | Tên màu |
| `hexCode` | String | Có | Mã hex |

**Ví dụ request:**
```json
{
  "name": "Xanh dương",
  "hexCode": "#1E90FF"
}
```

**Response:** HTTP 200 (không body)

---

#### 3.7.2 Lấy màu theo tên
`GET /api/colors/{colorName}`

**Path Variable:** `colorName` (String)

**Response** (`ColorResponse`) – HTTP 200 (xem cấu trúc ở 3.6.6)

---

### 3.8 Quản lý tài khoản (`/api/accounts`)

> Các endpoint này thường dành cho Admin.

#### 3.8.1 Cập nhật trạng thái tài khoản
`PUT /api/accounts/{id}/status`

**Path Variable:** `id` (Integer)

**Request Body:** `UserStatus` (giá trị thẳng, không bao trong object)  
Ví dụ: `"ACTIVE"` hoặc `"UNACTIVE"`

**Response:** HTTP 200 (không body)

---

#### 3.8.2 Cập nhật vai trò tài khoản
`PUT /api/accounts/{id}/role`

**Path Variable:** `id` (Integer)

**Request Body:** `Role` (giá trị thẳng)  
Ví dụ: `"ADMIN"`, `"USER"`, `"DESIGNER"`

**Response:** HTTP 200 (không body)

---

### 3.9 Quản lý nhân viên (`/api/admin/staff`)

> Yêu cầu xác thực (đăng nhập). Nhiều endpoint yêu cầu quyền ADMIN.

#### 3.9.1 Danh sách nhân viên (phân trang, lọc)
`GET /api/admin/staff`

**Query Params**
| Tham số | Kiểu | Bắt buộc | Mô tả |
|---------|------|----------|-------|
| `query` | String | Không | Từ khóa tìm kiếm |
| `status` | `StaffStatus` | Không | Lọc theo trạng thái |
| `department` | String | Không | Lọc theo phòng ban |
| `page` | int | `0` | Trang |
| `size` | int | `20` (min 1, max 100) | Kích thước trang |

**Response** (`StaffPageResponse`) – HTTP 200
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `content` | List<StaffProfileResponse> | Danh sách nhân viên |
| `page` | int | Trang hiện tại |
| `size` | int | Kích thước trang |
| `totalElements` | long | Tổng số |
| `totalPages` | int | Tổng trang |

---

#### 3.9.2 Tạo hồ sơ nhân viên
`POST /api/admin/staff`

**Request Body** (`CreateStaffProfileRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `userId` | Integer | Có | ID người dùng liên kết |
| `employeeNumber` | String | Có | Mã nhân viên (3–32 ký tự, chỉ chữ số, chữ, gạch ngang) |
| `department` | String | Có | Phòng ban (tối đa 100 ký tự) |
| `jobTitle` | String | Có | Chức danh (tối đa 120 ký tự) |
| `joinedAt` | LocalDate | Có | Ngày vào làm |

**Ví dụ request:**
```json
{
  "userId": 15,
  "employeeNumber": "NV-001",
  "department": "Kỹ thuật",
  "jobTitle": "Kỹ sư thiết kế",
  "joinedAt": "2026-01-15"
}
```

**Response** (`StaffProfileResponse`) – HTTP 201

---

#### 3.9.3 Lấy chi tiết nhân viên
`GET /api/admin/staff/{id}`

**Path Variable:** `id` (Long)

**Response** (`StaffProfileResponse`) – HTTP 200

---

#### 3.9.4 Cập nhật hồ sơ nhân viên
`PATCH /api/admin/staff/{id}`

**Path Variable:** `id` (Long)

**Request Body** (`UpdateStaffProfileRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `department` | String | Không | Phòng ban (tối đa 100 ký tự) |
| `jobTitle` | String | Không | Chức danh (tối đa 120 ký tự) |
| `joinedAt` | LocalDate | Không | Ngày vào làm |

**Response** (`StaffProfileResponse`) – HTTP 200

---

#### 3.9.5 Thay đổi trạng thái nhân viên
`PATCH /api/admin/staff/{id}/status`

**Path Variable:** `id` (Long)

**Request Body** (`ChangeStaffStatusRequest`)
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `status` | `StaffStatus` | Có | Trạng thái mới (PENDING/ACTIVE/SUSPENDED/TERMINATED) |
| `reason` | String | Có | Lý do (tối đa 500 ký tự) |

**Ví dụ request:**
```json
{
  "status": "SUSPENDED",
  "reason": "Nghỉ việc tạm thời"
}
```

**Response** (`StaffProfileResponse`) – HTTP 200

---

#### 3.9.6 Cấu trúc `StaffProfileResponse`

| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Long | ID hồ sơ |
| `userId` | Integer | ID người dùng |
| `name` | String | Họ tên |
| `email` | String | Email |
| `role` | `Role` | Vai trò |
| `accountStatus` | `UserStatus` | Trạng thái tài khoản |
| `employeeNumber` | String | Mã nhân viên |
| `department` | String | Phòng ban |
| `jobTitle` | String | Chức danh |
| `status` | `StaffStatus` | Trạng thái nhân viên |
| `joinedAt` | LocalDate | Ngày vào làm |
| `createdBy` | Integer | Người tạo |
| `createdAt` | LocalDateTime | Ngày tạo |
| `updatedBy` | Integer | Cập nhật lần cuối bởi |
| `updatedAt` | LocalDateTime | Ngày cập nhật |

---

### 3.10 Hồ sơ nhân viên hiện tại (`/api/v1/me`)

#### 3.10.1 Lấy hồ sơ của bản thân
`GET /api/v1/me/staff-profile`  
**Yêu cầu:** Đăng nhập

**Response** (`MyStaffProfileResponse`) – HTTP 200
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Long | ID hồ sơ |
| `employeeNumber` | String | Mã nhân viên |
| `department` | String | Phòng ban |
| `jobTitle` | String | Chức danh |
| `status` | `StaffStatus` | Trạng thái |
| `joinedAt` | LocalDate | Ngày vào làm |

---

### 3.11 Danh tính người dùng hiện tại (`/api/v1`)

#### 3.11.1 Lấy thông tin bản thân (me)
`GET /api/v1/me`  
**Yêu cầu:** Đăng nhập

**Response** (`CurrentIdentityResponse`) – HTTP 200
| Trường | Kiểu | Mô tả |
|--------|------|-------|
| `id` | Integer | ID người dùng |
| `name` | String | Họ tên |
| `email` | String | Email |
| `role` | `Role` | Vai trò |
| `accountStatus` | `UserStatus` | Trạng thái tài khoản |
| `staffStatus` | String | Trạng thái nhân viên (nếu có) |
| `contexts` | String[] | Danh sách ngữ cảnh (`CUSTOMER`, `ADMIN`, `EMPLOYEE`) |
| `permissions` | String[] | Danh sách quyền hạn |

**Ví dụ response (khách hàng):**
```json
{
  "id": 10,
  "name": "Nguyen Van A",
  "email": "nguyenvana@example.com",
  "role": "USER",
  "accountStatus": "ACTIVE",
  "staffStatus": null,
  "contexts": ["CUSTOMER"],
  "permissions": []
}
```

**Ví dụ response (admin):**
```json
{
  "id": 1,
  "name": "Admin",
  "email": "admin@example.com",
  "role": "ADMIN",
  "accountStatus": "ACTIVE",
  "staffStatus": "ACTIVE",
  "contexts": ["CUSTOMER", "ADMIN"],
  "permissions": ["internal.admin.access"]
}
```

### 3.12 Admin thêm mô hình 3D mới (`/api`)

> Các endpoint này yêu cầu đăng nhập (header `Authorization: Bearer <token>`). Có thể được sử dụng bởi Admin hoặc Designer để thêm mô hình 3D mới vào hệ thống.

#### 3.12.1 Thêm mô hình nhà thiết kế (Designer Model)
`POST /api/designer-models`  
**Content-Type:** `multipart/form-data`  
**Yêu cầu:** Đăng nhập

**Request Parts** (chi tiết cấu trúc xem ở section 3.3.1)
| Phần | Kiểu | Bắt buộc | Mô tả |
|------|------|----------|-------|
| `data` | `DesignerModelRequest` (JSON) | Có | Thông tin mô hình |
| `images` | List<MultipartFile> | Có | Ảnh minh họa |
| `model` | MultipartFile | Có | File mô hình 3D (`.glb`, `.obj`, …) |

`DesignerModelRequest`:
| Trường | Kiểu | Bắt buộc | Mô tả |
|--------|------|----------|-------|
| `designerId` | Integer | Không | ID nhà thiết kế |
| `name` | String | Không | Tên mô hình |
| `slug` | String | Không | Slug |
| `description` | String | Không | Mô tả |
| `filePath3d` | String | Không | Đường dẫn file 3D |
| `sizeXMm` | Double | Không | Kích thước X (mm) |
| `sizeYMm` | Double | Không | Kích thước Y (mm) |
| `sizeZMm` | Double | Không | Kích thước Z (mm) |
| `weight` | Double | Không | Trọng lượng |
| `modelParts` | List<ModelPartRequest> | Không | Danh sách bộ phận |
| `images` | List<ModelImageRequest> | Không | Danh sách ảnh |
| `category` | String | Không | Thể loại |

**Response:** `ModelResponse` – HTTP 200 (xem cấu trúc ở section 3.3.7)

---

#### 3.12.2 Thêm mô hình AI (AI Model)
`POST /api/ai-models`  
**Content-Type:** `multipart/form-data`  
**Yêu cầu:** Đăng nhập

**Request Parts** (chi tiết cấu trúc xem ở section 3.3.5)
| Phần | Kiểu | Bắt buộc | Mô tả |
|------|------|----------|-------|
| `data` | `AIModelRequest` (JSON) | Có | Thông tin mô hình AI |
| `images` | List<MultipartFile> | Có | Ảnh |
| `model` | MultipartFile | Có | File mô hình 3D |

`AIModelRequest` có cấu trúc tương tự `DesignerModelRequest`, thay `designerId` bằng `ownerId`.

**Response:** `ModelResponse` – HTTP 200

---

#### 3.12.3 Xuất bản mô hình nhà thiết kế (Admin only)
`POST /api/admin/designer-models/{id}/publish`

**Path Variable:** `id` (Integer) – ID mô hình cần xuất bản

**Request:** Không body  
**Yêu cầu:** Đăng nhập (Quyền ADMIN)

**Response:** HTTP 204 No Content (không body)

> Sau khi gọi endpoint này, mô hình sẽ được chuyển trạng thái sang công khai / hoạt động, hiển thị cho khách hàng.

---

## 4. Ví dụ gọi API (JavaScript / Fetch)

### 4.1 Đăng nhập
```js
const res = await fetch(`${BASE_URL}/api/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: 'user@example.com', password: 'Abc12345' })
});
const data = await res.json(); // { token, email, name, role, status }
localStorage.setItem('token', data.token);
```

### 4.2 Lấy danh sách sản phẩm
```js
const res = await fetch(`${BASE_URL}/api/products?page=0&size=12&q=ghe`);
const data = await res.json(); // PaginatedProductResponse
```

### 4.3 Tạo đơn hàng
```js
const res = await fetch(`${BASE_URL}/api/orders`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`
  }
});
const order = await res.json(); // OrderResponse
```

### 4.4 Tạo thanh toán VNPay/MoMo
```js
const res = await fetch(`${BASE_URL}/api/payments`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`
  },
  body: JSON.stringify({ orderId: 2001, method: 'VNPAY' })
});
const { paymentUrl, paymentId } = await res.json();
// window.location.href = paymentUrl;
```

### 4.5 Upload mô hình 3D (multipart)
```js
const formData = new FormData();
formData.append('data', new Blob([JSON.stringify({
  name: 'Mô hình AI',
  category: 'Đồ chơi',
  ...
})], { type: 'application/json' }));
formData.append('model', file3D);
images.forEach(img => formData.append('images', img));

const res = await fetch(`${BASE_URL}/api/ai-models`, {
  method: 'POST',
  headers: { 'Authorization': `Bearer ${token}` },
  body: formData
});
const model = await res.json(); // ModelResponse
```

---

## 5. Lưu ý cho frontend

1. **Token:** Lưu `token` từ `AuthResponse` vào `localStorage` / `sessionStorage` và gửi kèm header `Authorization: Bearer <token>` cho mọi API yêu cầu đăng nhập.
2. **Multipart:** Khi gửi `multipart/form-data`, **không** tự đặt header `Content-Type` (trình duyệt sẽ tự đặt boundary). Chỉ gán `Authorization`.
3. **Enum:** Gửi giá trị enum theo dạng chuỗi in hoa, ví dụ `"VNPAY"`, `"ACTIVE"`, `"PENDING"`.
4. **Ngày tháng:** Định dạng ISO 8601 (`yyyy-MM-dd` cho `LocalDate`, `yyyy-MM-dd'T'HH:mm:ss` cho `LocalDateTime`).
5. **Lỗi:** Khi backend trả 400/401/403/404/500, body thường chứa thông báo lỗi (tiếng Việt). Hãy parse và hiển thị cho người dùng.
6. **VNPay/MoMo:** Sau khi nhận `paymentUrl`, frontend phải **redirect** người dùng đến URL đó. Sau khi xong, cổng thanh toán sẽ quay về `/api/payments/vnpay/return` hoặc `/api/payments/momo/return`.

---

## 6. Bản tóm tắt endpoint

| # | Method | Endpoint | Yêu cầu auth | Mô tả |
|---|--------|----------|--------------|-------|
| 1 | POST | `/api/auth/register` | Không | Đăng ký |
| 2 | POST | `/api/auth/login` | Không | Đăng nhập |
| 3 | GET | `/api/auth/ping` | Không | Kiểm tra hệ thống |
| 4 | GET | `/api/products` | Không | Danh sách sản phẩm |
| 5 | GET | `/api/products/{id}` | Không | Chi tiết sản phẩm |
| 6 | POST | `/api/designer-models` | Có (multipart) | Admin/Designer thêm mô hình thiết kế mới |
| 7 | POST | `/api/admin/designer-models/{id}/publish` | Có (ADMIN) | Xuất bản mô hình thiết kế |
| 8 | GET | `/api/designer-models` | Không | Danh sách mô hình thiết kế |
| 9 | GET | `/api/designer-models/{id}` | Không | Chi tiết mô hình thiết kế |
| 10 | POST | `/api/ai-models` | Có (multipart) | Tạo mô hình AI |
| 11 | GET | `/api/ai-models/{id}` | Có | Chi tiết mô hình AI |
| 12 | POST | `/api/orders` | Có | Tạo đơn từ giỏ hàng |
| 13 | POST | `/api/orders/from-cart-item/{cartItemId}` | Có | Tạo đơn từ 1 item |
| 14 | PATCH | `/api/orders/{id}/status` | Có | Cập nhật trạng thái đơn |
| 15 | GET | `/api/orders/{id}` | Có | Chi tiết đơn hàng |
| 16 | POST | `/api/payments` | Có | Tạo yêu cầu thanh toán |
| 17 | GET | `/api/payments/vnpay/return` | Không | VNPay return |
| 18 | GET | `/api/payments/momo/return` | Không | MoMo return |
| 19 | POST | `/api/payments/momo/ipn` | Không | MoMo IPN |
| 20 | GET | `/api/materials/` | Không | Danh sách vật liệu |
| 21 | POST | `/api/materials/create` | Không | Tạo vật liệu |
| 22 | DELETE | `/api/materials/{id}/remove` | Không | Xoá vật liệu |
| 23 | POST | `/api/materials/colors/{id}` | Không | Thêm màu cho vật liệu |
| 24 | DELETE | `/api/materials/{id}/remove-color/{colorId}` | Không | Xoá màu vật liệu |
| 25 | POST | `/api/colors/create` | Không | Tạo màu |
| 26 | GET | `/api/colors/{colorName}` | Không | Lấy màu theo tên |
| 27 | PUT | `/api/accounts/{id}/status` | Có | Cập nhật trạng thái TK |
| 28 | PUT | `/api/accounts/{id}/role` | Có | Cập nhật vai trò TK |
| 29 | GET | `/api/admin/staff` | Có | Danh sách nhân viên |
| 30 | POST | `/api/admin/staff` | Có | Tạo hồ sơ NV |
| 31 | GET | `/api/admin/staff/{id}` | Có | Chi tiết NV |
| 32 | PATCH | `/api/admin/staff/{id}` | Có | Cập nhật hồ sơ NV |
| 33 | PATCH | `/api/admin/staff/{id}/status` | Có | Thay đổi trạng thái NV |
| 34 | GET | `/api/v1/me/staff-profile` | Có | Hồ sơ bản thân |
| 35 | GET | `/api/v1/me` | Có | Thông tin bản thân |

---

> **Lưu ý:** File này được sinh từ source code controllers và DTOs của dự án `2Dto3D_BE`. Nếu backend thay đổi, hãy cập nhật lại file này để đồng bộ với frontend.
