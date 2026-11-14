# 造梦西游 OL 11.x 逆向任务交接书

> 复制这一整份给新对话即可接续。目标：**让 11.4.0 / 11.6.0 离线可玩，主界面莲花台能显示角色并进入游戏。**

---

## 0. 一句话现状

通信协议已 100% 破解、本地 TLS 服务端已能跑通「更新检查 → 登录 → 选服」；
**卡在两点**：(a) 11.x 的登录场景不渲染服务端下发的角色；(b) 客户端被乐固（libtprt）锁死，
改 libgame 的代码（文件或内存）会被完整性校验杀掉。

---

## 1. 环境

| 项 | 值 |
|---|---|
| 工作目录 | `<desktop>/ol 重构`（ASCII junction：`<path> <path> python argv） |
| 模拟器 | LDPlayer9，`emulator-5554`，Android 9，**32 位 x86 + houdini**，已 root（`su` 可用） |
| 真机 | `ab0a271b`，24117RK2CC，**Android 17 / API 37，arm64-v8a only（无 32 位）、无 root** |
| adb | `<home>/AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| Ghidra | `<path> `<path> `plain`，已分析 `libgame_plain.so`） |
| NDK | `<path> |
| 签名 | `<path> `<your-pass>`），`build\tools\bt\lib\apksigner.jar`，zipalign 用 build-tools 36.1 |
| 模拟器重启 | `<path> reboot --index 0`（约 45s） |

---

## 2. 已破解的通信协议（关键资产）

**客户端 ↔ 服务器**：HTTPS POST `http<path>

```
请求体明文：JSON，action=checkupdate/login/servicelist/...
线上格式：  "-=4399=-" + base64( DES-ECB/PKCS5( JSON ) )      key = b"1#(z1Rr>"
应答格式：  同上，但**没有** "-=4399=-" 前缀（纯 base64）
```

实现：`v11/codec.py`（`decode(body)` / `encode(text, prefix=True)`）。

**应答必需字段**：

```json
{"code":1000,
 "servicetime":<unix>,
 "base":{"host":"127.0.0.1","port":8077,"service":1,
         "pkservice":{"host":"127.0.0.1","port":8077}},   // pkservice 必须是对象！
 "pkservice":{"host":"127.0.0.1","port":8077}}
```

**登录应答（8.0 的形状，11.x 兼容的部分）**：

```json
{"services":[{"service":1,"name":"单机服","roleids":["1001"],"isnew":0,
              "createtime":0,"starttime":0,"endtime":0,"state":1,"new":0}],
 "rolelist":{"1":[{"rid":"1001","username":"玩家","roleid":"1","rolelv":"1",
                   "rolelevel":"1","level":"1","exp":0,"vip":0,
                   "wingid":0,"weaponid":0,"suitid":0}]},
 "lastdata":{"service":1,"rid":"1001","username":"玩家"},
 "lastrid":"1001","username":"玩家",
 "create_role_count":{"1":1,"2":1,"3":1,"4":1}}
```

**类型铁律（11.4/11.6）**：
- `rid` / `roleid` / `rolelv` **必须是字符串**；数字会**崩**
- `login` 应答里**不能带 `savedata`**（一放就把角色状态清成 `INVALID_RID`、`service=INT_MIN`）
- `roleids` 用职业 id（1~4 有效：孙悟空/唐僧/猪八戒/沙僧；5=猫女在 11.x 不存在）

**存档校验门槛**（从 v800 源码逆出来的，11.x 疑似相同）：
`point` 存在；`roledata.player.roleid` 存在；`bag` 需 `bagsize`/`maxglobalitemid`/`spiritcount`；
`level.psl` 是数组；`pet` 是非空数组。

---

## 3. 服务端/脚本资产（`v11/`）

| 文件 | 作用 |
|---|---|
| `codec.py` | 加解密 |
| `mitsrv4.py` | 本地 TLS 服务端。`YJ_LOCAL_ACTIONS=a,b,c` 决定哪些 action 本地应答，其余**代理到真实服务器**并记录 |
| `responses.json` | 应答表（热加载，按 action 通配） |
| `redirect_game_only.py` | 只把游戏 API 域名（zmol/zmxychatxm/zmxyworldbossxm/zm.4399sj.com）的 443 重定向到本地 8443 |
| `try.ps1` | 一键：挂 CA → 起服务 → adb reverse 8443/8077 → 重启游戏 → 截图/报告 |
| `certs/` | `ca.pem`/`srv.pem`/`srv.key`/`c5d7d7c3.0`（CA 装进系统信任区用） |
| `cap169.py` | 16.9 抓包布网脚本（CA + 重定向 + 纯代理） |
| `sdb.py` | 解密 `.sdb` 数据表（base64 + DES-ECB，key `43((zmIp`） |
| `native/libgame_plain.so` | **已解密**的 libgame（.text/.rodata 都 XOR 0xB5，只有 .data 是明文） |
| `native/libtprt.so` | 乐固壳（158 KB） |
| `LEGU_RE.md` / `CURRENT.md` / `FINAL_STATUS.md` | 乐固逆向地图 / 逐轮记录 / 总汇总 |

**MITM 环境要点**：
- CA 必须 `mount --bind /data/local/tmp/cac /system/etc/security/cacerts`（`/data/local/tmp/cac` 里要有系统 137 张 + 我们的 `c5d7d7c3.0`）
- `sysctl -w net.ipv4.conf.all.route_localnet=1`
- `adb reverse tcp:8443 tcp:8443`
- 模拟器重启后 `/data/local/tmp` 会被清空，CA 要重装；旧的 bind mount 要先 `umount`

---

## 4. 8.0 单机版（可玩，已跑通，**服务端脚本都在**）

包名 `org.yjmobile.zmxy`，APK 在 `v800/build/offline-v800-*.apk`（最新 `offline-v800-noblest.apk`）。
它是**进程内 Java 服务端**，数据在 `/data/data/org.yjmobile.zmxy/files/offline/`（**明文 JSON**）：

| 文件 | 内容 |
|---|---|
| `roles.json` | `{"roles":[{rid,roleid,username,rolelv,roleexp,service}...]}` |
| `profile.json` / `profile_<rid>.json` | 当前角色 |
| `savedata.json` / `savedata_<rid>.json` | 存档（bag / roledata / level / pet） |

**改等级 90 + 跳过全部引导的做法**（脚本 `v11/_v8fix.py` + `v11/_v8push.py`）：
- `rolelv` → 90（roles.json 与 profile）
- `roledata.role.rolelv` → 90
- `roledata.player.completeguide` ← `guide_sequence`(35) + `guide_step`(164) 的**全部 199 个 id**
- `level.psl` / `level.mlru` ← `level.sdb` 的 45 个关卡 id

**已知缺陷**：活动类副本（如「八仙过海·蓬莱登岛」）离线服没实现 → 面板显示「不存在该boss」「剩余次数 0」，进去会闪退。常规副本（北天王殿等）正常。

---

## 5. 乐固（libtprt.so）逆向进展

**导出**：`JNI_OnLoad`、`unwind_xx_tpshell_crcI/II/III`、`g_tprt_pfn_array`、`g_tprt_ori_array`
**RTTI 类**：`TssChecker`、`g11FileChecker`、`tpshell::ITssLock`、`TssMutexLock`
**TssChecker vtable**：运行时 `0xb33b478`（base 0x0b318000），方法文件 VA：`0x11d40 / 0x11d58 / 0x11f38`
**已定位的杀进程点**：`0x11d6c: movs r0,#0x1c; svc` = **exit(28)**；`0x11f38` 是判定逻辑（7×0x44 的表里找 0x1c，检查 `[+0x14]/[+0x1c]/[+0x24]`，全 0 就 exit(28)）
**裸 svc 退出码**：16 / 22 / r0 / r0 / 27 / ? / 26 / 7 / 7 / 21 / 28 —— **没有 25、也没有 15**

**已实证的封锁**：
| 手段 | 结果 |
|---|---|
| 改 libtprt 文件 | 自校验 → exit(25) |
| 改 libgame 文件 | 加载期校验 → exit(25) |
| 改 libgame 内存代码 | 运行期校验 → exit(25)（约 100ms 后） |
| GOT hook（libtprt+libgame 的 exit/abort/kill/raise/pthread_exit/syscall） | 全部挂上，exit 码 15→4，但仍死，无 hook 日志 |
| libtprt 两个「r0 由调用者给」的 svc 改 nop | 生效，仍 15 |
| 改 libc exit/_exit/abort 入口 | dlsym 拿到的是 **houdini 桥接地址**，无效 |
| seccomp: exit→EPERM | `exited cleanly (15)` 消失，改成崩溃循环 |
| seccomp: exit→TRAP | 无 tombstone，仍死 |

**结论**：`exit(15)` 是「乐固认为未初始化 → 去死」的判定，最终落到 `exit_group`（seccomp 能拦），
但既不来自 libtprt 的 11 个 svc，也不走两个库的导入槽，走 libc 但 libc 是 houdini 桥接。

---

## 6. 两条路线的死结

**路线 A（原包 + 本地服）**：能到选服界面；`roleids` 能画服务器面板头像；但
`rolelist` **不参与主界面渲染**（8.0 参与，11.x 不参与）→ 莲花台永远空。

**路线 B（去乐固包）**：Java 层完全可控（能起进程内服务端、能改 URL），但
`nativeInit` 里 `exit(15)`，且改 libgame 会被 exit(25) 杀。

**Frida 不可用**：11.x 是 32 位（`lib/armeabi`），真机是 arm64-only + 无 root；
模拟器是 houdini + ptrace 被检测。

---

## 7. 唯一的下一步：逆掉 TssChecker，让校验「通过」

目标：让 libtprt 的判定返回「通过」而不是 `exit`，这样就能在去乐固包里改 libgame，
再用崩溃探针去修「莲花台不显示角色」。

**已有的坐标（直接用）**
- TssChecker vtable 运行时 `0xb33b478`（libtprt base `0x0b318000`），方法文件 VA：
  `0x11d40` / `0x11d58` / `0x11f38`
- `0x11d6c` 是 `exit(28)`（`movs r0,#0x1c; svc`）
- `0x11f38` 是判定逻辑：遍历「7 项 × 步长 0x44」的表找 `0x1c`，
  再检查 `[[r3]+0x14]`、`[[r3]+0x1c]`、`[[r3]+0x24]`，**三者全 0 就调 `0x11d6c` → exit(28)**
- 全部裸 svc 退出码：16 / 22 / r0 / r0 / 27 / ? / 26 / 7 / 7 / 21 / 28（**没有 15、没有 25**）

**建议步骤**
1. **先找 `exit(15)` 的发出者**：它不走 libtprt 的 11 个 svc，也不走 libgame/libtprt 的
   `exit/abort/kill/raise/syscall` 导入槽（全都 hook 过，未触发），但 seccomp 能拦到
   → 说明最终是 `exit_group` 系统调用。查两处：
   - libtprt 的 **Thumb PLT**（之前按 ARM 的 `ldr ip,[pc,#4]` 模式没解出来，换 Thumb 编码再解，
     目标是把 `exit` 的 PLT 桩和调用点找全）
   - `g_tprt_pfn_array` / `g_tprt_ori_array` 这两张表：乐固靠它们「恢复原始代码 + 比对」，
     很可能是**间接调用**（所以没有直接 xref）
2. **优先改「数据」而不是「代码」**：代码段被自校验盯着，但 `.data` 里的
   期望值/标志位一般不被校验。找到判定读的那个字段（`[+0x14]/[+0x1c]/[+0x24]` 那三个），
   让它们满足「通过」的条件即可。
3. **验证手段**：改完后跑 `v11/cycle.ps1 -Game <改过的libgame>`，
   看是否还出现 `exited cleanly`；不死了就说明校验已绕过。
4. **最后才是莲花台**：能跑之后，把 libgame 的 `nativeInit` / 登录场景函数做崩溃探针
   （`v11/_p_nativeinit.py` 那条路子），定位 `rolelist` 为什么没渲染。

> 注意：**没有「脱壳版 11.x」这种东西**——别去找，浪费时间。
> 这台模拟器是 houdini + ptrace 被检测，Frida 用不了；真机是 arm64-only 且无 root，
> 32 位的 11.x 在上面根本跑不起来。所以只能走纯静态逆向这条路。

---

## 8. 关键坑清单（省时间）

- 中文路径会破坏 python argv → 用 `<path>
- PowerShell 里 python `-c "..."` 引号会被吃掉 → **把脚本写成文件**
- libgame 的 `.text` + `.rodata` 都是 **XOR 0xB5**；`.data` 是明文
- libgame 运行时 `.text` 在**第二段 rw-p 映射**（file offset 0x5e4000 起），不是第一段
- 11.4/11.6 的 `roleids` 用职业 id；`rolelist` 必须是**按 service id 分组的对象**
- 加 `ip/host/port` 到 `services[]` 会让服务器名变 `INVALID_STR`
- `savedata` 只能放 enterserver，不能放 login
- 模拟器重启会清 `/data/local/tmp`（CA、dump、改过的 APK 都没了）
- 别用 `SECCOMP_RET_TRAP` 做全局实验（之前崩过模拟器的 system_server）
