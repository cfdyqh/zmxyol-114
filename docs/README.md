# 造梦西游OL 11.4.0（UC 渠道）改造记录

> 目标包：`base(2).apk`（716,065,704 B，`com.zmxyol.union.uc`，versionCode 1010 /
> versionName 11.4.0，只带 armeabi）。
> 本文记录 2026-11-05 这一轮的结论，供下次直接接着干。

---

## 一、结论先说

**这一版目前改不动代码**：腾讯乐固（libtprt）+ TP2（libtersafe2）对
`classes.dex` / `AndroidManifest.xml` / `lib/*.so` 做了内容校验，
任何改动都会让进程在启动 1 秒内被杀：

| 改动 | 结果 |
|---|---|
| 只**新增** `classes2.dex` | ✅ 正常启动 |
| 改 `classes.dex`（哪怕一个字符串） | ❌ `Process exited cleanly (4)` 循环 |
| 改 `AndroidManifest.xml`（换 application / provider 名） | ❌ 同上 |
| 改 `lib/armeabi/libgame.so`（连 `.comment` 段都算） | ❌ 同上 |
| 改 `lib/armeabi/libtprt.so` / `__tpinfo.tsa` | ❌ `exited cleanly (2)` |
| 改 `assets/` 里的图片等资源 | ✅ 正常启动 |
| 整个 APK 重签名（内容不变） | ✅ 正常启动 |

也就是说：**assets 不算校验范围，代码文件全部算**。

---

## 二、已验证的事实

### 2.1 包结构

- 单 `classes.dex`（3,994,080 B，4357 个类），**没有二次加固的 dex**；
  `zmxyApplication extends com.tencent.tpshell.TPShellApplication`（乐固壳），
  `zmxy` 是 cocos2dx 主 Activity。
- `lib/armeabi/`：`libgame.so`(33 MB) `libtersafe2.so`(TP2) `libtprt.so`(乐固)
  `libsecsdk.so`(UC) `libfnupdate.so` `libtpnsSecurity/Watchdog.so`。
- `assets/` 约 876 MB，含 `assets/database/*.sdb`（和 8.0.0 同名同格式）。
- 渠道 SDK 是「蜂鸟」`com.ssjj.fnsdk`（UC 渠道），不是 4399。

### 2.2 协议 / 服务器

- 编码没变：`base64(DES-ECB/PKCS5, key="43((zmIp")`。
- 服务器地址（运行时解密进 libgame 的 rw 段，文件里搜不到）：
  - `http<path> 8.0.0 的 sy）
  - `http<path>
  - `htt<path>
- **官方服务器还活着**：`zmol.4399sj.com -> 119.188.48.34`（alidns 可解析，
  模拟器 ping 通），GET 会回 `{"code":1001,"errorMsg":"此功能尚未开放"}`。
- 用同样的 DES 手工构造 `checkupdate` POST，服务器回
  `{"code":1001,"errorMsg":"请勿使用破解版进行游戏(1)"}`；
  客户端自己发则弹「更新检查失败, 请勿使用破解版进行游戏(3)」。
  → 服务器校验 `sign/safe`（TP2 参与计算）。**在 x86+houdini 的模拟器上必失败**，
  真机 ARM 上大概率正常（未验证，手上没真机）。

### 2.3 登录链（Java 侧已拆清）

```
native -> YJMobileJNIHelper.loginHummingbirdSDK()
       -> SsjjFNSDK.login(activity)
       -> SsjjFNUserListener.onLoginSucceed(SsjjFNUser)   // 实现类 org.yjmobile.lib.llL1ii
       -> YJMobileJNIHelper.hummingbirdLoginFinished() -> native 发 login
```

离线时可以反射构造 `llL1ii` 并替 SDK 调 `onLoginSucceed`（`v11/java/.../FnForce.java`
已写好，但因为代码校验问题还没跑起来）。

### 2.4 已写好的东西（都不能用，仅存档）

| 文件 | 作用 |
|---|---|
| `v11/java/org/yjmobile/zmxy/offline/` | 从 v800 搬来的本地服（LocalServer/Des/Canned…）+ `Boot`/`UrlPatch`/`FnForce`/`Provider` |
| `v11/repack.py` | 在 v800 repack 基础上加了 `--rename-string`（AXML 字符串池等值替换） |
| `v11/build.ps1` | javac → d8 → repack（加 classes2.dex）→ zipalign → 签名 |
| `v11/native/tprt_stub.c` | 试过用假 libtprt 顶掉乐固，失败：libgame 依赖它的 `g_tprt_pfn_array`/`g_tprt_ori_array` |
| `v11/dexlib.py` | 最小 DEX 读写（改方法体用） |

### 2.5 试过但撞墙的注入点

| 注入点 | 结果 |
|---|---|
| manifest 换 application | SIGTERM（乐固状态没初始化，被别的检查杀） |
| manifest 换 provider 名（占位 ContentProvider 抢先执行） | exit(4)（manifest 被校验） |
| 加 `classes2.dex` | 允许，但没有任何入口能触发 |
| 改 dex 里方法体 | exit(4) |
| 改 libgame/libtprt/libtersafe2 | exit(4)/exit(2) |
| 假 libtprt | libgame 链接失败（缺两个数组符号）；补了零数组后 NULL 调用 SIGSEGV |
| 改 `__tpinfo.tsa` | exit(2) |

### 2.6 定位工具（可复用）

- `v11/attach_trace.sh`：设备上 strace attach 到刚启动的进程，`-i` 抓 exit_group
  的 IP（这次抓到的是 houdini 里的地址，说明 guest 调用被翻译，回溯不到 guest 库）。
- 设备上 `strace`/`gdbserver` 都在 `/system/bin`；`setprop wrap.*` 不可用
  （属性区满了：`__system_property_add failed`）。
- `v11/dexlib.py` 可读 class_data / code_item，改完自动重算 dex 的 SHA1+Adler32。
  注意：改**字符串**会破坏 dex 的排序校验（`Out-of-order string_ids`），
  要么不动字符串，要么保持字典序。

---

## 三、下一步（按性价比排）

1. **拿「脱壳/去乐固」的 11.4.0 底包**（最省事）。有底包后直接套 `v800` 的离线方案：
   本地服 + classes2.dex + MemPatch 改 URL（域名换成 4399sj）。
2. **真机验证**：先确认 11.4.0 在真机上能正常过 checkupdate 进登录。
   如果能进，说明只是模拟器过不了反外挂，问题就变成「怎么在真机上做离线/改数据」。
3. **逆向乐固校验**（最费时）：目标是在 libtprt 里找到读
   `__tpinfo.tsa`/代码文件哈希的函数，把它 patch 成直接返回 0；
   同时解决 libgame 对 libtprt 的自校验（exit 2 那条路径）。
4. **只改数据（assets）**：`assets/database/*.sdb` 可以改（未被校验），
   但要客户端能进游戏才有意义。


---

## 四、2026-11-05 晚补充：去乐固尝试（有进展，卡在游戏自带反外挂）

### 4.1 重要更正

Zygote 日志里的 `Process exited cleanly (N)` 是 **exit(N)**（正常退出码），
不是信号 N。之前记的 "SIGTERM/exit 4/2" 都是 `exit()` 的返回码：
乐固校验失败 = exit(4)，乐固自身文件被改 = exit(2)。

### 4.2 实测：真机也过不了 checkupdate

用户在真机上装原版 11.4.0，同样弹「更新检查失败, 请勿使用破解版进行游戏(3)」。
→ 不是模拟器的问题：**服务器就是拒绝 11.4.0 这个客户端**
（我们手工发 `checkupdate` 得到 `{"code":1001,"errorMsg":"请勿使用破解版进行游戏(1)"}`，
服务器还活着，只是不认这个版本的签名/版本）。所以只能离线。

### 4.3 去乐固包已经能跑起来

做法（`v11/repack.py`）：

1. manifest 的 `android:name` 从 `org.yjmobile.zmxy.zmxyApplication`（35 字符）
   等长改成 `org.yjmobile.zmxy.offline.O`（27 字符），跳过
   `TPShellApplication.attachBaseContext` 里的乐固初始化 → 乐固的完整性校验根本不会跑。
2. `lib/armeabi/libtersafe2.so` 换成自写 stub（`v11/native/tersafe2_stub.c`）：
   提供 libgame 需要的 8 个 `tp2_*` 符号 + `TssSdk` 的 10 个 JNI native。
3. 加 `classes2.dex`（本地服 / UrlPatch / FnForce）。

效果：**进程能起来，我们的 Boot 跑了，本地服起了，URL 也 patch 成功了**
（日志 `patch: url[0] live`），Activity 的 onCreate/onResume 都走完了。

### 4.4 卡点：游戏自带反外挂 exit(15)

再往后走，`Cocos2dxRenderer_nativeInit` 一到，进程就 `exit(15)`。
原因基本确定：**乐固平时会替 App 伪造签名/环境**，去掉乐固后，App 自己的
反外挂（libgame / libsecsdk / Java 侧）发现签名或环境不对，主动退出。

已经做过的排查：

- `v11/patch_libgame.py` / `patch_exit_plt.py`：把 libgame / libsecsdk / libtprt /
  libfnupdate 的 `exit/_exit/abort/kill/raise` PLT 桩改成 `return 0`。
  结果：exit 码从 15 变成 25（还是死），说明还有别的终止路径。
- `v11/native/tersafe2_diag.c`：诊断版 stub，起线程用 `dl_iterate_phdr` 扫
  各 so 的 `DT_JMPREL`，把 `exit/_exit/abort/kill/raise/tgkill/pthread_kill`
  的 GOT 槽改成自己的 hook 并打日志。结果：只 hook 到 libgame 的 `raise`，
  `exit` 没走 JMPREL → 说明 exit 不是从这些 so 的 PLT 发的
  （怀疑是 Java `System.exit`、ART 的 `Runtime.exit`，或者 libc 内部路径）。

### 4.5 下一步的两个方向

1. **继续定位 exit(15)**：现在包已经不受乐固限制，可以随便改 dex/so。
   最直接的是给 `System.exit`/`Runtime.exit` 下钩子（改 dex 里的调用点，
   或 hook libc 的 `exit`），拿到调用栈再逐个 patch。
2. **绕开去乐固，改打「UC 插件」**：`assets/ucgamesdk/modules/cn.uc.gamesdk.loader.apk`
   是 assets（乐固不校验），如果 UC SDK 不校验插件签名，可以往它的 classes.dex
   里塞一个静态初始化调用我们的 `Boot`，这样乐固保持原样（签名伪造还生效），
   只多加一个 classes2.dex。需要先测 UC 是否验签。

> 设备上已恢复成原版 11.4.0（`/data/local/tmp/uc114.apk` 留着备用）。

## 五、2026-11-05 23:1x：签名比对是下一个靶子

原包签名证书（META-INF/RELEASE.RSA 里第一张 X.509，DER 733 B）：

- MD5(cert)  = 97a25deab48e5478c726722cde13bcbb（Java getPackageInfo(...).signatures 口径）
- SHA1(cert) = eeb782bf3c9ca209e21803195a13f3c5180659c

Dex 里做这件事的是 com.ssjj.fnsdk.core.SsjjFNUtility：
getSignatures(context) / getApkSignatures(context,path) / md5(...)，
FN SDK 用它算 sign 上报。

**去乐固包里的打法**（可以随便改 dex）：
1. dex 顺序对调：我们的 classes.dex、原 app dex 改 classes2.dex；
2. shadow SsjjFNUtility.getSignatures()，返回上面这个证书的 DER（或者直接让比对恒真）；
3. 重打包验证 exit(15) 是否消失。

对照实验结论（重要）：exit(15) 与 TP2 无关（原版 tersafe2 / stub 都退 15），
只在「乐固不在」时出现 → 是乐固平时替 App 伪装签名/环境，去掉后触发 App 自己的校验。

## 六、下次接着干（2026-11-05 23:5x 收工）

进度：钩子源码已写，**编译报错未修**：

- 11/native/tersafe2_open.c 里 FILE 未定义 → 补 #include <stdio.h>、#include <stdarg.h>，
  并把 a_arg(ap, mode_t) 改成 a_arg(ap, int)（mode_t 会被提升）。
- 编译命令（工作区 tmp 必须指到工作区，否则 NDK 报 Permission denied）：
  $env:TMP='tmp' 后用
  uild\ndk\android-ndk-r26d\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe --target=armv7a-linux-androideabi21 -shared -fPIC -O2 -fno-omit-frame-pointer -o v11\native\libtersafe2-open.so v11\native\tersafe2_open.c -Wl,-soname,libtersafe2.so -llog -ldl
- 然后 repack（去乐固配方见 §4.3）+ zipalign + apksigner + 装机，看 logcat 里
  OPEN/OPENAT/FOPEN path=… caller=…，定位 exit(15) 前读的文件与调用地址。

设备状态：模拟器上是**原版 11.4.0**；原包备份在设备 /data/local/tmp/uc114.apk。
