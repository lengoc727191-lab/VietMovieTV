# VietMovie TV (Android TV Native App - Kotlin + Media3 ExoPlayer)

Ứng dụng xem phim trực tiếp trên Android TV / TV Box sử dụng nguồn API miễn phí từ `phimapi.com` (KKPhim), phát luồng `.m3u8` mượt mà bằng Media3 ExoPlayer và điều khiển 100% bằng Remote TV (D-Pad).

## Cách tạo file APK tự động trên GitHub (không cần Android Studio)
1. Giải nén file ZIP này ra thư mục máy tính.
2. Đăng nhập [GitHub](https://github.com), tạo một Repository mới (có thể để Private hoặc Public).
3. Tải toàn bộ thư mục/file bên trong lên Repository (đảm bảo có cả thư mục ẩn `.github/workflows/build-apk.yml`).
4. Vào tab **Actions** trên GitHub -> đợi khoảng 2–3 phút để GitHub tự động biên dịch.
5. Khi hiện dấu tích xanh, bấm vào lượt chạy đó -> kéo xuống mục **Artifacts** -> tải **VietMovieTV-APK** về, giải nén lấy file `VietMovieTV.apk` và cài lên TV.
