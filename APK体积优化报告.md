# APK 体积优化报告

> 项目：Rust + Jetpack Compose 计数器应用  
> 基准：Debug APK 11.9 MB（未压缩 30.5 MB）  
> 日期：2026-06-02

---

## 一、优化结果总览

| 版本 | 压缩大小 | 未压缩 | 相比 Debug 减少 | 核心措施 |
|---|---|---|---|---|
| Debug | **11.9 MB** | 30.5 MB | — | 无 |
| Release 无 R8 | 7.9 MB | ~21 MB | 34% | 移除调试符号 |
| **Release + R8 + 裁剪** | **4.2 MB** | 10.1 MB | **65%** | R8 + 资源精简 |
| AAB (Play Store) | 7.0 MB | — | — | 动态分发（每设备~4MB） |
| Rust .so 体积优化 | 271 KB | — | 仅节省 3% | LTO + opt=z |

> **结论：最大优化空间在 Kotlin/DEX 层（占 92%），不在 Rust 层（占 3%）。R8 + 资源裁剪是关键。**

---

## 二、APK 体积构成分析

### Debug APK（30.5 MB 未压缩）

```
┌──────────────────────────────────────────────────┐
│ DEX (Kotlin/Java 字节码)   ████████████████ 28.2M │ 92.5%
│ Native .so (arm64/armv7)   █ 0.3M                │  1.0%
│ resources.arsc             ▌ 0.5M                │  1.5%
│ Kotlin builtins            ▏ 0.05M               │  0.2%
│ META-INF LICENSE           ▏ 0.05M               │  0.2%
│ WebP 图标                  ▏ 0.03M               │  0.1%
│ 其他                        ▏ 1.3M               │  4.5%
└──────────────────────────────────────────────────┘
```

### 各组件详细

| 组件 | 大小 | 说明 |
|---|---|---|
| classes.dex | 19.5 MB | 主 dex — Compose、Kotlin stdlib、Material3 |
| classes8.dex | 10.0 MB | 多 dex — 未压缩的库依赖 |
| 其他 dex | 0.2 MB | classes2~7 |
| **dex 合计** | **29.6 MB** | **占 97%**（含 Compose BOM 等重型库） |
| libnative_lib.so (arm64) | 279 KB | Rust JNI 库 |
| libnative_lib.so (armv7) | 2.8 KB | armv7 精简版 |
| libandroidx.graphics.path.so ×4 | 37 KB | x86/x86_64/arm64/armv7 四份 |

> ⚠️ Debug 构建中 Compose 依赖体积极大，但这是开发阶段的正常现象。Compose 库需要 R8 裁剪才能显著缩小。

---

## 三、6 项优化措施详解

### 优化 1：R8 代码混淆与裁剪

```kotlin
// app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true   // ← 开启 R8
    }
}
```

| 前 | 后 | 节省 |
|---|---|---|
| 29.6 MB dex | 9.2 MB dex | **69%** |

R8 移除未使用的类、方法、字段，并将类名混淆为短名（如 `a`, `b`, `c`...）。Compose 库从 20MB+ 压缩到约 8MB。

> ⚠️ 需要 ProGuard 规则保护 JNI native 方法和 Compose 类。

---

### 优化 2：资源裁剪

```kotlin
buildTypes {
    release {
        isShrinkResources = true  // ← 开启资源裁剪
    }
}
```

- 移除未引用的 drawable、layout、string 等资源
- 资源文件名被混淆为短名（如 `-6.webp`, `j_.webp`, `0w.xml`）
- resources.arsc 从 479KB → 439KB

---

### 优化 3：ABI 拆分（剔除模拟器库）

```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a")  // 仅真机架构
        isUniversalApk = false  // 每个 ABI 独立 APK
    }
}
```

| 措施 | 效果 |
|---|---|
| 剔除 x86 + x86_64 .so | 节省 20 KB（来自 androidX 依赖） |
| 独立 APK | arm64 用户只需下载 4.2 MB，而非包含全部 ABI |

> ⚠️ ABI 拆分 + `isUniversalApk = false` 会生成多个 APK，适用于直接分发。如果要上传 Google Play，用 AAB 格式更好（Google Play 自动按 ABI 分发）。

---

### 优化 4：AAB 格式（Google Play 分发）

```bash
./gradlew bundleRelease
```

- AAB 打包所有 ABI 的 .so，体积 7.0 MB
- Google Play 仅为用户设备下载对应 ABI 的 split APK（约 4 MB）
- 比手动 ABI 拆分更方便，Play 还会自动优化

---

### 优化 5：Rust .so 体积优化

```toml
# native_lib/Cargo.toml
[profile.release]
opt-level = "z"      # 最小体积优化
lto = true           # 链接时优化
codegen-units = 1    # 更好的内联
strip = true         # 剥离符号
panic = "abort"      # 移除 unwind 代码
```

| 前 | 后 | 节省 |
|---|---|---|
| 279 KB | 271 KB | **3%** (8 KB) |

> 💡 对于简单 JNI 函数（仅 count+1），Rust .so 已经很精简。主要体积来自 jni crate 的 Java 类型绑定 (~250KB)。更复杂的 Rust 库（如图像处理、加密）LTO 效果会更显著。

---

### 优化 6：JNI 库 strip（未生效）

构建日志提示：
```
Unable to strip the following libraries, packaging them as they are:
libnative_lib.so
```

Android SDK 的 `aarch64-linux-android-strip` 无法处理 Rust 编译的 ELF。解决方案：

```bash
# 用 NDK 自带的 strip 工具手动处理
$NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip \
  --strip-all libnative_lib.so
```

> 实测：strip 后从 271KB → 158KB（节省 42%）！但需要集成到构建流程中。

---

## 四、效果对比图

```
Debug          ████████████████████████████ 11.9 MB
Release(无R8)  ██████████████████ 7.9 MB     (-34%)
Release+R8     █████████ 4.2 MB              (-65%)
Release+R8+strip███████ 4.0 MB               (-66%)
AAB(per device)███████ ~4.0 MB               (-66%)
```

---

## 五、最终推荐配置

### app/build.gradle.kts（生产环境）

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ABI 拆分（直接分发 APK 时用）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}
```

### native_lib/Cargo.toml（生产环境）

```toml
[profile.release]
opt-level = "z"
lto = true
codegen-units = 1
strip = true
panic = "abort"
```

### app/proguard-rules.pro（最小化）

```prolog
# JNI 保护
-keep class com.example.myrustapp.NativeLib { native <methods>; }

# Compose 保护
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# 保留调试信息
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
```

---

## 六、优化清单

| # | 措施 | 节省 | 难度 | 推荐 |
|---|---|---|---|---|
| 1 | 开启 R8 (`isMinifyEnabled = true`) | ~65% dex | 低 | ✅ 必须 |
| 2 | 资源裁剪 (`isShrinkResources = true`) | ~40KB | 低 | ✅ 必须 |
| 3 | ABI 拆分 | ~20KB .so | 低 | ✅ 推荐 |
| 4 | Rust LTO + opt=z | ~3% .so | 低 | ✅ 推荐 |
| 5 | 手动 llvm-strip | ~42% .so | 中 | ⚡ 可选 |
| 6 | 移除未使用依赖 (Navigation3) | 少量 dex | 中 | ⚡ 可选 |
| 7 | AAB 替代 APK | 每设备~4MB | 低 | ✅ 上架 Play 必须 |
| 8 | kotlin-builtins 剔除 | ~50KB | 高 | ❌ 不建议 |

---

## 七、不要做的事

- ❌ **不要手动删除 kotlin_builtins** — Kotlin 运行时依赖它们，删除会导致崩溃
- ❌ **不要用 debug 版 APK 评估体积** — debug 包含大量调试数据，不具备参考意义
- ❌ **不要在 release 中保留 x86 ABI** — 几乎没有 x86 Android 设备，白白增加体积
- ❌ **不要省略 ProGuard 规则** — R8 可能移除 JNI 方法或 Compose 类，导致运行时崩溃

---

## 八、体积与性能权衡

| Rust LTO | opt-level="z" | opt-level="s" | opt-level=3 |
|---|---|---|---|
| 体积 | 最小 | 较小 | 最大 |
| 性能 | 较慢 | 较快 | 最快 |
| 适用场景 | 简单 JNI | 通用推荐 | 计算密集型 |

> 本项目是简单的 count+1，opt="z" 和 opt=3 差距可忽略。对于加密/图像处理等场景，建议 opt="s" 做折中。

---

> 📄 完整开发记录见 [Rust-Android-Dev-Guide.md](./Rust-Android-Dev-Guide.md)  
> 📋 开发提示词见 [rust_kotlin_android_开发提示词.md](./rust_kotlin_android_开发提示词.md)
