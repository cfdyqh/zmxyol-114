# 乐固 libtprt.so 逆向地图（11.4/11.6，158 KB）

## 一、导出符号（这是它给 libgame 用的接口）

| 地址 | 符号 | 作用 |
|---|---|---|
| 0x326d | `JNI_OnLoad` | 注册 JNI natives（真逻辑在 native 里） |
| 0x5435 | `unwind_xx_tpshell_crcI` | 完整性校验（真正的 checksum 函数） |
| 0x54a9 | `unwind_xx_tpshell_crcII` | 文件里是 **`bx lr` 空实现**（运行时可能被改写） |
| 0x54ad | `unwind_xx_tpshell_crcIII` | 遍历一张 7 项表找 `0x1b`，再取 `[r3+0x14]` |
| 0x24d00 | `g_tprt_pfn_array` | 函数指针数组（乐固用来「恢复原始代码」） |
| 0x27c4c | `g_tprt_ori_array` | 原始数据数组（校验基准） |

## 二、RTTI 抓到的类

| 名字串 | 位置 | typeinfo 指针引用 |
|---|---|---|
| `10TssChecker` | 0x2180c | **0x22488**（→ typeinfo 在 0x22484） |
| `g11FileChecker` | 0x1c391 | 无（可能被内联/改名） |
| `N7tpshell8ITssLockE` | 0x1db6c | 0x2242c |
| `N7tpshell12TssMutexLockE` | — | 待查 |

## 三、其它关键字符串

- `ch_tprt_mem` —— 内存校验（我们之前撞到的就是它）
- `SubstrateHookFunction(%p, %p, %p, %p)` —— 检测 Substrate/Cydia 注入
- `TSS_SDK_SIGDATA`、`libtprt.so`
- 一堆 minizip 报错（`incorrect header check` 等）——乐固的 payload 是 zip 加密的

## 四、exit 路径（之前扫的）

11 处裸 `svc #0`（`movs r7,#0xf8` = exit_group），退出码分别是
16 / 22 / r0 / r0 / 27 / ? / 26 / 7 / 7 / 21 / 28 —— **没有 25**，
说明 `exit(25)` 走的是别的路径（很可能是 libc `exit`，或另一处没扫到的代码）。

**已实证**：改 libtprt 文件 → 自校验 → exit(25)；
改 libgame 文件 → 加载期校验 → exit(25)；
改 libgame 内存 → 运行期校验 → exit(25)（约 100ms 后）。

## 五、下一步（可执行）

1. **运行时抓 vtable**：让 11.6 正常跑起来（它会活很久），dump libtprt 的
   `.data.rel.ro`，用 typeinfo（运行时 = base + 0x22484）反查 vtable，
   再用 vtable 的方法地址去反编译 `TssChecker` / `g11FileChecker` —— 
   这是拿到「校验函数本体」的最短路径（和之前搞 `YJLoginManager` 的方法一样）。
2. 找到 `ch_tprt_mem` 的调用点（字符串 xref 或 vtable 方法里出现），
   确认它比较的是什么（多半是 `g_tprt_ori_array`）。
3. 目标：让 `ch_tprt_mem` 永远返回「通过」，这样改 libgame 就不再被杀。

## 六、TssChecker 的 vtable 和「杀进程」代码（本轮新进展）

运行时 dump libtprt（base 0x0b318000，160 KB）后，用 typeinfo 反查到了 vtable：

```
10TssChecker  typeinfo 运行时 0x0b33b484
   vtable @0x0b33b478
     [+0x00] 0xb329d41 -> 文件 0x11d40
     [+0x04] 0xb329d59 -> 文件 0x11d58
     [+0x08] 0xb329f39 -> 文件 0x11f38
```

反汇编这三点，看到真正的杀手：

```
0x11d6c:  movs r0, #0x1c        ; 28
0x11d70:  push {r7}
0x11d72:  movs r7, #0xf8        ; exit_group
0x11d74:  svc  #0               ; ← exit(28)
```

而 `0x11f38` 是一个 TssChecker 方法：

1. 调 `0x11e88`，返回 0 就跳到 `0x12054`
2. 调 `0x11ec0`，非 0 就跳过
3. 遍历一张 **7 项、步长 0x44** 的表，找值 `0x1c`
4. 找到后检查 `[[r3]+0x14]`、`[[r3]+0x1c]`、`[[r3]+0x24]`
5. **三个都为 0 就 `call 0x11d6c` → exit(28)**
6. 否则继续，循环 10 次调 `0x11efc` 再校验

→ 这就是「状态检查失败就杀进程」的典型结构。

## 七、exit(25) 还没定位

- 全 .text 里**没有** `movs r0,#0x19`（25）
- 11 处裸 svc 的退出码里也没有 25
- 说明 25 是**别处**发出的（libc `exit()` 的调用点，或另一个模块）

下一步：把 libtprt 的 PLT 表按 Thumb 编码正确解出来（我按 ARM 的
`ldr ip,[pc,#4]` 模式找没找到 stub），定位 `exit` 的调用点，
看哪个分支会传 25 进去。

## 八、GOT hook 实测：exit 码从 15 变成 4（说明拦到了东西）

思路：**不改代码段**（会被自校验抓），只改 **GOT 槽**，把
`exit/abort/kill` 换成我们自己的函数。槽位（文件 VA）：

| 库 | exit | abort | kill |
|---|---|---|---|
| libtprt.so | 0x23f60 | 0x23fec | 0x23fcc |
| libgame.so | 0x1f79d24 | 0x1f79bc8 | 0x1f79ee0 |

运行时按 `/proc/self/maps` 的「文件偏移 + 可写映射」算地址，`mprotect` 后写入。

实测（去乐固包 + 把 nativeInit 改成 `bx lr` 触发校验）：

```
hooked exit/abort/kill (old exit=0x0)
libgame got exit=0x...d24 abort=0x...bc8 kill=0x...ee0
hooked libgame exit/abort/kill
patched nativeInit @0x...: 4df5985c -> bx lr
tprt initialize -> 0
tprt release ok
Process exited cleanly (4)          ← 原来是 15！
```

**结论**：
1. GOT hook 生效，而且**改变了死亡路径**（15 → 4），证明我们确实拦到了乐固的 kill switch；
2. 但没有打印出我们的 `!! ... suppressed` 日志 → 说明 exit(4) 不是走我们 hook 的
   `exit/abort/kill` 槽，而是另一条路（可能是 `_exit`、`raise`、或某个裸 svc）；
3. 这个构建里 **Legu 的 `initialize()` 竟然返回 0**（`tprt initialize -> 0`），
   说明它初始化成功了，之后才被某个检查判死。

**下一步**：把 `_exit` / `raise` / `pthread_exit` 也挂上，并让 handler 改成
「先打印、再从 maps 里 dump 一下返回地址」——这样就能确定是哪个库、哪个函数在杀。

## 九、把所有能挂的 exit 路径都挂了 —— 仍然是 15

本轮把能想到的「温柔」手段全试了：

| 手段 | 结果 |
|---|---|
| libtprt 的 exit/abort/kill/raise GOT 槽 | 挂上，未触发 |
| libgame 的 exit/abort/kill/raise/pthread_exit/syscall GOT 槽（含第二组 0x205ffec/0x205fff4） | 全部挂上，未触发 |
| libtprt 两个「r0 由调用者给」的裸 svc（0x331e / 0x4522）在内存里改成 `0000` | 生效（日志确认 `00df -> 0000`），仍然 15 |
| libc 的 exit/_exit/abort 入口直接改写 | 入口字节是 **ARM** 指令（`ee7002ef` 等），说明 dlsym 拿到的是 houdini 的桥接地址，改了也没用 |
| 我们自己的 Java 代码里有没有 exit | 没有（grep 过） |

**结论**：`exit(15)` 既不走上表任何一个导入符号，也不是 libtprt 的裸 svc ——
很可能是在 **houdini 的 x86 侧**或某条我们看不到的路径上发出的。

## 十、下一步：用 seccomp 把 exit_group 变成「失败」

最干净的办法：在进程里装一个 **seccomp filter**，把 `exit_group` / `exit`
从「执行」改成 **`SECCOMP_RET_ERRNO`**（返回 EPERM）。这样无论谁想退出，
系统调用都会失败、进程继续跑，我们就能看到它接下来走到哪一步。

（注意：之前试过 `SECCOMP_RET_TRAP`，那样会 crash 掉模拟器的 system_server；
`ERRNO` 是安全得多的返回类型。）

## 十一、seccomp 实测：exit 被拦住了，但进程改用另一种方式死

装好的过滤器（12 条规则，覆盖 arm/x86_64/i386 的 `exit`(1/60) 与 `exit_group`(231/248/252)）：

```
YJOffline: seccomp installed: exit/exit_group -> EPERM (12 rules)
```

结果：**不再出现 `exited cleanly (15)`** —— 说明确实拦到了。
但进程改成**崩溃循环**：

```
ActivityManager: Process com.zmxyol.union.uc (pid 13822) has died: fore TOP
ActivityManager: Process com.zmxyol.union.uc (pid 13844) has died: fore TOP
...（每 20~30ms 一个新 pid）
```

原因：bionic 的 `_exit()` 里 `exit_group` 返回 EPERM 后会继续往下走（`exit` 也被拦），
最终进到未定义路径 → 进程还是死了，只是不再是「干净退出 15」。

**结论**：光「不让它退出」不够，必须找到**是谁决定退出**、把那处逻辑改掉。

## 十二、下一步

1. 用 `SECCOMP_RET_TRACE` + 自己的 tracer 拿栈（需要 ptrace，风险高，之前 TRAP 崩过模拟器）；
2. 或者回到静态：在 libgame 里找「常量 0xf 出现在 `bl exit` 附近」的代码——
   虽然 exit 的 GOT 槽我们挂过没触发，但那可能是因为走了 `_exit` 或另一个库；
3. 最稳的：先在 **pristine（乐固正常）包** 里确认 `exit(15)` 到底会不会出现
   （正常包不会），从而反推它是「乐固没初始化」这条分支独有的。
