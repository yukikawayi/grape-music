# ============================================================
# R8 keep 规则（isMinifyEnabled = true + isShrinkResources = true）
#
# 现状说明（AGP 9.5 / R8 full mode）：
#  - Room 2.7 / Media3 1.8 / Coil 2.7 / OkHttp 4.12 / coroutines 1.10 /
#    DataStore 1.2 均自带 consumer rules（jar 内 proguard.txt），无需手工 keep；
#  - kotlinx.serialization 虽在依赖里但 App 代码未使用，R8 会整体裁掉；
#  - App 代码无 Class.forName / newInstance 等反射，四大组件由 Manifest 自动 keep；
#  - 唯一没有规则文件的老库是 jaudiotagger 2.0.1，见下方整包 keep。
# ============================================================

# ---- 崩溃堆栈可还原：保留行号，混淆名通过 build/outputs/mapping/release/mapping.txt 回查 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 反射元数据：注解、泛型签名、内部类/外部类（Palette / 协程 / 老库都会读）----
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ---- jaudiotagger 2.0.1：无 consumer rules。库内通过 Class查找/日志框架间接访问类，
#      直接调用只能保住入口，保险起见整包 keep（体积代价 ~1MB 内，换回归零风险）----
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# ---- OkHttp / Okio：平台可选扩展（Conscrypt / BouncyCastle / OpenJSSE / ALPN）----
#      设备上不存在时 R8 报 missing class 告警，全部是运行时可选路径，静音即可
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- coroutines：debug 探针与 JDK9+ 的可选路径 ----
-dontwarn kotlinx.coroutines.debug.**
-dontwarn java.lang.invoke.**
