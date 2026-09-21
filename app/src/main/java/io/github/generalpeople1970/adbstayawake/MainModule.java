package io.github.generalpeople1970.adbstayawake;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * AdbStayAwake（ADB调试常亮）模块入口。
 *
 * <p>目标进程：system_server（scope.list = system）。</p>
 *
 * <p>实现思路（对应需求四个 Hook 点）：</p>
 * <ol>
 *   <li><b>监听 ADB 状态</b>：读取 {@link Settings.Global#ADB_ENABLED}（"adb_enabled"），
 *       并注册 {@link ContentObserver} 实时跟踪其变化，缓存在 {@link #sAdbEnabled}。</li>
 *   <li><b>强制保持常亮</b>：Hook {@code PowerManagerService#getScreenOffTimeoutLocked(...)}，
 *       当 ADB 激活时让其返回 {@link Integer#MAX_VALUE}（约 24.8 天），使系统永不因超时自动熄屏。</li>
 *   <li><b>允许手动锁屏</b>：不 Hook 任何电源键（KEYCODE_POWER）相关逻辑。电源键触发的
 *       {@code goToSleep} 路径不经过屏幕超时计算，因此按电源键依旧能立即锁屏。</li>
 *   <li><b>断开后恢复</b>：本模块<em>不</em>永久修改任何系统字段。当 ADB 未激活时，Hook 直接返回
 *       原始计算结果（{@code chain.proceed()}），系统原有的屏幕超时设置立即自动生效，无需另行还原。</li>
 * </ol>
 *
 * <p>基于 libxposed API 102：入口类继承 {@link XposedModule}，使用 {@link XposedInterface.Hooker}
 * 拦截器链模型（{@code intercept(Chain)}）；不使用任何 {@code de.robv.android.xposed.*} 旧版 API。</p>
 */
public class MainModule extends XposedModule {

    private static final String TAG = "AdbStayAwake";

    /** system_server 中电源管理服务的完整类名。 */
    private static final String CLASS_PMS = "com.android.server.power.PowerManagerService";
    /** 计算“屏幕超时”的方法名（不同 Android 版本参数不同，故按名字匹配所有重载）。 */
    private static final String METHOD_TIMEOUT = "getScreenOffTimeoutLocked";
    /** 已知会调用超时方法的调用者，尝试去优化以规避方法内联导致 Hook 不生效。 */
    private static final String[] CANDIDATE_CALLERS = {
            "updateUserActivitySummaryLocked",
            "updatePowerStateLocked",
    };

    /** 缓存的 ADB 激活状态，由 ContentObserver 更新。volatile 保证跨线程可见。 */
    private static volatile boolean sAdbEnabled = false;
    /** ContentObserver 是否已注册成功（幂等）。 */
    private static volatile boolean sObserverRegistered = false;
    /** 是否已把注册任务投递到主线程（避免在锁内重复投递）。 */
    private static volatile boolean sRegistrationScheduled = false;
    /** 供静态 Hooker 内部写日志用的框架接口引用。 */
    private static volatile XposedInterface sXposed;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        sXposed = this;
        log(Log.INFO, TAG, "onModuleLoaded: process=" + param.getProcessName()
                + ", isSystemServer=" + param.isSystemServer());
    }

    /**
     * system_server 准备启动关键服务时回调，此处拿到系统类加载器并安装 Hook。
     * 现代 API 中该回调在 system_server 内取代了首个 onPackageLoaded 阶段。
     */
    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        super.onSystemServerStarting(param);
        sXposed = this;

        final ClassLoader cl = param.getClassLoader();
        try {
            final Class<?> pms = cl.loadClass(CLASS_PMS);

            int hooked = 0;
            for (Method m : pms.getDeclaredMethods()) {
                if (METHOD_TIMEOUT.equals(m.getName())) {
                    // 安装拦截器：ADB 激活时强制返回极大超时值。
                    hook(m).intercept(new ScreenOffTimeoutHooker());
                    // 去优化被 Hook 的方法本身（best-effort）。
                    tryDeoptimize(m);
                    hooked++;
                }
            }

            // best-effort：去优化已知调用者，降低因内联而 Hook 不触发的概率。
            for (String caller : CANDIDATE_CALLERS) {
                for (Method m : pms.getDeclaredMethods()) {
                    if (caller.equals(m.getName())) {
                        tryDeoptimize(m);
                    }
                }
            }

            log(Log.INFO, TAG, "PowerManagerService hooked, " + METHOD_TIMEOUT
                    + " method(s)=" + hooked);
            if (hooked == 0) {
                log(Log.WARN, TAG, "未找到 " + METHOD_TIMEOUT
                        + "，当前 ROM 可能改动了实现，模块可能不生效。");
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hook PowerManagerService 失败", t);
        }
    }

    private void tryDeoptimize(Method m) {
        try {
            deoptimize(m);
        } catch (Throwable ignored) {
            // 去优化失败不影响主流程。
        }
    }

    /**
     * 拦截 {@code getScreenOffTimeoutLocked}：
     * ADB 激活 → 返回 {@link Integer#MAX_VALUE}（永不自动熄屏）；
     * 否则 → 透传系统原始计算结果（尊重用户原有超时设置）。
     */
    public static final class ScreenOffTimeoutHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            // 先执行原始逻辑（纯 getter，无副作用），保持拦截器链礼貌并拿到原值。
            final Object original = chain.proceed();

            // 首次进入时利用 PowerManagerService 实例懒注册 ADB 状态监听。
            ensureAdbObserver(chain.getThisObject());

            if (sAdbEnabled) {
                // 关键：返回值类型必须与被 Hook 方法的“真实返回类型”一致，否则框架在
                // PROTECTIVE 模式下回填时会抛 ClassCastException——曾导致 system_server
                // 在开机阶段崩溃、进而 bootloop。
                // 不同 ROM 上 getScreenOffTimeoutLocked 的返回类型不一致（部分 ROM 为 int，
                // Xiaomi HyperOS/Android 15 等为 long）。以原始返回值的运行时类型为准，
                // 返回同类型的极大值（≈ 24.8 天），从根本上避免类型不匹配。
                if (original instanceof Long) {
                    return (long) Integer.MAX_VALUE;
                }
                return Integer.MAX_VALUE;
            }
            return original;
        }
    }

    /**
     * 懒调度 ADB 状态监听的注册。
     *
     * <p><b>重要：本方法由 {@code getScreenOffTimeoutLocked} 在持有
     * {@code PowerManagerService.mLock} 的情况下调用。</b>因此这里<em>绝不</em>做任何
     * Binder IPC（如读取 Settings、注册 ContentObserver），否则可能造成锁序颠倒 / Watchdog
     * 超时。此处只做廉价的反射取 Context，然后把真正的 IPC 工作投递到主线程 Looper 上执行，
     * 彻底脱离 mLock。</p>
     */
    private static void ensureAdbObserver(Object pmsInstance) {
        if (sRegistrationScheduled || pmsInstance == null) {
            return;
        }
        synchronized (MainModule.class) {
            if (sRegistrationScheduled) {
                return;
            }
            // 仅做反射（读取实例字段，无 IPC），拿到 Context。
            final Context ctx = findContext(pmsInstance);
            if (ctx == null) {
                return; // 拿不到 Context，下次调用再试（不置位调度标志）。
            }
            sRegistrationScheduled = true;

            // 把 IPC 密集的注册工作放到主线程执行，避免在 mLock 下进行 Binder 调用。
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    registerObserver(ctx);
                }
            });
        }
    }

    /**
     * 真正的注册逻辑，运行在主线程（不持有 PMS 锁）：读取初始 adb_enabled 并注册 ContentObserver。
     */
    private static void registerObserver(Context ctx) {
        if (sObserverRegistered) {
            return;
        }
        try {
            final ContentResolver cr = ctx.getContentResolver();

            // 读取初始 ADB 状态。
            sAdbEnabled = readAdbEnabled(cr);

            // 在主线程 Looper 上分发变更回调。
            final ContentObserver observer = new ContentObserver(
                    new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    final boolean now = readAdbEnabled(cr);
                    if (now != sAdbEnabled) {
                        sAdbEnabled = now;
                        final XposedInterface xp = sXposed;
                        if (xp != null) {
                            xp.log(Log.INFO, TAG, "adb_enabled changed -> " + now);
                        }
                    }
                }
            };
            cr.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                    false, observer);

            sObserverRegistered = true;

            final XposedInterface xp = sXposed;
            if (xp != null) {
                xp.log(Log.INFO, TAG, "ADB 状态监听已注册，初始 adb_enabled=" + sAdbEnabled);
            }
        } catch (Throwable t) {
            // 注册失败：复位调度标志，允许后续再次尝试。
            sRegistrationScheduled = false;
            final XposedInterface xp = sXposed;
            if (xp != null) {
                xp.log(Log.ERROR, TAG, "注册 ADB 状态监听失败", t);
            }
        }
    }

    /** 读取 adb_enabled 全局设置，异常时视为未激活。 */
    private static boolean readAdbEnabled(ContentResolver cr) {
        try {
            return Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 优先取名为 mContext 的字段；找不到则遍历（含父类）任意 Context 类型字段。 */
    private static Context findContext(Object instance) {
        Class<?> c = instance.getClass();
        // 先按常见字段名快速命中。
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField("mContext");
                f.setAccessible(true);
                Object v = f.get(instance);
                if (v instanceof Context) {
                    return (Context) v;
                }
            } catch (NoSuchFieldException ignored) {
                // 继续向上找。
            } catch (Throwable ignored) {
                break;
            }
        }
        // 兜底：扫描任意 Context 字段。
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Context.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(instance);
                        if (v instanceof Context) {
                            return (Context) v;
                        }
                    } catch (Throwable ignored) {
                        // 跳过该字段。
                    }
                }
            }
        }
        return null;
    }
}
