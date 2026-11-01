---
title: GLB-Support
tags: [mobile, native, 3d]
---

# GLB-Support

Model 3D đọc bằng parser tự viết (`GlbModel.kt`, `object GlbParser`) và vẽ bằng `GlbRenderer.kt` (GLES2). Parser không phụ thuộc OpenGL hay bitmap Android để có thể test trên JVM.

Chỉ nhận **glTF 2.0 dạng binary (`.glb`)**. File `.gltf` dạng text, `.obj`, `.vrx` bị lọc từ phía JS ([[Coordinate-Conversion]]) và parser cũng từ chối nếu sai magic.

## Hỗ trợ
- Mesh tam giác tĩnh, cây node (matrix hoặc TRS); không có node thì vẽ mọi mesh với ma trận đơn vị.
- Vật liệu PBR nhưng **chỉ dùng** `baseColorFactor`, `baseColorTexture` (TEXCOORD_0) và `emissiveFactor`.
- `alphaMode` OPAQUE / MASK / BLEND, `alphaCutoff`, `doubleSided`.
- Extension: `KHR_materials_unlit`, `KHR_mesh_quantization`, `KHR_texture_transform`, `KHR_materials_emissive_strength`, `EXT_texture_webp`.
- Texture nhúng trong file (PNG/JPEG/WebP), sampler wrap; component float/int/short/byte, kể cả normalized.
- Index 32-bit; máy không có `GL_OES_element_index_uint` thì bung thành mảng đỉnh tuần tự.
- Thiếu normal thì tự tính.

## Không hỗ trợ (ghi vào `warnings`, xem log `OverlayRenderer`)
- Nén Draco, meshopt; texture KTX2/Basis.
- Animation (vẽ tư thế tĩnh), skinning (vẽ ở tư thế bind, có thể sai tỉ lệ).
- Sparse accessor, buffer/ảnh ngoài file (`uri`), primitive mode khác TRIANGLES, TEXCOORD_1.

## Shading
Không phải PBR: `baseColor` (factor × texture) + ánh sáng hemisphere và một đèn hướng cố định trong không gian thế giới ARCore (trục Y hướng lên). Kim loại sẽ trông như nhựa.

- Texture được thu nhỏ ≤ 2048 và đưa về kích thước lũy thừa 2 (GLES2 cần để dùng REPEAT + mipmap).
- Vẽ 2 lượt: OPAQUE + MASK (ghi depth), rồi BLEND (không ghi depth).
- Scale âm (lật gương) thì đảo front face để culling vẫn đúng.
- Sau khi vẽ phải unbind buffer: các renderer khác dùng con trỏ client-side.

## Khuyến nghị cho designer
Xuất `.glb` không nén, texture PNG/JPEG nhúng trong file, không animation. Kích thước thật của model (đơn vị mét) nhân với scale editor × 0.15 m.

Liên quan: [[Overlay-Rendering]], [[Mobile-Roadmap]].
