# Rust + Kotlin Android 从 0 到 1

> 从空白文件夹到 Google Play 可上架应用，每一步的完整记录。  
> 最终产物：4.2MB APK，Rust JNI 驱动核心逻辑，Jetpack Compose 渲染 UI。

---

## 目录

1. [环境搭建](#1-环境搭建)
2. [创建项目骨架](#2-创建项目骨架)
3. [Rust 原生库](#3-rust-原生库)
4. [Kotlin JNI 桥接](#4-kotlin-jni-桥接)
5. [Compose UI](#5-compose-ui)
6. [编译与构建](#6-编译与构建)
7. [安装到设备](#7-安装到设备)
8. [体积优化](#8-体积优化)
9. [GitHub 发布](#9-github-发布)
10. [完整源码清单](#10-完整源码清单)
11. [事故记录表](#11-事故记录表)

---

## 1. 环境搭建

### 必须安装

```bash
# ---- Rust ----
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# Android 交叉编译目标
rustup target add aarch64-linux-android armv7-linux-androideabi

# NDK 编译工具
cargo install cargo-ndk

# ---- JDK 17 (不要用 21+) ----
# Windows
scoop install openjdk17

# macOS
brew install openjdk@17

# ---- Android SDK (通过 scoop 或手动) ----
scoop install android-clt
# 或下载 Android Studio，确保 Build-Tools ≥36, Platform ≥24

# ---- Gradle (可选，wrapper 下载慢时的备选) ----
scoop install gradle

# ---- ADB ----
# 包含在 android-clt 中，验证:
adb --version   # ≥1.0.41
```

### 验证

```bash
rustc --version      # ≥1.96
cargo --version      # ≥1.96
java --version       # 17.x
gradle --version     # ≥9.0  (或 ./gradlew --version)
adb --version        # ≥1.0.41
```

---

## 2. 创建项目骨架

### 2.1 创建 Android 项目

用 Android Studio 新建一个 Compose 项目，或手动创建：
[https://developer.android.google.cn/tools/agents/android-cli?hl=zh-CN](使用android cli AI友好)

```android create --name MyRustApp --output ./MyRustApp```
```
MyRustApp/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/myrustapp/
│       │   └── MainActivity.kt
│       └── jniLibs/           # ← 这是 .so 的存放位置
│           ├── arm64-v8a/
│           └── armeabi-v7a/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
    └── wrapper/
```

### 2.2 创建 Rust 库

```bash
cd MyRustApp
cargo new native_lib --lib
```

结构变为：
```
MyRustApp/
├── app/                # Android 应用
├── native_lib/         # Rust 原生库
│   ├── Cargo.toml
│   └── src/lib.rs
└── ...
```

### 2.3 关键配置文件

**app/build.gradle.kts** — 注意 namespace 必须和 Kotlin package 一致：
```kotlin
android {
    namespace = "com.example.myrustapp"   // ← 关键！
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.myrustapp"   // ← 同上
        minSdk = 24
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
}
kotlin { jvmToolchain(17) }
```

**native_lib/Cargo.toml**：
```toml
[package]
name = "native_lib"
edition = "2024"

[lib]
crate-type = ["cdylib"]      # ← 动态库，不是 rlib！

[dependencies]
jni = "0.22"                 # ← JNI 绑定

[profile.release]
opt-level = "z"
lto = true
codegen-units = 1
strip = true
panic = "abort"
```

---

## 3. Rust 原生库

### 3.1 写 JNI 函数

**native_lib/src/lib.rs**：
```rust
use jni::objects::JClass;
use jni::sys::jint;
use jni::EnvUnowned;

/// JNI 函数名格式: Java_<包名>_<类名>_<方法名>
/// 包名中的 . 替换为 _，类名中的 _ 用 _1 转义
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_myrustapp_NativeLib_nativeIncrement(
    _env: EnvUnowned,      // jni 0.22: EnvUnowned 不是 JNIEnv！
    _class: JClass,
    count: jint,
) -> jint {
    count + 1
}
```

### 3.2 JNI 命名规则

| Kotlin 声明 | Rust 函数名 |
|---|---|
| `package com.example.myrustapp` | `Java_com_example_myrustapp_` 前缀 |
| `object NativeLib` | `_NativeLib_` |
| `external fun nativeIncrement(count: Int): Int` | `nativeIncrement` |
| **完整** | `Java_com_example_myrustapp_NativeLib_nativeIncrement` |

---

## 4. Kotlin JNI 桥接

### 4.1 NativeLib.kt

```kotlin
package com.example.myrustapp

object NativeLib {
    private var loaded = false

    init {
        try {
            System.loadLibrary("native_lib")   // 加载 libnative_lib.so
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            loaded = false   // 加载失败不崩溃
        }
    }

    fun isLoaded(): Boolean = loaded

    // 公开 API：带容错 fallback
    fun increment(count: Int): Int =
        if (loaded) nativeIncrement(count)
        else count + 1                     // ← Kotlin 纯实现兜底

    @JvmStatic
    private external fun nativeIncrement(count: Int): Int
}
```

> ⚠️ **为什么需要 fallback**：手机型号千奇百怪，某个 ABI 的 .so 可能损坏/缺失。有 fallback 至少应用不闪退。

### 4.2 MainActivity.kt

```kotlin
package com.example.myrustapp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyRustAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CounterScreen()
                }
            }
        }
    }
}
```

> ⚠️ 简单应用不要引入 Navigation3，直接在 Activity 里 setContent。

---

## 5. Compose UI

### CounterScreen.kt

```kotlin
@Composable
fun CounterScreen(modifier: Modifier = Modifier) {
    var count by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "$count", fontSize = 72.sp)

        Spacer(Modifier.height(24.dp))

        // 状态指示器
        Text(
            text = if (NativeLib.isLoaded()) "✓ Rust JNI" else "✗ Fallback",
            fontSize = 12.sp,
            color = if (NativeLib.isLoaded()) Color(0xFF4CAF50)
                    else Color(0xFFFF5722)
        )

        Button(
            onClick = { count = NativeLib.increment(count) },
            modifier = Modifier.size(200.dp, 64.dp)
        ) {
            Text("+1", fontSize = 24.sp)
        }
    }
}
```

---

## 6. 编译与构建

### 6.1 编译 Rust → .so

```bash
cd native_lib
cargo ndk \
  --target aarch64-linux-android \
  --target armv7-linux-androideabi \
  --platform 24 \
  -- build --release
```

输出：
```
target/aarch64-linux-android/release/libnative_lib.so   (arm64, ~271KB)
target/armv7-linux-androideabi/release/libnative_lib.so  (armv7, ~3KB)
```

### 6.2 复制到 jniLibs

```bash
cp target/aarch64-linux-android/release/libnative_lib.so \
   ../app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libnative_lib.so \
   ../app/src/main/jniLibs/armeabi-v7a/
```

### 6.3 构建 APK

```bash
cd ..
export JAVA_HOME="C:/path/to/openjdk17"
gradle assembleRelease --no-daemon
# 或: ./gradlew assembleRelease

# 输出: app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
```

### 6.4 验证 .so 在 APK 中

```bash
unzip -l app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk | grep libnative_lib.so
# 应输出: 277416 ... lib/arm64-v8a/libnative_lib.so
```

### 6.5 验证 JNI 符号

```bash
nm -D app/src/main/jniLibs/arm64-v8a/libnative_lib.so | grep Java_
# 应输出: 000000000016be4 T Java_com_example_myrustapp_NativeLib_nativeIncrement
```

---

## 7. 安装到设备

### Wi-Fi 调试 (Android 11+)

```bash
# 1. 手机: 设置 → 开发者选项 → 无线调试 → 开启
# 2. 手机: 点击「使用配对码配对设备」→ 记下 IP:端口 和 6位配对码
# 3. 电脑:
adb pair <ip>:<配对端口> <配对码>
adb connect <ip>:<连接端口>

# 4. 安装
adb -s <device> install -r app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk

# 5. 启动
adb -s <device> shell am start -n com.example.myrustapp/.MainActivity

# 6. 查看日志
adb -s <device> logcat -s NativeLib:D CounterScreen:D
```

### USB 调试（更稳定，推荐首次使用）

```bash
# USB 连接手机 → 允许 USB 调试
adb devices                    # 确认连接
adb install -r <apk>
```

---

## 8. 体积优化

### 优化效果总览

| 版本 | 大小 | 措施 |
|---|---|---|
| Debug | 11.9 MB | 无 |
| Release 无 R8 | 7.9 MB | 移除调试信息 |
| **Release + R8 + 裁剪** | **4.2 MB** | R8 + ABI 拆分 |
| Rust .so 优化 | 额外 -8KB | LTO + opt=z |

### 关键配置（三件套）

```kotlin
// app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true      // ← R8 代码裁剪
        isShrinkResources = true    // ← 资源精简
    }
}
splits {
    abi {
        isEnable = true             // ← ABI 拆分（剔除 x86）
        include("arm64-v8a", "armeabi-v7a")
    }
}
```

```toml
# native_lib/Cargo.toml
[profile.release]
opt-level = "z"      # 最小体积
lto = true           # 链接时优化
panic = "abort"      # 移除 unwind 代码
```

---

## 9. GitHub 发布

```bash
cd MyRustApp
git init
git add -A
git commit -m "feat: Rust + Compose Android 计数器"

# 创建仓库并推送
gh repo create MyRustApp --public --push
```

---

## 10. 完整源码清单

### 最终文件结构

```
MyRustApp/
├── native_lib/
│   ├── Cargo.toml          # [lib] cdylib, jni 0.22, profile.release
│   └── src/lib.rs          # JNI: Java_..._nativeIncrement
├── app/
│   ├── build.gradle.kts    # namespace=com.example.myrustapp, R8 on
│   ├── proguard-rules.pro  # JNI/Compose 保护规则
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/myrustapp/
│       │   ├── MainActivity.kt      # 入口 (Compose)
│       │   └── NativeLib.kt         # JNI 桥接 + 容错 fallback
│       │   └── ui/main/
│       │       └── CounterScreen.kt # 计数 UI
│       └── jniLibs/
│           ├── arm64-v8a/libnative_lib.so
│           └── armeabi-v7a/libnative_lib.so
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── Rust-Android-Dev-Guide.md              # 开发记录
├── APK体积优化报告.md                      # 体积优化
├── rust_kotlin_android_开发提示词.md       # 速查模板
└── Rust-Kotlin-Android-从0到1.md          # 本文
```

### 8 个关键文件

| # | 文件 | 核心内容 |
|---|---|---|
| 1 | `native_lib/Cargo.toml` | `crate-type=["cdylib"]`, `jni="0.22"`, release profile |
| 2 | `native_lib/src/lib.rs` | `#[unsafe(no_mangle)] fn Java_..._nativeIncrement` |
| 3 | `app/build.gradle.kts` | namespace, isMinifyEnabled, ABI splits |
| 4 | `app/proguard-rules.pro` | `-keep class NativeLib { native <methods>; }` |
| 5 | `app/.../NativeLib.kt` | loadLibrary + try-catch + fallback |
| 6 | `app/.../MainActivity.kt` | setContent {CounterScreen()} |
| 7 | `app/.../CounterScreen.kt` | Button onClick → NativeLib.increment |
| 8 | `app/src/main/jniLibs/` | 两套 .so（arm64 + armv7） |

---

## 11. 事故记录表

开发过程中遇到的全部错误及修复：

| # | 错误 | 原因 | 修复 | 耗时 |
|---|---|---|---|---|
| 1 | `cannot find module jni` | Cargo.toml `[dependencies]` 格式损坏 | 节头独占一行 | 5min |
| 2 | `unsafe attribute used without unsafe` | Rust 2024 edition | `#[unsafe(no_mangle)]` | 2min |
| 3 | `deprecated type alias JNIEnv` | jni 0.22 API 变更 | 改用 `EnvUnowned` | 3min |
| 4 | Gradle 下载 136MB 超时 | 网络 + 锁文件 | 用本地 Gradle 9.5.1 | 30min |
| 5 | `Cannot find Java {languageVersion=17}` | 系统 JDK 是 21 | `scoop install openjdk17` | 5min |
| 6 | Wi-Fi ADB 频繁断开 | 手机息屏 | 改为 USB + 配对码方式 | 20min |
| 7 | `Activity class does not exist` | namespace ≠ Kotlin package | 统一为 `com.example.myrustapp` | 10min |
| 8 | 应用闪退（猜测） | JNI 加载失败无保护 | NativeLib 加 try-catch + fallback | 15min |
| 9 | `adb pair` 报 protocol fault | Windows ADB 与 OPPO 不兼容 | `adb kill-server` 后重试 + USB 配对 | 15min |
| 10 | ABI split + AAB 冲突 | splits 与 bundleRelease 互斥 | 分开构建 | 5min |
| 11 | cargo-zigbuild 找不到 libc | zig 0.14 不支持 Android | 改用 NDK clang linker | 20min |

**总耗时约 2.5 小时**，其中环境问题占 60%、JNI 命名问题 20%、其他 20%。

---

## 附录：速查命令

```bash
# 编译 Rust
cd native_lib && cargo ndk --target aarch64-linux-android --target armv7-linux-androideabi --platform 24 -- build --release

# 构建 APK
cd .. && export JAVA_HOME=<jdk17> && gradle assembleRelease --no-daemon

# 安装
adb connect <ip>:<port>
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk

# 验证 JNI 符号
nm -D app/src/main/jniLibs/arm64-v8a/libnative_lib.so | grep Java_

# 查看日志
adb logcat -s NativeLib:D CounterScreen:D AndroidRuntime:E
```

---

> 📂 仓库：[github.com/lilyco-42/MyRustApp](https://github.com/lilyco-42/MyRustApp)  
> 📄 配套文档：开发提示词 / 体积优化报告 / 详细开发记录
