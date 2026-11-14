# 造梦西游 OL 11.x 离线化 —— 结论汇总（乐固攻坚阶段）

最后更新：2026-11-06 22:30

## 一、两条路线各自的结论

### 路线 A：原包（乐固生效）+ 本地 TLS 服务端
能一路走到选服界面，但**主界面莲花台的角色和「+」建角色入口出不来**。
服务端能影响的只有：`roleids`（面板头像）、`code/servicetime/base/services/
create_role_count/pkservice/savedata`。
`rolelist` 会被解析（面板头像证明），但**不参与主界面渲染**——这是 11.x 与 8.0 的关键差异。

### 路线 B：去乐固包（改 manifest 绕壳）
能改 Java 层（我们的 Application 能跑、能起进程内服务端），但
**`nativeInit` 里 `exit(15)`** 挡住。改 libgame（文件/内存）会被 libtprt 校验杀掉。

## 二、乐固（libtprt.so）逆向进展

| 项目 | 结论 |
|---|---|
| 导出 | `JNI_OnLoad`、`unwind_xx_tpshell_crcI/II/III`、`g_tprt_pfn_array`、`g_tprt_ori_array` |
| RTTI 类 | `TssChecker`、`g11FileChecker`、`tpshell::ITssLock`、`TssMutexLock` |
| TssChecker vtable | 运行时 `0xb33b478`，方法文件 VA `0x11d40 / 0x11d58 / 0x11f38` |
| 杀进程代码 | `0x11d6c: movs r0,#0x1c; svc` = **exit(28)**；`0x11f38` 是判定逻辑 |
| 裸 svc 退出码 | 16 / 22 / r0 / r0 / 27 / ? / 26 / 7 / 7 / 21 / 28（**没有 25、也没有 15**） |
| 完整性校验 | 改 libtprt 文件 → exit(25)；改 libgame 文件 → 加载期 exit(25)；改 libgame 内存 → 运行期 exit(25) |

## 三、本轮新做的手段与结果

| 手段 | 结果 |
|---|---|
| **GOT hook**（改 exit/abort/kill/raise/pthread_exit/syscall 的导入槽，不动代码） | 全部挂上；exit 码从 15 变 4（说明拦到了东西），但仍死 |
| 把 libtprt 两个「r0 由调用者给」的裸 svc 改成 nop | 生效，仍 15 |
| 直接改 libc 的 exit/_exit/abort 入口 | dlsym 拿到的是 **houdini 桥接地址**（ARM 指令），改了无效 |
| **seccomp：exit → EPERM** | `exited cleanly (15)` 消失，改成崩溃循环 → 说明确实拦到了内核调用，但光拦不够 |
| **seccomp：exit → TRAP(SIGSYS)** | 没有 tombstone，进程照样死（循环） |

## 四、结论

**`exit(15)` 是一条「乐固认为未正确初始化 → 去死」的逻辑**，它最终落到
`exit_group` 系统调用上（seccomp 能拦到），但：

- 不是 libtprt 的 11 个裸 svc 之一
- 不走 libgame/libtprt 的 exit/abort/kill/raise/syscall 导入槽
- 走 libc，但 libc 在这台机器上是 houdini 的桥接，无法直接改

**要真正破掉，需要在 houdini 之外的环境（真机 + Frida/ptrace）或者
完整逆出 TssChecker 的判定逻辑（让它返回「通过」而不是「退出」）。**
后者我们已经有了 vtable 和方法地址，是可以继续的——只是工作量大。

## 五、当前设备状态

| 包 | 版本 | 状态 |
|---|---|---|
| `org.yjmobile.zmxy` | 16.9.0 | 在线版，可玩（7.4GB 资源已下完） |
| `com.zmxyol.union.uc` | 11.6.0 | 已 force-stop；本地服配置在 `v11/responses.json` |

## 六、资产清单（都在 `v11/`）

- `codec.py` —— 4399 通信加解密（key `1#(z1Rr>`）
- `mitsrv4.py` —— 本地 TLS 服务端（可本地应答 / 可纯代理抓包）
- `responses.json` —— 应答表（热加载）
- `cap169.py` —— 16.9 抓包布网脚本
- `redirect_game_only.py` —— 只重定向游戏 API 域名
- `native/libtprt.so`、`libgame_plain.so` —— 解密后的镜像
- `terprt*` 系列分析脚本、`tersafe2_gothook2.c`（GOT hook + seccomp 替身）
- `LEGU_RE.md` —— 乐固逆向地图
- `CURRENT.md` —— 逐轮工作记录
