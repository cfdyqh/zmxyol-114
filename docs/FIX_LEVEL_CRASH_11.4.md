# 11.4.0 离线版 —— 进关卡闪退的根因与修复（2026-11-07）

## 结论

「进关闪退」根因是**我方 libtersafe2.so stub 返回了全零缓冲**：

- `libgame.so` 在关卡面板/进关时调用 `tss_get_report_data2()`（TP2 反外挂的"上报数据"），
  把返回的缓冲区当**长度前缀二进制结构**解析；
- 我们的 stub 返回 `static unsigned char g_report[256]`（全 0），客户端按 0 长度/0 偏移继续解析，
  最后拿 NULL 去构造 `std::string` → `std::logic_error` → 没人 catch → `std::terminate()` → `abort()`。

真机栈（小米，32 位 ARM，符号完整）：

```
#00 abort
#01 __gnu_cxx::__verbose_terminate_handler
#02 __cxxabiv1::__terminate
#03 std::terminate()
#04 __cxa_throw
#05 libgame.so+0x19c5730      <- std::__throw_logic_error
#08 libgame.so+0x69b174       <- 解析器（读 tss_get_report_data2 的缓冲）
#15 cocos2d::CCMenuItem::activate   <- 点击关卡节点
```

RTTI 解出来是 `St11logic_error`（在 GOT 槽 `0x1f794b8` → typeinfo `0x1f6fba4` → name `St11logic_error`）。

## 修复

**用原版 `libtersafe2.so`**（从 `base(2).apk` 的 `lib/armeabi/libtersafe2.so`，3.6 MB）替换 stub。

原版和我们的 `libtprt-shim.so`（提供 `g_tprt_pfn_array` 等 29+4 个表）配合正常，
不触发乐固校验，也不再崩。

## 另外两个一起修的问题

1. **启动「更新检查失败，网络连接出错(0)」**：客户端第一次请求早于本地服 bind。
   → `Boot.start()` 里在起 `yjboot` 线程**之前**同步调用 `LocalServer.start(app)`（幂等）。
2. **shim 的 url_patch_thread 竞态**：`/proc/self/maps` 快照 + 直接 `memcmp`，映射被回收时踩空
   （真机 `SIGSEGV @ memcmp+52`，backtrace `url_patch_thread+296`）。
   → 改成逐页扫描 + 线程局部 `sigsetjmp` 保护；已确认 `urlpatch: HIT url#0/#1/#2`（第 0 轮全命中）。

## 手机上的残留问题（非游戏 bug）

小米 HyperOS 的音频栈在 SoundPool 建 AudioTrack 时踩 `strlen(NULL)`：

```
#03 strlen_a15
#04 libaudioclientimpl.so (isAppNeedtoChangeStreamType+98)
#07 libAudioTrack.so (AudioTrack::set ...)
#09 libsoundpool.so (Stream::play_l ...)
```

进关卡能进、能打，但一放音效（攻击音）就整进程 SIGSEGV。
已把 `Cocos2dxPrefsFile` 的 `Music/Sound` 置 false（`/sdcard/yj/sound` 可恢复），
登录界面开关确实变灰，但攻击音效仍会触发——是系统层问题，游戏侧无解。
**结论：手机上玩 11.4 需要系统不崩音频（换机/关游戏音效/用模拟器）。**

## 当前可玩环境

- **雷电9（emulator-5554）**：完整可玩
  登录 → 莲花台（角色+进入游戏）→ 村庄 → 世界地图 → 九重天面板（奖励图标/五星/开始挑战）
  → 进关卡 → 攻击（16/20 能量条消耗、怪物在场）全部正常。
- **真机（ab0a271b, HyperOS/Android 17, 32 位可跑）**：能进关卡，音效崩溃。
- **雷电14（Android 14）**：11.4 卡在更新检查（shim 兼容问题，未解决）。

## 构建

```
cycle.ps1 -OutName fix7.apk \
  -Tersafe native\libtersafe2-orig.so \
  -Tprt    native\libtprt-shim-fix2.so \
  -Game    native\libgame_local.so
```

产物：`v11/build/fix7.apk`（683 MB）。安装：`pm install -r -d`（**不要加 -g**，小米会拒）。

## 关键数据/配置

- 登录应答：`v11/login.11.4.WINNER.json`（`lastdata` 必须是 **空数组 `[]`**）
- 设备覆盖点：`/sdcard/yj/{login_plain,login,login_serverinfo,login_enterserver}.json`
- 数据表：249 张 `.sdb` + `sdb.list` → `/sdcard/Android/data/com.zmxyol.union.uc/files/update/database/`
