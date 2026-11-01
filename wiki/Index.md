---
title: Index
tags: [hub, mobile]
---

# ARMobile — Bản đồ tài liệu

Điểm vào tài liệu của app quét AR cho end-user. Mỗi note ngắn, nói về một thứ, nối sang note liên quan bằng wikilink. Mở thư mục `wiki/` bằng Obsidian để xem graph.

Backend, web designer tool và kiến trúc tổng thể nằm ở repo chính `Augmented-Reality` (thư mục `wiki/`, note `Index`, `Architecture`, `Trigger-Database`, `API-Public-Scan`).

## Tổng quan
- [[App-Architecture]] — app gồm những lớp nào, dữ liệu chạy qua đâu
- [[Mobile-Setup]] — cài và chạy trên máy Android
- [[Mobile-Roadmap]] — việc còn thiếu

## Lớp JavaScript (`src/`)
- [[Scan-Flow]] — màn `ARScanScreen`: quyền camera, tải database, chọn project đang nhìn thấy
- [[API-Client]] — gọi backend, xử lý lỗi và server Render đang ngủ
- [[Coordinate-Conversion]] — đổi toạ độ editor sang toạ độ marker ARCore

## Lớp native Android (`android/.../artracking/`)
- [[Native-ArCameraView]] — view camera + ARCore session, props và event với JS
- [[Trigger-DB-Cache]] — tải, kiểm tra sha256 và cache file `.imgdb`
- [[Overlay-Rendering]] — vẽ ảnh, video, model lên marker bằng GLES2
- [[GLB-Support]] — parser `.glb` tự viết: hỗ trợ gì, không hỗ trợ gì

## Vận hành
- [[Mobile-Troubleshooting]] — lỗi hay gặp và thông báo lỗi trong app
