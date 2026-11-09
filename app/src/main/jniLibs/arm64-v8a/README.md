# arm64-v8a 预编译原生库

将下列 so 放到本目录后，再用 Android Studio Sync / Build。

## 必放文件

| 文件 | 来源 |
|------|------|
| `librk_dl.so` | `rk_inference` 执行 `./build-android.sh lib` |
| `librknnrt.so` | `rknn-toolkit2` → `rknpu2/runtime/Android/librknn_api/arm64-v8a/` |
| `librga.so` | `rknn-toolkit2` → `rknpu2/examples/3rdparty/rga/libs/AndroidNdk/arm64-v8a/` |
| `librk_dl_jni.so` | 由工程 CMake 编译；`assembleDebug` 会自动同步到本目录 |
| OpenCV so | CMake 链接的 OpenCV Android 包（如 `libopencv_java4.so` 及依赖） |

## 编译 rk_dl

```bash
cd /path/to/rk_inference
export ANDROID_NDK_PATH=...   # 推荐 NDK r18/r19
export RK_SDK_ROOT=...        # rknn-toolkit2 根目录
./build-android.sh lib
```

产物一般在 `build/Android/lib/`（以实际 install 路径为准）。

## 说明

- 仅支持 **arm64-v8a**（RK3588 等）
- 缺少 so 时 App 仍可编译运行相机预览，人脸推理会初始化失败并提示
- 模型已在 `assets/models/`（RetinaFace / VGGFace / facial_expression）

## 16 KB 页对齐（Google Play / Android 15+）

自 2025-11-01 起，面向 Android 15+ 上架的应用要求 native so 的 LOAD 段 `Align ≥ 0x4000`（16 KB）。

| 文件 | 状态 |
|------|------|
| `librk_dl_jni.so` | 工程 CMake 已启用 `-Wl,-z,max-page-size=16384` |
| `librk_dl.so` / `librga.so` | 当前为 64 KB 对齐，满足要求 |
| `librknnrt.so` | **预编译闭源**；旧版多为 4 KB（`Align 0x1000`）。需从 Rockchip 获取已按 16 KB 重编的 runtime，或向厂商索取 |

校验：

```bash
llvm-readelf -lW path/to/*.so | grep LOAD
# Align 列应为 0x4000 或更大（如 0x10000）
```
