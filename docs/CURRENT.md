# 造梦西游OL 11.4.0 离线改造 · 当前状态总览（第 6 轮末）

> 这份是**唯一入口**：先看这里，细节再翻各轮交接。
> 目标：把 11.4.0 改成可离线单机游玩（本地服应答更新检查/登录/选服，进游戏）。

---

## 一、已确定的事实（不用再查）

### 1.1 报文协议（完全解开，可直接用）

```
wire = b"-=4399=-" + base64( DES-ECB/PKCS5( json ) )
key  = b"1#(z1Rr>"          # libgame .bss +0x1104
```
- 参考实现 `v11/codec.py`（`decode`/`encode`），响应信封 `{"code":1000,"servicetime":<秒>}`。
- 已抓到并解密的请求：`v11/checkupdate_request.json`。`sensitivefiles` 会上报 libgame 等文件的 MD5。

### 1.2 客户端结构
- 乐固壳 `zmxyApplication extends TPShellApplication`；`libtprt.so` = 乐固 native；
  de-乐固包靠**换 manifest 的 application 名**跳过壳。
- `libgame.so`（33 MB）：文件里 `.text` 是 **XOR 0xB5**，运行时由 libtprt 构造期原地解密
  （映射是 `rw-p`）。→ 现在能任意改 libgame：`v11/patch_libgame_xor.py`（解密→补丁→重加密，
  已验证与运行时镜像 0 差异）。
- 渠道实际是 **7game/7723 的皮肤 + UC/九游 SDK**（prefs 全是 `cn.uc.gamesdk.*`）。
- 模拟器：LDPlayer9，**32 位 x86 + houdini**；`/system` 只读（要 CA 就 bind mount）；
  `ro.debuggable=1`；**houdini 不允许执行我们注入的 ARM 代码（SIGILL）**，ptrace 会被反调试打成 exit(4)。

### 1.3 原包（乐固生效）能跑到哪
`更新检查 → 资源加载 → 9game 账号登录 → 实名认证（要真实身份证）`。
本地服（`v11/mitsrv3.py` + `v11/responses.json`）已能可靠地过更新检查。

### 1.4 de-乐固包卡在哪
`nativeInit` 处 `exit(15)`。已排除：guest libc 各终止入口（hook 验证有效）、
libgame 裸 svc、TP2 初始化、文件/内存可见的显式调用。
**未解决**：exit 的真实来源。相关线索：
- libtprt 有 11 处内联 `exit_group` 裸 svc（`0x2bd4 0x2eb8 0x331e 0x4522 0x5414 0x738e
  0x85b0 0x8988 0xcdf8 0xdf8a 0x11d74`），所在函数：`FUN_12bcc / FUN_12eb0 / (无) /
  FUN_1451c / FUN_1540c / (无) / (无) / FUN_18980 / (无) / (无) / FUN_21d6c`（Ghidra 地址）。
- `ch_tprt_mem` 字符串共 9 处，但**在 .data 里**（VA 0x2415c/0x24410/0x24ecc/0x250a8/…），
  不是 .rodata，也不是普通日志串 —— 很像**完整性校验的登记表**（名字+期望值）。
- 把从乐固生效进程 dump 的 `g_tprt_pfn_array`/`g_tprt_ori_array` 写回去 → **exit(7)**（自身内存校验被触发）。

---

## 二、两条路和各自唯一的卡点

| 路线 | 能到哪 | 唯一卡点 | 价值 |
|---|---|---|---|
| **A. 原包 + 本地服（root）** | 更新检查 ✅、资源加载 ✅、登录界面 ✅ | **9game 账号 + 实名认证** | 需要用户配合；每启动都要真登录 |
| **B. de-乐固 + FnForce** | EarlyBoot/数据表/本地服/URL patch/TP2 init 全通 | **exit(15)** | 真离线，最符合目标 |

## 二之二、登录已经打通到「客户端真的把 login 发给我们」（重要进展）

用户用真账号登录成功后，抓到并解密了真实 login 请求：

```json
{"ext":{"sid":"sst177230d...","cpId":21443,"fngid":"1452242389508580","fnpid":"5",
        "gameId":539817,"serverId":0,"channelId":"2","versionSDK":"8.0.4"},
 "md5":"0f5427d1...","pid":"5","uid":"","name":"","safe":"3d039681...",
 "uuid":"64D8CD29...","action":"login","channel":108,"service":-1,
 "version":"s11.4.0","gametime":0,...}
```

关键事实：
1. **SDK 登录是通的**（有 sid），游戏也把 login 发到了我们的本地服。
2. **真服务器对 login 也拒绝**：`{"code":1001,"errorMsg":"服务器繁忙，请稍后再试0","servicetime":...}`
   （用 `v11/replay.py login` 从 PC 直接回放验证过）→ 拿不到正版应答，只能自己造。
3. **每条应答必须带 `base`**：`{"host":"127.0.0.1","port":8077,"service":1,"pkservice":1}`
   —— 客户端在 `analyzeAndApplyDataToGame` 里用 `base.host/port` 去连**游戏大厅 TCP socket**。
   之前 `responses.json` 没 `base`，这就是登录卡住的原因之一。
4. **SDK 会记住账号**：第一次真登录之后，重启游戏点「同意并登陆」就会**自动登录**，
   不需要再输账号密码 → **迭代成本很低**，我这边可以自己反复试。

### 现在的失败模式（三种，各自的含义）

| 应答 | 客户端反应 | 说明 |
|---|---|---|
| 本地造的 login（带 base + v800 全套字段） | **SIGSEGV（fault addr 0x0，GLThread）** | JSON **解析成功**，但某字段为 null 被解引用 |
| 代理转发真服务器的 login | 弹「**网络传输数据出现错误**」 | 没解析成功（代理层 body 处理有 bug，多半是 chunked） |
| `-=4399=-` 前缀关掉（`YJ_PREFIX=0`） | 同样是 SIGSEGV | 前缀不是问题，客户端能容忍 |

**崩点已经定位到 guest 调用链**（从 tombstone 的栈里读出来的 libgame 返回地址，
libgame 运行时基址 0x3118000）：

```
0x8839f4 / 0xe7321c / 0xe7c6ec / 0xe7c8e0        （都在 .text 里）
```

**重要发现：libgame 的 .text 是 ARM / Thumb 混合**（0x8839f4 附近是 ARM 指令，
`ldr r8,[sp,#0x88] / mov r1,r5 / add r0,sp,#0x30`），所以之前「全 Thumb」的扫描
（比如裸 svc 扫描）可能漏掉 ARM 段里的东西 —— 以后静态分析要两种模式都过。

→ 下一轮就从这四个地址进：用 Ghidra 以 **ARM** 模式反编译这几个函数，
看它到底取的是哪个 JSON key（空对象/缺字段 → NULL 解引用），然后补字段。

## 二之三、第 8 轮：已确认「login 应答解析成功但缺字段」

自动登录（不输密码）已经能用，迭代一轮约 50 s（`v11/try.ps1`）。试过的 login 应答变体**全部**
在同一处崩（`SIGSEGV / fault addr 0x0 / GLThread`，JIT pc 在 0x4a2xxx–0x4a5bxx 之间浮动）：

| 变体 | 结果 |
|---|---|
| v800 全套字段（base/services/rolelist{}/lastdata/create_role_count/uid/…） | 崩 |
| `services` 补成 2 项（防 1-based 越界） | 崩 |
| `rolelist` 改成含一条角色的**数组** | 崩 |
| 关掉 `-=4399=-` 前缀 | 崩 |
| 把非 JSON 请求（TP2 配置/WebView）改成转发真服务器 | 崩（不是 TP2 的问题） |

→ **v800 那套字段不够**，11.4.0 的登录处理还要别的 key，且缺 key 会直接 NULL 解引用。
只能反编译确认。**下一步就用 tombstone 里的 4 个 guest 返回地址进 Ghidra（ARM 模式）**：

```
0x8839f4   0xe7321c   0xe7c6ec   0xe7c8e0      （libgame 运行时基址 0x3118000；.text 里 ARM/Thumb 混合）
```

具体做法：
1. `open_binary`/Ghidra 导入 `v11/native/libgame_rt.so`（33 MB，ARM，**要开 ARM 和 Thumb 双模式**，
   Ghidra 用 `Language/Compiler: ARM:LE:32:v8` 会自动按符号/对齐判断，但函数起点要手动补）；
2. 跳到 `0x8839f4`/`0xe7c8e0`，反编译所在函数，看它从 JSON 里取哪些 key，
   特别注意 `->`/`[]` 之后立刻解引用的地方（那就是 NULL 崩点）；
3. 把缺的 key 用合理形状补进 `v11/responses.json` 的 `*login*`（热加载，改完直接 `try.ps1`）。


## 二之四、第 9 轮：代理转发修好了，证实「真服务器拒绝服务」

- `mitsrv4.py` 的 `proxy()` 现在会**正确处理 `Transfer-Encoding: chunked`**
  （真服务器是 nginx + CDN，所有应答都是 chunked）。修好之后能干净解出真服务器应答：

  ```
  REAL PLAIN [login]: {"code":1001,"errorMsg":"服务器繁忙，请稍后再试0","servicetime":1793958628}
  ```

- 用**刚登录出来的新鲜 sid** 一样是 1001 → **不是会话过期，是服务器真的关服拒服**。
- 真服务器应答的格式细节（照抄时注意）：**chunked 传输**、body 是 base64 且
  **没有** `-=4399=-` 前缀；我们自己的应答带前缀客户端也能吃，所以前缀不是问题。
- 客户端对 `code:1001` 的反应是**弹错误框（不崩）**；对 `code:1000` 的成功应答才崩
  （SIGSEGV / fault addr 0x0）。→ **崩点在成功路径**，而成功应答没有真实样本可抄，只能反编译。

## 二之五、静态分析这个目标的硬限制（重要，别再浪费时间）

libgame 里所有 JSON key 字符串（`rolelist/services/base/lastdata/create_role_count/...`）
**都不被代码用字面指针引用**：把每个 key 在 decrypted 池里的 VA 拿去全 `.text` 搜 4 字节字面量，
**命中数全是 0**。字符串访问走的是**运行时索引/解密机制**，所以 xref-based 分析
（Ghidra/IDA 常规套路）在这个目标上**系统性失效**：

- 找不到 `ch_tprt_mem` 的校验函数（libtprt 同理）；
- 找不到 login 处理函数读的是哪个 key。

要继续静态分析，必须先逆出那个「字符串池索引」函数（`str(index)` 之类），工作量不小。


## 二之六、第 10 轮：已经进到「选服/选角」界面（本轮最大进展）

有了自动登录（不用输密码），我做了十几轮「改 responses.json → 重启 → 看反应」的二分，
现在**登录成功路径的基本字段已经摸出来了**。下面这张表是本轮最值钱的东西：

| 登录应答里的字段 | 客户端的反应 | 结论 |
|---|---|---|
| **只有** `code:1000` + `base` | 弹「没有发现可用服务器」 | `base` 够用，缺服务器表 |
| `+ services:[{service,name,createtime,starttime,endtime,new,state}]` | 弹「**创建角色数量数据获取失败**」 | 服务器表被接受了 |
| `+ create_role_count:{"1":..,"5":..}` | **进入选服/选角界面**（莲花台 + 服务器/账号迁移/隐私协议） | 这一步通了 |
| 服务器对象缺 `roleids` | 右上角「服务器: **INVALID_STR**」 | `roleids` 是服务器名解析的关键 |
| 服务器对象带 `roleids:[1001]` | 右上角「服务器: **单机服1**」✅ | 服务器名正确显示 |
| 顶层 `rolelist` 是**数组** | 不崩，但莲花台空 | 数组被忽略 |
| 顶层 `rolelist` 是对象 `{svc:[role]}` | **SIGSEGV fault 0x4** | 这个形状在 11.4.0 会崩 |
| 顶层 `rolelist` = `{svc:{rid:role}}` | 不崩，莲花台仍空 | 也不是这个形状 |
| `services[i].rolelist=[role]` | 不崩，莲花台仍空 | 角色不从这读 |

**点「服务器」按钮会打开「更换服务器」面板，5 个单机服都正常列出**
（截图 `v11/shot_srvui2.png`）。

还没解决的：**莲花台上没有角色**。8.0 的 `roleid` 是 1..4（悟空/唐僧/八戒/沙僧），
11.4.0 的 `role.sdb` 解出来后 **id 1..4 确实是这四个主角**（31..34 是「邪」版），
但用户提示「这个版本有不一样的东西」——所以下一步要从**建角色流程**（createrole）
反推 11.4.0 真正需要的角色字段，而不是照搬 8.0 的 rid/roleid 组合。

顺带：`role.sdb` 已经能用 `v11/sdb.py` 解开（base64 + DES-ECB，key `43((zmIp` 和 v8 一样），
29 条记录，字段有 id/name/mission_id/type/resource_id/base_hp_factor/...。

当前可用基线：`v11/responses.baseline_servers.json`（能进到选服界面、服务器名正确）。


## 二之七、第 11 轮：找到「反馈通道」——客户端自己的存档文件

**`/data/data/com.zmxyol.union.uc/files/savedata.dat` 用的就是同一套 `-=4399=-` 编码**
（base64 + DES-ECB，key `1#(z1Rr>`），解出来是客户端**自己解析后的状态**：

```json
{"rid":"1001","cuid":"","uuid":"4364D7...","appid":"4399zmxyx","cache":null,
 "cname":"","roleid":0,"service":1,"version":"11.4.0","uniqueid":-2147483648,
 "username":"","appversion":"11.4.0","servicename":"单机服1",
 "modification_time":"...","kSaveKeyDataFromServers":null,
 "kSaveKeyDeleteDataFromServers":null}
```

这条通道价值极大：**每轮改完应答，读一眼 savedata 就知道客户端到底吃进去了什么**
（`v11/roleprobe.ps1` + `v11/_sdsave.py` 已经把这套流程自动化）。

### 已经确认的结论

| 我们的角色对象 | savedata 里的 rid | 说明 |
|---|---|---|
| `{"rid":1001}`（数字） | `"INVALID_RID"` | **rid 必须是字符串** |
| `{"rid":"1001"}`（字符串） | `"1001"` ✅ | 角色被真正解析了 |
| 再加 `username` | `username` 有时是 `"Wukong"`、有时 `"INVALID_STR"` | 取决于走的哪条分支 |
| 加 `rolelv`/`levelid`/`level` | 客户端**更早崩**（fault 0xd37fc9） | 这些字段的取值/类型有坑 |

`roleid` 在 savedata 里始终是 `0`（说明客户端认的「当前角色」还空着），
`servicename` 已经是 `单机服1` ✅。

### rolelist 的形状（11.4.0 和 8.0 不一样）

| 形状 | 结果 |
|---|---|
| `{svc: [role]}`（8.0 的形状） | rid 能解析，但随后 **SIGSEGV fault 0x4** |
| `{svc: {rid: role}}` | 不崩，但角色不显示 |
| `services[i].rolelist = [role]` | 忽略 |
| 顶层直接给 `rid/roleid/username/...` | 不崩，莲花台仍空 |

### roleids 的效果（重要）

服务器对象里 `roleids` 决定「这个服有哪些角色」：
- 缺 `roleids` → 右上角「服务器: **INVALID_STR**」
- `roleids:['1001']` → 右上角「服务器: **单机服1**」✅ 且「更换服务器」面板正常（`shot_srvui2.png`）
- `roleids:[]` → 服务器名正常，但莲花台也没有「+」建角色入口

用户提示：11.4.0 的建角色入口是**左上角的「+」**，现在没有出现 —— 说明客户端认为
「这个服的角色表已经满了/不可建」。下一步重点就是搞清楚 `roleids` + `rolelist` 的
正确组合，让客户端给出「+」。


### 第 11 轮后半段：savedata 反馈通道用起来之后的结论

用 `v11/roleprobe.ps1`（改应答 → 跑 → 读 savedata.decoded）连续试了十几种角色形状：

| 角色对象 | savedata 的 rid | username | roleid | 结论 |
|---|---|---|---|---|
| `{}` / 数字 rid | `INVALID_RID` | `INVALID_STR` | 0 | 不认 |
| `{"rid":"1001"}` | `"1001"` ✅ | `""` | 0 | rid 认了 |
| `+ username:"Wukong"` | `"1001"` ✅ | `"Wukong"` ✅ | 0 | username 也认了 |
| `+ cname/uniqueid/roleid/service` | `"1001"` ✅ | `"Wukong"` ✅ | **0** | `cname`/`uniqueid`/`roleid` **不被读** |
| `+ rolelv/levelid/level` | — | — | — | 更早崩（fault 0xd37fc9 / 0x0） |
| career/id/job/type 全家桶 | — | — | — | 更早崩（连 savedata 都没写） |
| `rolelist` = 字符串数组 | `INVALID_RID` | `INVALID_STR` | 0 | 元素必须是对象 |
| `rolelist` = `{svc:{rid:role}}` | `INVALID_RID` | — | 0 | 不认 |

**关键判断**：`rid` 和 `username` 被正确解析，说明角色对象的位置和形状**已经对了**；
`roleid` 永远是 0 说明客户端认「角色职业/模型」用的是**另一个键名**（还没找到），
所以莲花台画不出人物。

而 `rolelv/levelid/level/career...` 这些会**让客户端提前崩**，说明它们要么类型不对、
要么不是这个阶段该出现的字段 —— 需要真正的字段清单，靠猜效率已经很差。

### 现在的最短路径

1. **逆出字符串池索引机制**（`FUN_0001ceb4` 那类），才能定位 `YJSimpleRoleInfo` /
   `YJServerInfo` 的反序列化函数，直接读出它认的键名（这一步做完，role/lobby 的字段
   都能一次性拿全，不用再猜）。
2. 或者：去 Ghidra 里按 `role.sdb` 的字段名（`base_hp_factor` 等静态字符串，**这些在 .rodata
   里是明文**）反查引用它们的结构体/解析器 —— 那条链路会带出角色对象的字段表。


## 二之八、第 12 轮：为什么会话静态分析一直失败——.rodata 也是加密的

用户要求「以后不行的直接逆」，所以这一轮把静态分析的前提重新核了一遍，结论很重要：

### 事实 1：libgame 的 `.rodata` 在文件里是**加密**的

同一地址，文件 vs 运行时内存：

```
0x1bf8db8  file: c1 d0 d2 cc 9b c5 db d2 ...   runtime: "rolelist\0..."
0x1c06e88  file: 95 95 95 95 95 95 52 37 ...   runtime: "services\0..."
0x1c06d59  file: da c7 ea d7 d4 c7 ea 84 ...   runtime: "uid\0..."
```

- 不是简单单字节 XOR（每个字节异或值不同），是流式/表式加密；
- 加载时 libgame 把整个 `.rodata` **原地解密**（映射是 `rw-p`）；
- 所以 **Ghidra/IDA 直接看文件时，字符串全是乱码**——这就是为什么
  「搜字符串」「找 xref」这套常规流程在这个目标上系统性失效。

### 事实 2：代码里**没有任何地方**出现这些字符串的地址

- 全文件搜 `0x1bf8db8`（"rolelist"）、`0x1c06e88`（"services"）、`0x1c06d59`（"uid"）
  的 4 字节小端 → **出现 0 次**；
- `.data` / `.bss` 运行时 dump（`v11/gdata.bin`、`v11/gbss.bin`）里，**指向 .rodata 字符串起始的槽 0 个**；
- `.rodata` / `.data` / `.bss` 里也**没有**密集的「字符串偏移表」（≥6 连排的 0 个）。

→ 说明引用机制是**运行时算出来的**（按 id 解密/查表后拼地址），不是静态指针。
这也解释了之前所有失败：`ch_tprt_mem` 找不到 xref、`rolelist/services` 找不到 xref、
`.text` 里搜 key 地址命中 0，全都源于同一个原因。

### 下一步（两条都能走通，都不靠猜）

1. **找 .rodata 的解密器**：既然加载时原地解密，就一定有「读文件密文 → 写内存」的循环。
   文件密文和运行时明文我们**都有**（`libgame.so` vs `r1.bin`），可以：
   - 先求每个字节的「密文⊕明文」，看是不是位置相关的简单序列（可能直接暴露密钥流/表）；
   - 再拿这个序列的特征常数去 `.text` 里搜，定位解密函数；
   - 解密器通常和「字符串按 id 取用」的函数挨着，顺着就能找到 key 表。
2. **差分堆对比**（不依赖静态）：给客户端发「带角色」和「不带角色」两种登录应答，
   各 dump 一次堆，做差分。差异出来的结构体附近就是角色解析的结果，
   结构里的字段名/常量能直接告诉我们 11.4.0 认哪些键。

相关脚本：`v11/_strtables2.py`、`_strslots.py`、`_dbbss.py`、`_idxrun.py`、`_idxrun2.py`（都是本轮验证用的）；
运行时 dump：`v11/gdata.bin`（.data）、`v11/gbss.bin`（.bss）、`v11/r1.bin`（.rodata 明文）。


### 第 12 轮的决定性发现：`.rodata` 也是 XOR 0xB5

上一段推测「.rodata 用了另一种流式加密」——**错了**。真实原因是：**整个 libgame 文件
（.text 和 .rodata 都是）XOR 0xB5**，而我之前一直在「只解了 .text」的半成品上做分析，
所以字符串全是乱码、搜什么都没结果。

验证方式（一行就能复现）：

```python
d = open('v11/libgame.so','rb').read()
seg = bytes(b ^ 0xB5 for b in d[0x1bdaf98:0x1bdaf98+0x126b90])   # .rodata
# -> 'rolelist' / 'services' / 'create_role_count' 全部是明文
```

解出来后协议字段一览（**这才是 11.4.0 真正的字段名表**）：

| 字段 | 文件偏移 | 字段 | 文件偏移 |
|---|---|---|---|
| checkupdate | 0x1be4bc8 | services | 0x1bf2e88 |
| servicelist | 0x1be4d98 | create_role_count | 0x1bf2f10 |
| rolelist | 0x1be4db8 | forserverinfo | 0x1bf2d10 |
| inituser | 0x1be4df0 | username | 0x1be6d40 |
| action | 0x1be4e00 | roleid | 0x1be51d8 |

紧挨着的还有一整片协议命令名：`mysteryshop / mysteryshop2 / buypurview / buygoods /
order / shop / servicelist / rolelist / inituser / action / …` —— 这就是客户端的
action 字符串池。

**生成完全解密、且 ELF 头保持完整的镜像**（Ghidra 能直接吃）：

```python
d = bytearray(open('v11/libgame.so','rb').read())
for i in range(0x5e4d30, 0x1d01b28):   # .text 起 .. .rodata 末
    d[i] ^= 0xB5
open('v11/native/libgame_plain.so','wb').write(d)
```

**为什么之前搜不到任何引用**：`adr` / `add rX, pc, #imm` 这类 **PC 相对寻址**不产生
4 字节字面量，所以「搜字面量」必然为 0。要用反汇编器算目标地址（`v11/_pcref.py` 就是干这个的），
或者直接把解密后的镜像丢给 Ghidra 让它的分析器建 xref。

→ 本轮末尾已经启动两件事（下一轮取结果）：
1. Ghidra 全量分析 `libgame_plain.so`（项目 `<path>
2. `v11/_pcref.py`：ARM/Thumb 双模式扫 PC 相对引用，直接找指向 `rolelist` /
   `create_role_count` / `username` 等字符串的指令地址。


### 第 13 轮：字符串表和 JSON key 表都挖出来了

有了 `libgame_plain.so`（`.text` + `.rodata` 都解密的镜像）之后：

1. **解出 15,276 个「指向 .rodata 字符串」的数据槽**（`v11/strptrs.txt`），
   也就是客户端所有的字符串指针表。典型的有：

   - `.data` **0x1f7a070** 起 1434 项：`LOGIN_DATA / LOGIN_RESULT / YJ_EVENT_* / …`（事件名表）
   - `.data` 0x1f7bd2c 起 224 项：界面贴图路径
   - `.data.rel.ro.local` 0x1d04550 起 192 项：页签贴图
   - 以及前面看到的 action 名表（`roletop / rolesigninfo / servicelist / rolelist / inituser` …）

2. **定位到 11.4.0 真正的 JSON key 表**（`.data`）：

   | key | .data 槽 | key | .data 槽 |
   |---|---|---|---|
   | rolelist | **0x1f7b6cc** | roleid | **0x1f7be38** |
   | rid | 0x1f7be30 | username | **0x1f7c010** |
   | levelid | 0x1f7bf90 | host / port | 0x1f7b9ac / 0x1f7b9b0 |

3. **这些槽的地址在 `.got` 里**（rolelist→0x1f75434、rid→0x1f76474、
   roleid→0x1f7645c、username→0x1f7647c、levelid→0x1f76484），
   说明代码是 **GOT 相对寻址**读取的：
   `.got` 基址 0x1f74f90 → 位移 rolelist=0x4a4、rid=0x14e4、roleid=0x14cc、
   username=0x14ec、levelid=0x14f4。

4. 扫 `.text`（Thumb 1026 万条 + ARM 543 万条指令）找这些位移的 `ldr`，得到一批候选
   （例如 `0x16f0104 / 0x16f9f9c / 0x1741890 / 0x1766824 …` 都带 rolelist 的 0x4a4 位移）。
   但 0x4a4 这种小位移容易撞车，需要用「同函数里是否同时出现多个 key 位移」来筛。

5. **Ghidra 全量分析已经启动**（项目 `<path> `libgame_plain.so`），
   跑完之后直接对 `.got` 槽（0x1f7645c 等）做 xref，就能拿到解析函数，不用再手扫。

脚本：`v11/_strptrs.py`（解字符串表）、`v11/_gotrefs.py`（GOT 位移扫描）、
产物：`v11/strptrs.txt`、`v11/native/libgame_plain.so`。


### 第 14 轮：拿到 PIC/GOT 的寻址范式，并定位到 rolelist 的读取点

1. Ghidra 全量分析**成功**（项目 `<path> `libgame_plain.so`）。
   但大函数（如 FUN_008ab4a8，对 key 区有 2272 处引用）**decompile 超时/失败**，
   得靠反汇编或切片。

2. `.text` 里**没有**任何指向 `.got` 的字面量 —— 因为 ARM PIC 是这么做的：

   ```
   0x13de9c8: ldr  r3, [pc, #0x44]     ; r3 = got-relative offset
   0x13de9d4: add  r3, pc, r3          ; r3 = GOT base
   ...
   0x13de9e0: ldr  ip, [pc, #0x30]     ; disp 也在字面量里
   0x13de9ec: ldr  r1, [r3, ip]        ; <-- 真正读 GOT 槽
   ```

3. **定位到 rolelist 的读取点**：`.text **0x13deab4**`（文件 VA，GOT base 0x1f79a34，
   位移 0x4a4 → 槽 0x1f75434），所在函数 `FUN_014a4564`。
   它太大，Ghidra decompile 失败 → 下一步用反汇编跟 `bl` 目标（找 JSON 取值函数），
   或者把函数切片后再 decompile。

4. 已确认的 key ↔ 槽 ↔ GOT 位移对照（写进脚本就能复用）：

   | key | .data 槽 | GOT 位移 |
   |---|---|---|
   | rolelist | 0x1f7b6cc | 0x4a4 |
   | rid | 0x1f7be30 | 0x14e4 |
   | roleid | 0x1f7be38 | 0x14cc |
   | username | 0x1f7c010 | 0x14ec |
   | levelid | 0x1f7bf90 | 0x14f4 |

脚本：`v11/_strptrs.py`、`v11/_picgot.py`、`v11/_picgot2.py`、`v11/_picgot3.py`；
Ghidra 辅助：`v11/ghidra/Dig4.java`（xref 查询）、`Dig5.java`（按引用数排函数）。


### 第 15 轮：角色对象的数字字段**全部要字符串**——`roleid` 通了

继续用 savedata 当判据，把角色对象的字段类型挨个试：

| 角色对象 | savedata.rid | savedata.**roleid** | 说明 |
|---|---|---|---|
| `{"rid":"1001","roleid":1,...}` | "1001" | **0** | roleid 是数字 → 丢 |
| `{"rid":"1001","roleid":"1","rolelv":"1","cname":"Wukong","uniqueid":"1001","username":"Wukong"}` | "1001" | **1** ✅ | **数字字段全用字符串就对了** |

所以 11.4.0 的 `YJSimpleRoleInfo` 风格是**「数字也用字符串」**：
`rid` / `roleid` / `rolelv` / `uniqueid` 都要写成 `"1"` 这种。

`uniqueid` 在 savedata 里仍是 INT_MIN、`cname` 干脆不出现 —— 说明这两个不是从登录应答的角色对象里读的，
是进入游戏/加载角色数据那一步才填。

### 顺带挖出来的 key 表（.data，全部明文可查）

`v11/strptrs.txt` 里 0x1f7b960 起就是**存档/角色字段表**：

```
savedata / roledata / player / role / bag / weaponid / wingid / suitid /
rideid / nweaponid / pigmentid / fcpigmentid / dazzleid / equips / pet / level /
cur_role_id / forbidchat / pkservice / host / port / point / prayequips /
true_spirit_bag / firstpass / title / hiremark / red_rain_info / lvdata / spotdata /
kSaveKeyDataFromServers / version / JsonDatas / kSaveKeyDeleteDataFromServers
```

再往后 0x1f7c280 起是一整片**属性缩写表**（`A2 A4 A5 A9 AQ AT AV AW B7 BG BH BI BJ …`，
和 v800 掉落物里那套 `A2/A4/A5` 完全对得上）。


### 第 16 轮：堆里没有解析后的角色结构；但 `.dynsym` 里有登录 UI 的类名

1. **堆检查**：登录完成后 dump 了两个最大堆区（`v11/h1.bin` 33MB、`v11/h2.bin` 31MB），
   能搜到我们发的原始应答 JSON 和客户端自己写的 savedata，但**没有**解析后的角色结构体
   （搜 `Wukong`/`1001` 只命中文本，不命中结构）。说明角色数据被拆成了很小的对象，
   或者根本没被角色列表用上。

2. **直接伪造 `savedata.dat` 也不行**：手工构造了一份带 `rid/roleid/cname/uniqueid` 的存档，
   push 进去重启 —— 客户端在登录后又把它**覆盖**回自己的版本（`uniqueid` 仍是 INT_MIN、
   `cname` 消失）。所以 `cname`/`uniqueid` 是「进入角色」之后才写的，不是登录阶段。

3. **重大线索：`.dynsym` 里有部分 C++ 符号名**（18001 条里挑出的）：

   ```
   0xe5faf8  Java_..._native3533LoginFinished
   0xe6d060  Java_..._nativeHummingbirdLoginFinished
   0xf572e8  Java_..._native4399LoginFinished
   0x1699548 ..._Base_managerISt5_BindIFSt7_Mem_fnIM22YJLoginCreateRoleLayerFviEEPS3_...
   0x169c5e8 ..._Mem_fnIM28YJLoginSelectCreateRoleLayerFviEE...
   0x16b233c ..._Mem_fnIM12YJLoginSceneFvN14YJLoginManager22LOGIN_OBSERVATIO...
   ```

   → 登录界面类名确认：**`YJLoginScene` / `YJLoginManager`（带 LOGIN_OBSERVATION 枚举）/
   `YJLoginCreateRoleLayer` / `YJLoginSelectCreateRoleLayer`**。
   这几个名字是**下一步的抓手**：在 Ghidra 里按这些字符串/符号找类的方法表，
   就能定位「角色列表怎么填、`+` 按钮什么时候出现」。


### 第 17 轮：导出符号表 + JsonCpp + 三个 JNI 登录回调

1. **导出整张符号表**：`v11/dynsym.txt`（**18,000 条**，`0x地址<TAB>符号名`）。
   虽然大部分是 `std::function` 的模板实例，但能看出引擎结构：
   `YJAI / YJCollision / BehaviorTree / YJBTN* / AudioPlayer / **JsonCpp**`
   —— **客户端用的 JSON 库是 JsonCpp**（`JsonCpp::Value`），
   这意味着 key 是 `std::string`，`json["rolelist"]` 一定从某个 `const char*` 来。

2. **三个 JNI 登录回调的地址**（都是从 Java 侧进来的入口）：

   | 函数 | 地址（文件 VA） |
   |---|---|
   | `Java_..._native3533LoginFinished` | 0xe5faf8 |
   | `Java_..._nativeHummingbirdLoginFinished` | 0xe6d060 |
   | `Java_..._native4399LoginFinished` | 0xf572e8 |

   反编译这三处得到的是**不完整**的代码（调用走 vtable + Ghidra 在这片没切准函数边界），
   例如 `native3533LoginFinished` 只看到 `FUN_0079a5f4(); operator_new(0x618);`。
   → 下一步要用「从入口往前/往后扩边界」的方式重切函数，或者按 vtable 偏移追。

3. 登录界面类名（上一轮那条的补充）：`YJLoginScene`、`YJLoginManager`（带
   `LOGIN_OBSERVATION` 枚举）、`YJLoginCreateRoleLayer`、`YJLoginSelectCreateRoleLayer`。


### 第 18 轮：发现一处**地址换算必须修正**的坑（+0x10000）

追 `nativeHummingbirdLoginFinished`（文件 VA 0xe6d060）时看到：

```
0xe6d060  push {r3,lr}
0xe6d064  bl   #0x11c2bc4          ; PLT 桩
0xe6d068  pop  {r3,lr}
0xe6d06c  ldr  r1,[pc,#4]          ; r1 = 偏移
0xe6d070  add  r1, pc, r1          ; r1 = 0x1bf2c74
0xe6d074  b    #0x11c35d4          ; 另一个 PLT 桩
```

算出来的 `r1 = 0x1bf2c74`，但**文件的 0x1bf2c74 处是空的**；
减掉 **0x10000** 之后（0x1be2c74）才落到 `.rodata` 的字符串中间（"...eskills"）。

→ 结论：**代码里的 VA 与文件偏移在 `.rodata` 上差 0x10000**
（.data 区看起来差 0x1000）。之前 `v11/strptrs.txt` 里记的「槽值」是按文件偏移来的，
所以**所有「槽值 vs 代码立即数」的比对都要先做这个换算**，否则永远是 0 命中。
这也解释了为什么 `_picgot*.py`、`Dig4/Dig5` 的结果彼此对不上。

**下一步第一件事**：用运行时内存（`/proc/pid/mem` 里 libgame 的映射）把
「文件偏移 → 运行时 VA」的真实映射表量出来（读某个已知字符串的实际地址即可），
再把这套换算写进所有分析脚本。这件事不做，后面的 xref 都是错的。

### 另外：手工伪造 `savedata.dat` 无效（已验证两次）

put 进去带 `rid/roleid/cname/uniqueid` 的存档，客户端登录后必定覆盖回自己的版本
（`uniqueid` 恒为 INT_MIN、`cname` 消失）→ 说明这两个字段是**进入角色之后**才写的，
不能靠预置存档绕过。


### 第 19 轮：地址换算是恒等映射；崩溃探针证明 key 指针在加载期就被缓存

1. **修正上一轮的误判**：用运行时 dump 直接量了一次——
   `rolelist` 在 `.rodata` 的**运行时地址 = 0x4cfcdb8**，减去库基址 `0x3118000`
   = `0x1be4db8` = **文件偏移**。→ **链接期 VA == 文件偏移（恒等）**，
   上一轮那个「+0x10000」是我手算 PC 值算错了。`v11/strptrs.txt` 里的数值是对的。

2. **崩溃探针**（新手段，值得记下来）：
   `.data` 槽 0x1f7b6cc（保存 `"rolelist"` 指针）的运行时地址 = 0x50946cc
   （`.data` 映射 0x5092000 ↔ 文件 0x1f79000）。在登录应答到达前把该槽改写成
   `0x00000001`（已回读确认写入成功），然后触发登录 —— **客户端没有崩**。

   → 说明这段代码在**启动时就把 key 指针缓存到别处**（比如 GOT 之外的副本/成员变量），
   解析时不再读这个槽。所以「运行时改指针」这条路对 key 不奏效；
   要证明引用关系得**改 APK 里的字符串本体**（重打包）或改 `.text`。

3. 目前仍然没解决的仍是那一个：**角色列表怎么才能被渲染出来**。


### 第 20 轮：`roleids` = 职业 id（服务器面板头像终于对了）

用户提供了两张截图（服务器面板 / 主界面），据此定位到：

1. **服务器面板里的小人头 = 角色职业图标**，来源是 `services[].roleids`：

   | roleids | 面板表现 |
   |---|---|
   | `[1001]` | 部分服有头像、部分**黑三角**（1001 不在 `role.sdb`） |
   | **`[1]`** | **5 个服各一个孙悟空头像，无黑三角、无重复** ✅ |

   → `roleids` 装的是**职业 id**（1=孙悟空 2=唐僧 3=猪八戒 4=沙僧），不是 rid。

2. **11.4.0 的角色表**（`role.sdb` 解密得到，见 `v11/rolelist.txt`）：
   `1-4` 四主角；`31-34` 邪版；`51-53` 影子/巫毒娃娃；`101-118` 十八尊者。

3. 选服界面会**每 ~5 秒轮询一次** `checkupdate → login(-1) → servicelist(1) → login(1,forserverinfo)`，
   这是它的正常刷新，不是死循环。

4. 两个**会破坏现状**的改动（别再加了）：
   - 给 `services[]` 加 `ip/host/port/pkservice` → 服务器名变回 `INVALID_STR`；
   - 给 login 应答加**顶层** `service/servicename/serverid` → 直接 SIGSEGV。

5. 仍未解决：**主界面莲花台不显示角色**（面板已经知道每个服有孙悟空）。
   说明面板只用 `roleids`，而莲花台要的是「当前角色的数据」——这块还没找到正确的键/形状。

已知良好基线（当前 `responses.json`）：`code + servicetime + base + services(roleids:[1]) +
create_role_count + rolelist{svc:[role(rid/roleid/rolelv 全是字符串)]}`。


### 第 21 轮：RTTI 摸到 `YJLoginManager` 的 typeinfo

1. 类名在二进制里只以**模板实例的 mangled 名**形式出现（`Maybe_get_result_typeILb1ESt7_Mem_fnIM12YJLoginScene...`），
   所以之前搜 `YJLoginScene` 的指针永远 0 命中。

2. 用 **RTTI 名字串**（`12YJLoginScene` / `14YJLoginManager` / `22YJLoginCreateRoleLayer` /
   `28YJLoginSelectCreateRoleLayer`）去搜指针：

   | 名字串 | 文件偏移 | 指针引用 |
   |---|---|---|
   | `14YJLoginManager` | 0x1c68c08 | **1 处：0x1d1fe34** ✅ |
   | 其他三个 | 0x1c90xxx | 0（0x90xxx 那几处是 .dynstr 里的符号名） |

   → **`YJLoginManager` 的 typeinfo 在 0x1d1fe30**（name 字段在 +4）。

3. 但 `vtable[-1]` 指向 typeinfo 的那个槽在文件里是 0（由 REL 重定位在加载时填），
   所以「typeinfo → vtable」这一步静态拿不到，需要：
   - 读 `.rel.dyn` 里 `r_offset == vtable-4` 的重定位项，或者
   - 运行时 dump `.data.rel.ro` 段（那里已经被重定位过）再取 `*(vtable-4)`。

**下一步**：运行时 dump `.data.rel.ro`（映射 `0x4e1b000-0x5092000` 或 `0x4bbc000-`），
在 dump 里搜 `0x4e1b000+...` 空间中值等于 `typeinfo 运行时地址` 的槽位（= vtable-4），
拿到 vtable 后就能列出 `YJLoginManager` 的全部虚函数并逐个 decompile。


### 第 22 轮（目标：修好「不显示角色」）——本轮穷举了角色对象的各种形状，仍未画出

本轮把「角色不显示」当成唯一目标，试过的所有变体（都在 `roleids` 已经能画出服务器面板头像的前提下）：

| 变量 | 结果 |
|---|---|
| `rolelist` = `{svc:[role]}`，role 全字符串（rid/roleid/rolelv/service/exp/vip/wingid/suitid/weaponid/level/state） | 不崩，莲花台空 |
| `rolelist` = `{svc:{rid:role}}`（对象套 rid） | 不崩，空 |
| 顶层 `cur_role_id` / `lastrid` | 不崩，空 |
| `lastdata` = `{rid:{}}` | 不崩，空 |
| `roledata`/`lastdata` 用**字符串**（JSON 文本） | **崩** |
| `roledata` = `{rid: <客户端自己的 savedata 对象>}` | 不崩，空 |
| `roleid`/`rolelv` 用数字 | **崩** |

结论：**莲花台的模型不是被 `rolelist` 驱动的**（面板头像是，莲花台不是）。
它要的很可能是「当前角色的存档数据」（`savedata`/`roledata` 那一坨 player/role/level/bag/equips…），
而这份数据的字段表我们已经有了（`.data` 0x1f7b960 起：`savedata/roledata/player/role/bag/
weaponid/wingid/suitid/rideid/nweaponid/pigmentid/fcpigmentid/dazzleid/equips/pet/level/
cur_role_id/…`），但**每层的具体结构还没逆出来**。

→ 下一步只能是逆 `roledata` 的反序列化：用 `.data` 里 `roledata`(0x1f7b964) /
`savedata`(0x1f7b960) 这两个 key 槽，配合 Ghidra 对引用它们的函数做切片反编译
（大函数 decompile 超时的，按区间切片）。


### 第 23 轮（目标：修好「不显示角色」）——找到客户端自己的重试缓存，挖出「进入游戏服」请求

**这是本轮最有价值的发现**：登录失败时，客户端会把待发请求写进 `savedata.dat` 的 `cache` 字段：

```json
"cache": {
  "requesttag": "REQUEST_LOGIN_GAME_SERVER",
  "requesturl": "http<path>",
  "requestdata": "-=4399=-xXvqch...",      // 就是这套 DES 加密的请求体
  "requesttype": 1,
  "requestheaders": ["Content-Type:application/json"],
  "requestdatasize": 584
}
```

用 `codec.decode` 一解就得到**客户端想发的下一条请求**（已存 `v11/login_game_server.json`）：

```json
{"md5":"0f5427d1...","pid":"5","safe":"3d039681...","uuid":"4364D7...",
 "cname":"Wukong","action":"login","channel":108,"service":1,
 "version":"s11.4.0","forserverinfo":1,"dataversion":"11.4.0", ...}
```

→ **`REQUEST_LOGIN_GAME_SERVER` = 带 `cname` 的 `login`（同时 `forserverinfo:1`）**。
也就是说：**客户端确实已经选好了角色（cname=Wukong）并主动发起「进入游戏服」**，
我们只差这条应答的内容。

**新工具**：让某个请求得不到满意应答（返回 `code:1001`），客户端就会把请求缓存进 savedata，
我们读出来即可 —— **这就是一个「协议探测通道」**，不用再猜客户端要发什么。

本轮对 `*enterserver*` 的应答尝试：

| enterserver 应答 | 结果 |
|---|---|
| `{code:1000, base:{...}}` | 不崩，但不连大厅、界面不变 |
| `+ state/result + host/port 字符串` | 同上 |
| `+ services/rolelist/cur_role_id/lastrid` | 同上 |
| `+ savedata(扁平对象)` | **崩** |

服务端是**有响应**的（客户端一路上都在推进：更新检查 → 登录 → 选服 → 发「进入游戏服」请求），
差的是这条应答的字段。

### 下一轮的做法

1. 用「重试缓存」这套通道，把 `enterserver` 应答故意做成 `code:1001` → 看客户端下一次缓存什么
   （如果它缓存别的 `REQUEST_*`，就能反推出它期待的状态机）；
2. 在 `v11/mitsrv4.py` 里已经加了路由：`login + forserverinfo + cname` → action=`enterserver`，
   `login + forserverinfo` → `serverinfo`，两条可以分别定制应答。


### 第 24 轮：乐固会校验 libgame 的完整性 —— 文件级改 libgame 会被静默杀掉

想用「把 `.data` 里 `rolelist` 指针改成 1」的**崩溃探针**（在 APK 里改，这样客户端启动时缓存的就是坏指针）：
定位到该槽的文件偏移 = `0x1f7a6cc`（`.data` 段 addr 0x1f7a000 / off 0x1f79000，
值 `0x1be4db8` = "rolelist" 字符串；注意 **`.data` 不加密**，只有 `.text`/`.rodata` 是 XOR 0xB5）。

流程走通了：`repack.py --asset lib/armeabi/libgame.so=...` → zipalign → apksigner（keystore
`build/your-keystore.jks`，pass `<your-pass>`）→ 装机。结果：

```
V/libnb: enter native_bridge3_loadLibraryExt .../libgame.so
I/ActivityManager: Process com.zmxyol.union.uc (pid 24739) has died: fore TOP
```

**进程被静默杀掉**（没有 SIGSEGV、没有 tombstone），发生在库加载之后不久。
→ **乐固（libtprt）对 libgame 做了完整性校验**，改 4 个字节就会被杀。

**影响**：原包（乐固生效）里**不能**用「改 APK 里 libgame」的方式做任何补丁/探针；
这类实验只能在去乐固包里做（那边又有 exit(15) 的问题）。这也再次解释了为什么当初要走去乐固路线。

（另：改了 libgame 的包安装后 emulator 又卡死过一次，`ldconsole reboot --index 0` 恢复正常；
现已装回原版 11.4.0。）


### 第 25 轮：servicelist 用数组会让界面整个消失（状态机变了，但又复位）

试了 \*servicelist*\ 的 olelist\ 用**扁平数组**（而不是按服 id 的对象）：

- 现象：**选服界面的 UI 全部消失**，只剩背景加载图（说明状态机真的往前走了一步）；
- 但随后客户端又从 \checkupdate\ 重新开始（\service:-1\），UI 恢复 —— 说明它等不到需要的应答又退回来了。

组合尝试（数组 servicelist + 带 role/savedata 的 enterserver）→ 界面恢复成选服，仍无角色、不连大厅。

另外注意到右上角 **「用户昵称:」始终是空的**：客户端会调 \yjHummingbirdSDKgetUserName_\，
而 SDK 返回的用户名是空（SDK 登录给的 uid 也是空）。这可能也是角色展示被挡的原因之一。


### 第 26 轮：从 v800 源码挖到两条**确定的协议结构**

翻 v800 的 `LocalServer.java` 注释，发现两个我们一直写错的点：

1. **`pkservice` 必须是 `{host, port}` 对象**（不是数字）：

```java
// analyzeAndApplyDataToGame also pushes this to
// YJSocketConnectToGameLobby::setLobbyAddress when present.
JSONObject pk = new JSONObject();
pk.put("host", "127.0.0.1");
pk.put("port", PORT);
r.put("pkservice", pk);
```

我们此前一直是 `"pkservice":1`。**这就是「大厅地址」的来源**。

2. **存档数据（savedata）有一组硬校验**，任一条不过就整包拒收（表现为「存档数据已损坏！」）：

```
YJPlayer::isValidPoint(savedata)   -> isMember("point")
YJPlayer::isValidData(player)      -> isMember("roleid")
YJBagManager::isValidData(bag)     -> bagsize + maxglobalitemid + spiritcount
YJLevelManager::isValidData(level) -> isMember("psl") && psl.isArray()
YJPetManager::isValidData(pet)     -> isArray()（空数组会失败）
```

上传形状：`{"roledata":{"player":{...,"roleid":3},"role":{"rolelv":2,...}}, "bag":{...}, "level":{...}, "pet":[...]}`

本轮已按这个形状构造 `savedata`（point/roledata.player.roleid/bag 三件套/level.psl 数组/pet 非空数组）
并配 `pkservice` 对象一起发；**不崩、但大厅仍未连接、角色仍未出现**。

→ 下一步：把 `savedata` 放进 **enterserver** 应答（而不是 login），并按 v800 的门槛逐项对齐；
另外 v800 里 `baseBlock()` 是 `{host, port, service, pkservice}`，`pkservice` 在里面也是对象。


### 第 27 轮：把 v800 的 savedata 校验门槛搬进 enterserver（仍未画出来）

本轮做了三件事：

1. 按 v800 的 uildSavedataBlock() 精确构造 savedata：
   \   point: 0
   roledata: {player:{rid,roleid,username}, role:{rid,roleid,rolelv,roleexp,username}}
   bag: {bagsize:72, maxglobalitemid:4400, spiritcount:0, item:[]}
   level: {psl:[], phfiel:0, mlru:[]}
   pet: [ {id:1} ]        // 非空数组
   \   放进 **enterserver** 应答 + pkservice:{host,port}。结果：不崩、但不连大厅、角色仍不出现。

2. 发现「进入游戏服」请求只在客户端**有已选角色名**时才发：20:04 那次之所以抓到
   \login+cname+forserverinfo\，是因为客户端在**重放缓存里的 REQUEST_LOGIN_GAME_SERVER**。

3. 想人为制造这个缓存（先让 login 失败）——本轮再试却**没有生成 cache**（cache: null），
   说明缓存只在特定失败状态下才写。

**下一步**：把「让客户端发出 enterserver 请求」变成可控的——例如先让它有一次成功的登录
（写出 cname）、再让下一次 login 失败触发缓存、最后重放那次请求并给出带 savedata 的应答。


### 第 28 轮：点遍莲花台也不会触发进服请求 —— 说明「角色没被画出来」才是根因

- 在莲花台/角色展示区做了一轮**点击扫描**（6 个点，覆盖莲花、中央按钮、两侧），
  请求日志里**没有**新的 \login+cname\（enterserver）请求 → 客户端确实没有可点的角色。
- 又试了 \create_role_count: []\（数组）+ oleids: []\ + olelist: {}\：
  服务器名变回 \INVALID_STR\，莲花台仍空，**没有出现「+」建角色入口**。

**结论**：11.4.0 的登录场景**只根据「本地已创建的角色」来画人和开入口**，
服务端下发的 olelist\ 只影响服务器面板头像（oleids\），不参与主界面渲染。
要让角色出现，必须让客户端认为「这个号上有一个自己创建过的角色」——
要么走它自己的 \createrole\ 流程，要么让 \savedata\ 里那个角色通过它的
\YJPlayer::isValidPoint / isValidData\ 一整套校验并被接受为「当前角色」。

**当前受阻**：原包里改 libgame 会被乐固静默杀掉（第 24 轮已验证），
所以「改文件做崩溃探针找渲染代码」这条路在原包上不通。


### 第 29 轮：原包路线在「显示角色」这个点上已穷举完

本轮又试了：

| 尝试 | 结果 |
|---|---|
| v800 \simpleRole()\ 的**原样数字**形状（rid/roleid/rolelv 全是数字） | **崩**（11.4.0 明确要字符串） |
| 完全不发 olelist\/oleids\，只发 \create_role_count\ | 不崩，服务器名 INVALID_STR，**仍无「+」、无角色** |
| 角色对象加 \service\/\servicename\ | 服务器名反而变 INVALID_STR |
| 莲花台 6 点点击扫描 | 不产生任何「进入游戏服」请求 |

**归纳**：原包（乐固生效）这条线上，服务端能做的影响就到这里：

- oleids\ → 服务器面板头像 ✅
- \code/servicetime/base/services/create_role_count/pkservice/savedata\ → 能一路走到选服界面 ✅
- **主界面角色渲染 / 「+」建角色入口** → 无论怎么发都不出现

而且「改 libgame 做崩溃探针」在原包上被乐固完整性校验堵死（第 24 轮）。

**结论：要修「角色不显示」，必须换到去乐固路线**（无乐固校验，可随意改 libgame 做探针，
也能用 v800 的 FnForce 伪造 SDK 登录）。它唯一的历史障碍是 exit(15)，
但现在手上有 \libgame_plain.so\、18,000 条符号、\.data\ 字段表，比当初强得多。


### 第 30 轮：ARM 模式也扫过了 —— libgame 里没有裸 exit svc

之前扫 `svc` 只用了 Thumb 模式（libgame 是 ARM/Thumb 混合），本轮补扫 **ARM 模式全 `.text`**
（543 万条指令）：`svc` 命中 11 处，**全部是数据被误当成指令**（立即数是
`0xcdab89` / `0x3733c6` 这种魔数，前后指令也是乱码）。

→ **libgame 里（Thumb + ARM 都扫过）不存在裸 `svc` 形式的 exit/exit_group**。

这条对「原包 exit(15)」和「去乐固 exit(15)」都成立：那个 15 不是 libgame 发的。


### 第 31 轮（切去乐固）：exit(15) 的精确时序 + libtprt 自校验实证

**1. 时序（关键）**

```
20:38:19.552  libnb: loadLibraryExt libtprt.so
20:38:19.687  YJOffline: tersafe2 stub loaded, signal handlers installed   ← 我们的 stub 已就位
20:38:19.688  YJOffline: tp2 TP2Sdk.init(19006) -> 0
20:38:20.428~20:38:20.885  URL patch 全部完成
20:38:20.946  libnb: native_bridge2_getTrampoline ...Cocos2dxRenderer_nativeInit
20:38:20.992  Zygote: Process exited cleanly (15)      ← 46 ms 后
```

→ 死亡点就在拿到 `nativeInit` trampoline 之后、nativeInit 内部。

**2. libtprt 的全部裸 svc（Thumb+ARM 全扫）**

```
0x2bd4 code 0x10(16)   0x2eb8 0x16(22)   0x331e r0(调用者传)   0x4522 r0(调用者传)
0x5414 0x1b(27)        0x738e ?          0x85b0 0x1a(26)        0x8988 7
0xdf8a 7               0xcdf8 0x15(21)   0x11d74 0x1c(28)
（ARM 那条是 set_tls，无关）
```

**里面没有 code 15**。只有 0x331e / 0x4522 两个是「r0 由调用者给」，15 只可能从这两个来。

**3. 实证：改 libtprt 会被它自己的完整性校验杀掉**

把 0x331e / 0x4522 前的 `push {r7}` 改成 `movs r0,#0x99`（这样退出码会变成 0x99，
就能判断是不是这两个点），重新打包运行：

```
Process 11451 exited cleanly (25)
```

**exit 变成 25，而且死得更早**（Boot.start 之后立刻死）→ 这就是 libtprt 的**自校验**在报错。
也就是说：**libtprt 也不能改**（和原包改 libgame 一个道理）。

**4. 由此确定的可行边界**

| 构建 | libgame | libtprt | 结果 |
|---|---|---|---|
| 原包（乐固生效） | 不能改（被杀） | 不能改（被杀） | 只能改服务端应答 |
| 去乐固 | **可以改** | 不能改（25） | 死因 exit(15) 仍未定位 |

→ 下一步只能在**去乐固包里改 libgame**：把 `nativeInit` 里可疑的调用点逐个 patch 掉
（比如把某个 call 换成 nop / 换成 return），看 exit(15) 什么时候消失。
这是唯一还能自由改的模块。


### 第 32 轮：终于能在运行时改到 libgame 的代码了 —— 但乐固连内存代码也查

**做到了什么**：

1. 确认 libgame 的 `.text` 在运行时是**第二段 rw-p 映射**（file offset 0x5e4000 起），
   而不是第一段 r--p；按 `/proc/self/maps` 里「覆盖该文件偏移 + 可写」的那一段去算地址，
   才能拿到真正的运行时地址（例：`0x3772844`）。
2. 写了个运行时打补丁的替身 `v11/native/tersafe2_patchinit.c`：轮询 maps，
   找到 libgame 的可写映射后把 `Cocos2dxRenderer_nativeInit` 改成 `bx lr`。
   **验证成功**——补丁前后字节是 `f8402de9`（真正的 ARM 序言）→ `1eff2fe1`。

**结果**：

```
maps: 3700000-4bc0000 rw-p off=5e4000 -> nativeInit @0x3772844
patched nativeInit @0x3772844: f8402de9 -> bx lr
Process ... exited cleanly (25)          ← 补丁后 ~100ms
```

**exit 从 15 变成 25** —— 25 正是 libtprt 自校验失败的码（第 31 轮实证过）。

→ **结论：libtprt 不只校验 libgame 的文件，也校验它在内存里的代码**。
改文件 → 加载期 exit(25)；改内存 → 运行期 exit(25)。

### 这就形成了一个闭环，当前无解

| 想改 | 障碍 |
|---|---|
| libtprt（关掉它的校验） | libtprt 校验自己 → exit(25) |
| libgame 文件 | libtprt 校验加载期 → exit(25) |
| libgame 内存 | libtprt 校验运行期 → exit(25) |
| classes.dex / manifest | 去乐固包能改，但那就绕开了乐固 → nativeInit 里 exit(15) |

**唯一剩下的方向**：找出 libtprt 的校验函数并让它失效——但它自校验，改不了它自己的代码。
可行变通：**在内存里改 libtprt**（它的代码段在运行时是否可写？需要实测），
或者用 ptrace/调试器在运行时改（反调试会杀）。


### 第 33 轮：内存里改 libtprt 的 exit svc 也没用 —— exit(25) 不走那 11 个 svc

**实测结果**：

1. libtprt 的代码段在运行时是 **`r--p`**（只读），数据段才是 `rw-p`。
2. 我们在进程内 `mprotect(0xb81e000, 0x17000, RWX)` **成功**（返回 0），
   然后把 11 个 `svc #0`（`df00`）全部改成 `0000`（no-op）——**全部生效**。
3. 同时把 libgame 的 `nativeInit`（真正的序言 `f8402de9`）改成 `bx lr`。

结果：

```
tprt svc 0x2bd4: df00 -> 0000   （11 处全部）
patched nativeInit @0x3772844: f8402de9 -> bx lr
Process ... exited cleanly (25)      ← 仍然 25
```

**exit(25) 不是那 11 个裸 svc 发的**（它们的码里根本没有 0x19），
说明 libtprt 还有别的退出路径（很可能是走 libc 的 `exit()`，
或者另一处我们没扫到的 ARM/Thumb 代码）。

### 现状总结（这个目标的天花板）

| 手段 | 结果 |
|---|---|
| 服务端应答（原包路线） | 只能到「选服界面 + 服务器面板头像」，角色和「+」出不来 |
| 改 libgame 文件 | libtprt 加载期校验 → exit(25) |
| 改 libgame 内存 | 运行期被校验 → exit(25) |
| 改 libtprt 文件 | 自校验 → exit(25) |
| 改 libtprt 内存（svc 去毒） | 校验照旧 → exit(25)（不走那 11 个 svc） |

**要再往下，只剩「逆 libtprt 的完整性校验器并让它失效」这条路**：
它自校验、且 exit 路径不明（不是裸 svc、也不是我们 hook 到的 libc exit），
工作量已经是「完整逆向一个加固壳」的量级。


### 第 34 轮：v800 的完整登录形状找到了，角色也确实被解析了 —— 但莲花台仍空

**从 v800 源码挖到的完整 login 应答**（`fillLogin`）：

```json
base: {host, port, ...}
services: [{service, name, roleids:[...], isnew, createtime, starttime}]
rolelist: {"1": [simpleRole...], ..., "5": [...]}     // 每个服都放全部角色
lastdata: {service:1, rid:<rid>, username:"玩家"}      // ★ 关键，必须非空
lastrid: <rid>
username: "玩家"
create_role_count: {"1".."5": 1}
uid: 1, userid: 1, serviceid: 1, bind: 1
```

源码注释原话：

> (rolelist non-empty + empty lastdata makes the client dereference a null JsonCpp value;
>  rolelist empty + non-empty lastdata shows the "登录记录数据错误" banner.)

**而且注释里还有客户端解析 rolelist 的伪代码**（`YJGlobalManager::setRoleListJson`）：
遍历对象的 key（atoi 成 service id）→ `getServerInfoByServerId` 找到服务器 →
`clearRoleList()` → 对数组里每个元素 `addRole(YJSimpleRoleInfo(r))`；
**数组直接被忽略**（这就是为什么之前放数组莲花台是空的）。
`YJSimpleRoleInfo` 需要 rid/username/rolelv/roleid/wingid/weaponid/suitid。

**本轮实测结果**：

- 按这个形状发（rid "1001"、lastdata、lastrid…）：**不崩**，`更换服务器`面板里
  **每个服都正确显示了孙悟空头像** → **说明角色对象确实被客户端解析了**！
- 但面板里同时出现了**两组服务器**：第一组头像正常，第二组全是**黑三角**。
  黑三角 = 角色解析失败的占位（因为 `servicelist` 每轮都重复下发，客户端把服务器列表累加了，
  第二组挂的 rolelist 已经被 `clearRoleList()` 清掉）。
- **莲花台（主界面角色）依然空** —— 即使角色已被解析。

**结论**：11.4.0 的主界面莲花台**不是**由 `rolelist` 里的 `YJSimpleRoleInfo` 画的
（8.0 是），这是 11.4.0 与 8.0 的关键差异。它八成要的是「当前角色的完整存档」
（`savedata`/`roledata` 那套 player/role/bag/level），而我们的 `savedata` 一直没通过它的校验
（存档里 `cname` 始终为空、`uniqueid` 始终 INT_MIN 就是证据）。


### 第 35 轮：login 应答里带 savedata 会把角色打回 INVALID_RID

按 v800 的 uildSavedataBlock 门槛构造了完整 savedata（point / cname /
roledata.player(roleid) / roledata.role / bag 三件套 / level.psl / pet 非空），
同时放进 login 和 enterserver 应答：

`
登录后存档: rid=INVALID_RID  service=-2147483648  cname="  uniqueid=INT_MIN
`

→ **带上 savedata 反而把已解析的角色打回无效**（service 变 INT_MIN）。
说明 11.4.0 在 login 阶段会用 savedata 覆盖角色状态，而我们的 savedata 结构没通过校验，
于是整块状态被清空。**login 应答里不能带 savedata**（enterserver 里可以再试）。

## 三、下一步（按可行性排序）

1. **A 路线（最快见到「进游戏」）**：用户用自己账号 + 实名登录一次；
   `v11/mitsrv3.py` 会把登录之后所有请求解成明文写进 `v11/requests.log`，
   然后按 `v11/responses.json`（已有 login/rolelist/servicelist/bag/victory 模板，
   来自 v800 的 `Canned.java`）逐条补应答。**热加载，改完不用重启服。**
2. **B 路线（真离线）**：把 `.data` 里 9 处 `ch_tprt_mem` 附近的登记表逆出来
   （找到「名字+期望哈希」结构 → 改期望值或让比较失败关闭），再回填那两张表。
   工具：`v11/patch_libgame_xor.py`、`<path> 工程、`v11/ghidra/Dig2.java`。
3. 无论哪条路，都要把 `v11/java/.../LocalServer` 的编解码换成 11.4.0 的
   （`v11/codec.py` 是参考实现），否则进程内本地服听不懂客户端。

## 四、环境怎么起（每次开机都要）

```powershell
# 模拟器
<path> launch --index 0     # 约 45 s
# 原包（当前就是原包装好的）
adb shell pm install -d /data/local/tmp/orig.apk

# 抓包/本地服（只劫持游戏服 4 个 IP，SDK 走真网）
adb shell su -c 'iptables -t nat -F OUTPUT; iptables -t mangle -F OUTPUT'
adb shell su -c 'mount --bind /data/local/tmp/cac /system/etc/security/cacerts'
python v11\redirect_game_only.py
adb reverse tcp:8443 tcp:8443
python v11\mitsrv3.py
# （install-ca 只需一次：v11/certs/c5d7d7c3.0 已在 /data/local/tmp/cac 里）
```

## 五、文件地图（只列关键）

| 路径 | 作用 |
|---|---|
| `v11/codec.py` | **11.4.0 编解码（DES-ECB + `-=4399=-`，key `1#(z1Rr>`）** |
| `v11/mitsrv3.py` `v11/responses.json` | 本地 HTTPS 服 + **热加载应答表** |
| `v11/redirect_game_only.py` `v11/mk_certs.py` `v11/certs/` | 只劫持游戏服的 iptables + 自签 CA/证书 |
| `v11/patch_libgame_xor.py` | libgame 解密/补丁/重加密（XOR 0xB5） |
| `v11/cycle.ps1` `v11/build.ps1` `v11/repack.py` | de-乐固包一键出包→装机→抓 logcat |
| `v11/native/` | TP2 替身系列（stub/trap/fill/exit_trap）、`libgame_rt.so`（明文 libgame）、scanmem 工具 |
| `v11/pfn.bin` `v11/ori.bin` | 从乐固生效进程 dump 的 pfn/ori 表 |
| `v11/requests.log` | 解出来的请求明文（登录之后会往里长） |
| 各轮交接 | `v11/交接_11.4.0_第2/3/4/5/6轮.md`（本轮序号从 2 开始） |
