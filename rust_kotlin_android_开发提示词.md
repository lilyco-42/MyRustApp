# Rust + Kotlin Android 开发提示词

> 使用此提示词启动 Rust + Kotlin Android 项目，可避免 90% 的常见坑。
> 基于 `jni 0.22+`、`cargo-ndk`、`Jetpack Compose` 实战验证。

---

## 一、项目模板结构

```
project/
├── native_lib/                    # Rust cdylib
│   ├── Cargo.toml
│   └── src/lib.rs
├── app/                           # Android 应用
│   ├── build.gradle.kts           # namespace == applicationId == Kotlin package
│   └── src/main/
│       ├── java/<package_path>/
│       │   ├── MainActivity.kt
│       │   └── NativeLib.kt       # JNI 桥接
│       └── jniLibs/               # cargo-ndk 输出放这里
│           ├── arm64-v8a/libnative_lib.so
│           └── armeabi-v7a/libnative_lib.so
├── build.gradle.kts
└── .gitignore                     # 必须忽略 native_lib/target/
```

---

## 二、Rust 侧模板

### Cargo.toml

```toml
[package]
name = "native_lib"
version = "0.1.0"
edition = "2024"

[lib]
crate-type = ["cdylib"]

[dependencies]
jni = "0.22"
```

> ⚠️ `[dependencies]` 必须独占一行，不能和注释拼在同一行。

### src/lib.rs — JNI 函数

```rust
use jni::objects::JClass;
use jni::sys::jint;
use jni::EnvUnowned;

/// JNI 函数命名: Java_<package>_<Class>_<method>
/// package 中的 . 替换为 _，类名中的 _ 用 _1 转义
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_myrustapp_NativeLib_methodName(
    _env: EnvUnowned,   // jni 0.22: 用 EnvUnowned 不要用 JNIEnv
    _class: JClass,
    input: jint,
) -> jint {
    input + 1  // 你的逻辑
}
```

> ⚠️ Rust 2024 edition 要求 `#[unsafe(no_mangle)]` 而不是 `#[no_mangle]`

---

## 三、Kotlin 侧模板

### NativeLib.kt — JNI 桥接（带容错）

```kotlin
package com.example.myrustapp  // 必须与 JNI 函数名中的 package 一致

import android.util.Log

object NativeLib {
    private var loaded = false

    init {
        try {
            System.loadLibrary("native_lib")
            loaded = true
            Log.d("NativeLib", "✓ loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeLib", "✗ failed: ${e.message}")
            loaded = false
        }
    }

    fun isLoaded(): Boolean = loaded

    // 公开方法：带 fallback，保证不崩溃
    fun increment(count: Int): Int = if (loaded) nativeIncrement(count)
                                      else count + 1

    @JvmStatic
    private external fun nativeIncrement(count: Int): Int
}
```

> ⚠️ JNI 函数名必须完全匹配：`Java_<package>_<Class>_<method>`

### MainActivity.kt — 直接嵌入 Compose

```kotlin
package com.example.myrustapp  // 与 build.gradle.kts namespace 一致

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.myrustapp.theme.MyRustAppTheme
import com.example.myrustapp.ui.main.CounterScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyRustAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CounterScreen()
                }
            }
        }
    }
}
```

> ⚠️ 避免在简单应用中引入 Navigation3，直接在 Activity 中 setContent 最稳。

---

## 四、构建脚本

### 编译 Rust → .so

```bash
cd native_lib
cargo ndk \
  --target aarch64-linux-android \
  --target armv7-linux-androideabi \
  --platform 24 \
  -- build --release

# 复制到 jniLibs
cp target/aarch64-linux-android/release/libnative_lib.so \
   ../app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libnative_lib.so \
   ../app/src/main/jniLibs/armeabi-v7a/
```

### 构建 APK（Windows）

```bash
# JDK 17 必须！不要用 21+
set JAVA_HOME=C:\path\to\openjdk17
gradlew assembleDebug

# 或用系统 Gradle 替代 wrapper（规避下载慢）
gradle assembleDebug --no-daemon
```

> ⚠️ 如果 `gradlew` 下载 Gradle 超时，用 `scoop install gradle` 的本地版本代替。

---

## 五、build.gradle.kts 关键配置

```kotlin
android {
    // ⚠️ namespace 必须和 Kotlin package 以及 JNI 函数名中的 package 三者一致！
    namespace = "com.example.myrustapp"

    defaultConfig {
        applicationId = "com.example.myrustapp"  // 同上
        minSdk = 24
        compileSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)  // JDK 17，不要改
}
```

> ⚠️ `namespace` ≠ Kotlin package → 运行时 Activity 找不到 → `Error type 3`

---

## 六、.gitignore

```gitignore
*.iml
.gradle
local.properties
.idea/
.DS_Store
/build
/captures
.externalNativeBuild
.cxx
native_lib/target/
**/target/
```

---

## 七、环境依赖一键安装（Windows）

```bash
# Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# Android NDK 交叉编译工具
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi

# JDK 17（Android 必备）
scoop install openjdk17

# Gradle（可选，备用）
scoop install gradle

# 检查
cargo --version    # ≥1.96
java --version     # 17.x
gradle --version   # 9.x
```

---

## 八、ADB Wi-Fi 调试

```bash
# Android 11+ 用配对码方式（推荐）
# 手机：设置 → 开发者选项 → 无线调试 → 使用配对码配对设备
adb pair <ip>:<配对端口> <6位配对码>

# Android 10 及以下用 tcpip 方式
adb tcpip 5555
adb connect <ip>:5555

# 安装 & 启动
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.myrustapp/.MainActivity

# 查看日志
adb logcat -s NativeLib:D CounterScreen:D AndroidRuntime:E
```

> ⚠️ Wi-Fi ADB 连接不稳定，初次部署建议 USB，后续调试用 Wi-Fi。

---

## 九、常见错误速查表

| 错误信息 | 原因 | 修复 |
|---|---|---|
| `cannot find module jni` | `[dependencies]` 格式损坏 | 节头独占一行 |
| `unsafe attribute used without unsafe` | Rust 2024 edition | `#[unsafe(no_mangle)]` |
| `deprecated type alias JNIEnv` | jni 0.22 API 变化 | 改用 `EnvUnowned` |
| `Timeout waiting for exclusive access` | Gradle wrapper 下载超时 | 用本地 Gradle |
| `Cannot find Java matching {languageVersion=17}` | JDK 版本不对 | 安装 JDK 17 |
| `Activity class does not exist` | namespace ≠ package | 统一三处命名 |
| `UnsatisfiedLinkError` | JNI 命名不匹配 | 检查函数名完全对应 |
| `adb: device offline` | Wi-Fi 断开 | 重新配对或 USB 连接 |
| `protocol fault (couldn't read status)` | 配对协议问题 | `adb kill-server` 后重试 |

---

## 十、JNI 类型映射

| Java/Kotlin | Rust (jni::sys) |
|---|---|
| `boolean` | `jboolean` |
| `byte` | `jbyte` |
| `char` | `jchar` |
| `short` | `jshort` |
| `int` | `jint` |
| `long` | `jlong` |
| `float` | `jfloat` |
| `double` | `jdouble` |
| `String` | `JString<'local>` |
| `void` | `()` |

---

## 十一、调试检查清单

- [ ] `Cargo.toml`: `crate-type = ["cdylib"]`，jni 版本 ≥0.22
- [ ] `lib.rs`: `#[unsafe(no_mangle)]`，`EnvUnowned`，函数名完全匹配
- [ ] `build.gradle.kts`: `namespace == applicationId == Kotlin package`
- [ ] `NativeLib.kt`: `System.loadLibrary("native_lib")`，有 try-catch
- [ ] `jniLibs/`: arm64-v8a 和 armeabi-v7a 各有一份 `.so`
- [ ] JDK 版本 = 17
- [ ] `nm -D libnative_lib.so | grep Java_` 能搜到 JNI 函数
- [ ] `aapt dump xmltree app-debug.apk AndroidManifest.xml | grep MainActivity` 类名正确

---

> 完整实战记录见 [Rust-Android-Dev-Guide.md](./Rust-Android-Dev-Guide.md)
