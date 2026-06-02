# Rust 开发 Android 应用实战记录

> 项目：使用 Rust + Jetpack Compose 构建一个简单的按钮点击 +1 计数器应用  
> 设备：OPPO Find X8 (PJD110) via Wi-Fi ADB  
> 日期：2026-06-02

---

## 项目概览

- **项目结构**：Rust 编译为 `.so` 动态库，通过 JNI 桥接给 Kotlin/Compose 层调用
- **Rust 侧**：`native_lib/` — 使用 `jni` crate，编译为 `cdylib`
- **Android 侧**：`app/` — Jetpack Compose + Navigation3 + ViewModel + Material3
- **NDK 跨平台编译**：`cargo-ndk` 编译 arm64-v8a 和 armeabi-v7a
- **构建系统**：Gradle 9.x + Kotlin 2.x

---

## 遇到的问题和解决方案

### 问题 1：Cargo.toml 格式损坏

**现象**：
```
error[E0433]: cannot find module or crate `jni` in this scope
```

**原因**：`[dependencies]` 节头与注释粘在同一行，导致 Cargo 无法识别：

```toml
# 错误写法：
[lib]
crate-type = ["cdylib"]   # 注释[dependencies]
jni = "0.22.4"

# 正确写法：
[lib]
crate-type = ["cdylib"]

[dependencies]
jni = "0.22.4"
```

**教训**：`.toml` 文件的节头（`[section]`）必须独占一行，不能和注释拼接在同一行。

---

### 问题 2：Rust 2024 Edition 语法变更

**现象**：
```
error: unsafe attribute used without unsafe
 --> src\lib.rs:5:3
5 | #[no_mangle]
  |   ^^^^^^^^^ usage of unsafe attribute
help: wrap the attribute in `unsafe(...)`
5 | #[unsafe(no_mangle)]
```

**原因**：Rust 2024 edition 要求 `#[no_mangle]` 必须写成 `#[unsafe(no_mangle)]`。这是因为 `no_mangle` 可能导致符号冲突，被视为 unsafe 行为。

**修复**：
```rust
// Rust 2024 写法
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_myrustapp_NativeLib_increment(...)
```

---

### 问题 3：jni crate 0.22 的 API 变更

**现象**：
```
warning: use of deprecated type alias `jni::JNIEnv`
```

**原因**：jni 0.22 将 `JNIEnv` 拆分为 `Env`（非 FFI 安全）和 `EnvUnowned`（FFI 安全）。旧的 `JNIEnv` 已被标记为 deprecated。

**修复**：
```rust
// 旧写法
use jni::JNIEnv;
fn foo(_env: JNIEnv, _class: JClass, ...) { }

// 新写法
use jni::EnvUnowned;
fn foo(_env: EnvUnowned, _class: JClass, ...) { }
```

---

### 问题 4：Gradle 下载超时

**现象**：
```
Timeout of 120000 reached waiting for exclusive access to file:
...\gradle-9.1.0-bin.zip
```

**原因**：
1. Gradle 9.1.0 分发包约 136MB，从 `services.gradle.org` 下载速度慢
2. 留下了 `.lck` 锁文件和 72.4MB 的 `.part` 部分下载文件

**解决方案**：
- 删除 `.lck` 锁文件
- 改用系统已安装的 Gradle 9.5.1（通过 scoop 安装）：`/c/Users/liuqi/scoop/shims/gradle`
- 跳过 wrapper，直接使用系统 Gradle

```
# 使用系统 Gradle 代替 gradlew
export JAVA_HOME="<jdk17_path>"
gradle assembleDebug --no-daemon
```

---

### 问题 5：JDK 版本不匹配

**现象**：
```
Cannot find a Java installation on your machine matching:
{languageVersion=17, vendor=any vendor, implementation=vendor-specific}
```

**原因**：项目 `app/build.gradle.kts` 中通过 `kotlin { jvmToolchain(17) }` 指定了 JDK 17，但系统只安装了 JDK 21 和 JDK 26。

**解决方案**：
```bash
# 安装 JDK 17
scoop install openjdk17

# 构建时指定 JAVA_HOME
export JAVA_HOME="C:/Users/liuqi/scoop/apps/openjdk17/current"
gradle assembleDebug --no-daemon
```

> **注意**：不要使用 JDK 21/25+。Android Gradle Plugin 与 JDK 17 兼容性最好，高版本可能有未知问题。

---

### 问题 6：Wi-Fi ADB 连接不稳定

**现象**：
- ADB 连接随机断开，设备变为 `offline`
- `error: closed` 或 `cannot connect to 192.168.10.41:5555`

**原因**：
1. 手机息屏后 Wi-Fi 进入省电模式
2. Wi-Fi ADB 本身不如 USB 稳定
3. 大文件传输（11.7MB APK）时容易超时

**缓解措施**：
```bash
# 重新连接
adb disconnect
adb connect 192.168.10.41:5555

# 或者用 USB 先建立连接，再切换到 Wi-Fi
adb tcpip 5555
adb connect <ip>:5555
```

> **最佳实践**：首次部署用 USB，调试阶段再用 Wi-Fi。或保持手机屏幕常亮。

---

### 问题 7：strip 工具无法处理 Rust 编译的 .so

**现象**（构建日志中的 warning）：
```
Unable to strip the following libraries, packaging them as they are:
libnative_lib.so
```

**原因**：Android SDK 自带的 `strip` 工具不识别 Rust 编译的 ELF 格式变体。这不影响运行，只是包体积略大。

**影响**：无运行时影响，可忽略。

---

## JNI 函数命名规则

Rust 中 JNI 函数名必须严格遵循 Java 包名规则：

```
Java_<package>_<Class>_<method>
```

- 包名中的 `.` 替换为 `_`
- 如果包名或类名本身包含 `_`，需要用 `_1` 转义

示例对照：

| Kotlin 声明 | Rust 函数名 |
|---|---|
| `com.example.myrustapp.NativeLib.increment()` | `Java_com_example_myrustapp_NativeLib_increment` |

Kotlin 侧：
```kotlin
// app/src/main/java/com/example/myrustapp/NativeLib.kt
object NativeLib {
    init { System.loadLibrary("native_lib") }
    external fun increment(count: Int): Int
}
```

Rust 侧：
```rust
// native_lib/src/lib.rs
use jni::objects::JClass;
use jni::sys::jint;
use jni::EnvUnowned;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_myrustapp_NativeLib_nativeIncrement(
    _env: EnvUnowned,
    _class: JClass,
    count: jint,
) -> jint {
    count + 1
}
```

---

## 完整构建流程

```bash
# 1. 编译 Rust → .so
cd native_lib
cargo ndk \
  --target aarch64-linux-android \
  --target armv7-linux-androideabi \
  --platform 24 \
  -- build --release

# 2. 复制 .so 到 jniLibs
cp target/aarch64-linux-android/release/libnative_lib.so \
   ../app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libnative_lib.so \
   ../app/src/main/jniLibs/armeabi-v7a/

# 3. 构建 APK（需要 JDK 17）
cd ..
export JAVA_HOME="C:/Users/liuqi/scoop/apps/openjdk17/current"
gradle assembleDebug --no-daemon

# 4. 安装到设备
adb connect 192.168.10.41:5555
adb -s 192.168.10.41:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# 5. 启动
adb -s 192.168.10.41:5555 shell am start -n com.example.myrustapp/.MainActivity
```

---

## 文件结构

```
MyRustApp/
├── native_lib/                    # Rust 原生库
│   ├── Cargo.toml                 # [lib] crate-type=["cdylib"]
│   └── src/
│       └── lib.rs                 # JNI 函数实现
├── app/                           # Android 应用
│   ├── build.gradle.kts           # AGP + Compose 配置
│   └── src/main/
│       ├── java/com/example/myrustapp/
│       │   ├── MainActivity.kt    # 入口 Activity
│       │   ├── NativeLib.kt       # JNI 桥接声明
│       │   ├── Navigation.kt      # 导航图
│       │   └── ui/main/
│       │       └── CounterScreen.kt  # 计数器 UI
│       └── jniLibs/
│           ├── arm64-v8a/
│           │   └── libnative_lib.so
│           └── armeabi-v7a/
│               └── libnative_lib.so
├── build.gradle.kts               # 根项目配置
├── settings.gradle.kts
└── gradle/
    └── wrapper/
```

---

## 环境依赖清单

| 工具 | 版本 | 用途 |
|---|---|---|
| Rust | 1.96.0 | 编译原生代码 |
| cargo-ndk | latest | Android NDK 交叉编译 |
| Gradle | 9.5.1 | Android 项目构建 |
| JDK | 17.0.2 | Kotlin 编译 |
| Android SDK Build-Tools | 36.0.0 | APK 打包 |
| Android SDK Platform-Tools | 37.0.0 | adb 调试 |
| NDK | (通过 cargo-ndk) | 交叉编译工具链 |
| adb | 1.0.41 | 设备连接 |

---

## 关键经验总结

1. **Rust edition 很重要**：2024 edition 的 `#[unsafe(no_mangle)]` 语法是常见坑
2. **crate 版本要跟上**：jni 0.22 的 API 变化（`JNIEnv` → `EnvUnowned`）需要适配
3. **JDK 版本要匹配**：Android 项目推荐 JDK 17，不要用太新或太旧的版本
4. **Wi-Fi ADB 不稳定**：大文件安装优先用 USB，调试阶段再用 Wi-Fi
5. **Cargo.toml 格式要严格**：节头必须独占一行
6. **Rust .so 可以工作**：strip 警告不影响功能，可以忽略
7. **Gradle wrapper 下载慢**：可用系统 Gradle 替代，注意版本兼容性

---

## 问题 8：JNI 函数名不匹配导致 Native 调用失败

**现象**：应用安装后可能闪退或点击按钮无响应。

**原因**：
- Kotlin 中使用 `object NativeLib` + `@JvmStatic private external fun nativeIncrement()` 
- 对应的 JNI 函数名必须是：`Java_com_example_myrustapp_NativeLib_nativeIncrement`
- 早期的命名 `Java_..._increment` 不匹配 `nativeIncrement`

**最终正确的 Rust 签名**：
```rust
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_myrustapp_NativeLib_nativeIncrement(
    _env: EnvUnowned,
    _class: JClass,
    count: jint,
) -> jint {
    count + 1
}
```

---

## 问题 9：Navigation3 框架导致不必要的复杂性

**现象**：原项目使用 Navigation3，多了一层抽象，增加崩溃点。

**解决**：直接在 `MainActivity` 中嵌入 `CounterScreen`，去除 Navigation3 依赖：
```kotlin
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyRustAppTheme {
        Surface(...) { CounterScreen() }
      }
    }
  }
}
```

---

## 问题 10：缺少 JNI 异常保护导致闪退

**现象**：如果 `System.loadLibrary` 失败，应用直接崩溃。

**解决**：在 NativeLib 中加入防御性编程：
- `init` 块 try-catch 包裹 `loadLibrary`
- 提供 `isLoaded()` 检测方法
- JNI 调用失败时使用 Kotlin fallback（纯 Kotlin `count + 1`）
- 界面上显示状态指示器：`✓ Rust JNI` 或 `✗ Kotlin fallback`

---

## JNI 函数命名对照表（最终版）

| Kotlin 声明 | Rust 函数名 |
|---|---|
| `object NativeLib { private external fun nativeIncrement(...) }` | `Java_com_example_myrustapp_NativeLib_nativeIncrement` |

> **注意**：`@JvmStatic` 在 object 中对外部函数命名无影响，JNI 只看类名和方法名。

---

## 运行结果

APK 成功构建（11.7MB debug），安装到 OPPO Find X8 (arm64-v8a) 上。  
应用界面：屏幕中央显示大号计数数字，下方有一个 "+1" 按钮，每次点击调用 Rust JNI 函数递增计数。  
界面底部显示 Native 加载状态（绿色 ✓ 或 红色 ✗）。
