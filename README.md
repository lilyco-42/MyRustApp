# MyRustApp — Rust 驱动的 Android 计数器应用

[![Rust](https://img.shields.io/badge/Rust-1.96-orange?logo=rust)](https://www.rust-lang.org/)
[![Android](https://img.shields.io/badge/Android-24%2B-green?logo=android)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-purple?logo=kotlin)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-latest-blue?logo=jetpackcompose)](https://developer.android.com/compose)

一个 **Rust + Jetpack Compose** 混合开发的 Android 示例应用。  
核心计数逻辑在 Rust 中实现，通过 JNI 桥接到 Kotlin/Compose UI 层。

---

## 🎯 功能

- 屏幕中央大字显示当前计数值
- 点击 **+1** 按钮递增计数
- 底部状态指示器显示 Native 库加载状态（✓ Rust JNI 绿色 / ✗ Fallback 红色）
- Rust JNI 加载失败时自动降级为 Kotlin 纯实现（不影响使用）

## 🏗️ 架构

```
┌─────────────────────────────────┐
│   Jetpack Compose UI Layer      │  ← Kotlin
│   CounterScreen.kt              │
├─────────────────────────────────┤
│   JNI Bridge: NativeLib.kt      │  ← Kotlin object
│   external fun nativeIncrement  │
├═════════════════════════════════┤
│   Rust Native Library (.so)     │  ← Rust cdylib
│   fn Java_..._nativeIncrement   │
└─────────────────────────────────┘
```

## 📁 项目结构

```
MyRustApp/
├── native_lib/                    # Rust 原生库
│   ├── Cargo.toml                 # crate-type = ["cdylib"]
│   └── src/lib.rs                 # JNI 函数实现
├── app/                           # Android 应用
│   ├── build.gradle.kts           # AGP 配置
│   └── src/main/
│       ├── java/com/example/myrustapp/
│       │   ├── MainActivity.kt    # 入口 Activity
│       │   ├── NativeLib.kt       # JNI 桥接 + 容错
│       │   └── ui/main/
│       │       └── CounterScreen.kt  # 计数器 UI
│       └── jniLibs/               # 预编译 .so
│           ├── arm64-v8a/libnative_lib.so
│           └── armeabi-v7a/libnative_lib.so
├── build.gradle.kts               # 根项目配置
├── Rust-Android-Dev-Guide.md      # 详细开发总结
└── README.md
```

## 🚀 快速开始

### 环境要求

| 工具 | 版本 | 说明 |
|------|------|------|
| Rust | ≥1.96 | 编译原生代码 |
| cargo-ndk | latest | Android 交叉编译 |
| JDK | 17 | Kotlin 编译（不要用 21+） |
| Gradle | 9.x | 项目构建 |
| Android SDK | Build-Tools 36, Platform 24+ | APK 打包 |

### 1. 编译 Rust → .so

```bash
cd native_lib
cargo ndk \
  --target aarch64-linux-android \
  --target armv7-linux-androideabi \
  --platform 24 \
  -- build --release
```

### 2. 复制 .so 到 jniLibs

```bash
cp target/aarch64-linux-android/release/libnative_lib.so \
   ../app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libnative_lib.so \
   ../app/src/main/jniLibs/armeabi-v7a/
```

### 3. 构建 APK

```bash
# Windows
set JAVA_HOME=C:\path\to\jdk17
gradlew assembleDebug

# macOS / Linux
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 4. 安装到设备

```bash
# Wi-Fi ADB
adb connect <设备IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.myrustapp/.MainActivity

# 或直接用 USB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 🔧 核心代码

### Rust JNI 函数 (`native_lib/src/lib.rs`)

```rust
use jni::objects::JClass;
use jni::sys::jint;
use jni::EnvUnowned;

#[unsafe(no_mangle)]  // Rust 2024 edition 要求
pub extern "system" fn Java_com_example_myrustapp_NativeLib_nativeIncrement(
    _env: EnvUnowned,  // jni 0.22 用 EnvUnowned 替代 JNIEnv
    _class: JClass,
    count: jint,
) -> jint {
    count + 1
}
```

### Kotlin JNI 桥接 (`NativeLib.kt`)

```kotlin
object NativeLib {
    private var loaded = false

    init {
        try { System.loadLibrary("native_lib"); loaded = true }
        catch (e: UnsatisfiedLinkError) { loaded = false }
    }

    fun isLoaded(): Boolean = loaded

    fun increment(count: Int): Int = if (loaded) nativeIncrement(count)
                                      else count + 1  // Kotlin fallback

    @JvmStatic
    private external fun nativeIncrement(count: Int): Int
}
```

## 📋 遇到的问题

开发过程中遇到并解决了 11 个问题，包括：

- Rust 2024 edition 语法变更 (`#[unsafe(no_mangle)]`)
- jni 0.22 API 变化 (`JNIEnv` → `EnvUnowned`)
- Gradle wrapper 下载超时 / JDK 版本不匹配
- `namespace` 与 `applicationId` 不一致导致 Activity 找不到
- Wi-Fi ADB 配对协议兼容性问题
- ……

详见 **[Rust-Android-Dev-Guide.md](./Rust-Android-Dev-Guide.md)**

## 📄 License

MIT
