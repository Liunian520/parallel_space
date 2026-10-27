# Bcore（AAR）里的 BlackBoxCore.getProcessName 被补丁改成直接调用这个方法。
# 名字必须保持不变，否则 attach 会 NoSuchMethodError，首页再次卡在引擎未就绪。
-keep class com.parallelspace.parallel_space.ProcessNameCompat {
    public static java.lang.String get(android.content.Context);
}

# AAR 自带的规则（consumer proguard）在 minify 打开时也会合并；这里再写一遍，避免漏。
-keep class top.niunaijun.blackbox.** { *; }
-keep class top.niunaijun.jnihook.** { *; }
-keep class mirror.** { *; }

# black-reflection.jar 没有 consumer ProGuard。Flutter 3.47 的 release 会开 R8 full mode，
# 从应用静态调用图看这 21 个类像是没人用，于是整包删掉或改名。
# BlackBoxCore.<clinit> 构造单例时会调用 BlackReflection.create；类被收掉后初始化失败，
# ART 随后抛 NoClassDefFoundError: top.niunaijun.blackbox.BlackBoxCore。
# 注解类型名必须保持原样，create() 靠它们在接口上找目标类。
-keep class top.niunaijun.blackreflection.** { *; }

# AAR 里的 hidden-API 镜像（black.android.** 等，约 900 个接口）只通过上述注解被反射调用。
# 不 keep 时 R8 把它们收成空接口并改名（例如 UserHandleStatic → hk0），
# @BClassName 一并丢失，create() 返回 null，单例构造失败。
-keep class black.** { *; }

# AAR 自身已被 R8 收过一遍，剩下 a.a / b.a / b.b / c.a / d.a 这几个反射入口。
-keep class a.a { *; }
-keep class b.a { *; }
-keep class b.b { *; }
-keep class c.a { *; }
-keep class d.a { *; }

# NativeCore 解隐藏 API 失败时调用 FreeReflection。
-keep class me.weishu.reflection.** { *; }

# 镜像接口上的注解是运行时契约，不能被 R8 剥掉。
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
