# 乐固壳已攻破 —— `nativeInit` exit(15)/exit(25) 的根因与解法

最后更新：2026-11-06 23:05

## 0. 一句话

`libtprt.so` 不是「可以丢掉的壳」——它是 **libgame.so 运行时依赖的 libc 跳板表**。
用 **自写 shim 顶替 libtprt + 预解密 libgame**，并把 `g_tprt_pfn_array` 按真实语义填好，
**乐固就彻底消失，引擎正常启动到登录场景（莲花台）**。

## 1. 根因（之前一直没找到的那一环）

`libgame.so` 的未定义符号里有两条被漏掉了（早期用 DT_HASH 遍历，nchain 没覆盖全）：

```
g_tprt_pfn_array      (OBJECT, 116 byte = 29 个指针)
g_tprt_ori_array      (OBJECT, 16 byte  = 4 个指针)
```

`libgame.so` 在 `nativeInit` / 初始化期**通过这两张表调用 libc**（不是走自己的 PLT）。
把数组留空或全填 0，libgame 就判定「壳没初始化」，直接 `exit()`。

### 1.1 `g_tprt_pfn_array` 的真实语义（29 项）

从 `libtprt.so` `.data` 0x24d00 读初值，再顺着 Thumb 跳板（`bx pc` → ARM `ldr ip,[pc]; add pc,ip,pc`）
和 `.plt` 桩解到 `.rel.plt` 符号，得到：

| idx | 目标 | idx | 目标 |
|---|---|---|---|
| 3 | `fread` | 12 | `strlen` |
| 4 | `fwrite` | 13 | `strcmp` |
| 5 | `fopen` | 14 | `strstr` |
| 6 | `fclose` | 15 | `strchr` |
| 7 | `read` | 16 | `strrchr` |
| 8 | `write` | 17 | `time` |
| 9 | `close` | 18 | `gettimeofday` |
| 10 | `send` | 19 | `settimeofday` |
| 11 | `recv` | | |

`0 / 1 / 2 / 20..28` 是乐固自己的逻辑：

- `pfn[1]` @ libtprt `0x57a8` = **mprotect + 解密 + CRC 校验** 驱动器
  （页对齐 → `mprotect(…,7)` → 按 mode 3/4 逐字节异或 → `crc32` 比对 `[desc+0x1c]`；
  任一不符 **返回 0**）。libgame 拿到 0 就认为自己的代码损坏 → 退出。
- `pfn[20]` @ `0x7b80` = 一次性初始化（带 done 标志）。
- `pfn[5]` = `fopen` —— 之前 shim 返回 1，libgame 把 `FILE* = 1` 传给 `fseek`，
  崩在 `fault addr 0x31`（=1+0x30）。**这条崩溃就是「pfn 表填错」的铁证。**

### 1.2 为什么预解密可行

`libgame.so` 的加密只有 **XOR 0xB5**，而且范围必须精确：

```
.text   0x5e4d30 .. 0x1aa3bb4   XOR 0xB5
.rodata 0x1bdaf98 .. 0x1d01b28  XOR 0xB5
.ARM.extab/.ARM.exidx 0x1aa3bb4 .. 0x1bdaf98   **不加密，不能动**
```

老的 `native/libgame_plain.so` 把 `.ARM.extab/.exidx` 也异或了（2.4 MB 的异常表被毁）。
正确的镜像用 `v11/_mkplain2.py` 生成 → `native/libgame_plain2.so`。

因为已经预解密，`pfn[1]` 的「解密+CRC」必须**直接返回成功**，否则会把代码二次异或。

## 2. 解法（已实测跑通）

`native/tprt_shim.c` → `native/libtprt-shim.so`（8.5 KB）：

1. 导出 `JNI_OnLoad`、`g_tprt_pfn_array[29]`、`g_tprt_ori_array[4]`、
   `unwind_xx_tpshell_crcI/II/III`、`com.tencent.tpshell.TPShellApplication` 的全部 JNI natives；
2. `pfn[3..19]` 用 `dlsym(RTLD_DEFAULT, …)` 直接绑真实 libc 函数（ABI 完全一致）；
3. `pfn[1]` 及其它乐固自定义项：记日志并**返回 1（成功）**；
4. 构造函数打印 `tprt SHIM loaded`，并带 `dladdr` 探针，可随时看是谁在调用。

配套：

- `libgame.so` 用 `native/libgame_plain2.so`（预解密，或 `libgame_local.so` = 再把 API URL 静态改成
  `htt<path>
- `libtersafe2.so` 用 `native/libtersafe2.so`（原 TP2 stub）；
- 出包：`v11/cycle.ps1 -Tprt native\libtprt-shim.so -Game native\libgame_local.so`；
- **快速迭代**：不必重打包，直接换已安装包 lib 目录里的 .so（root）：
  `v11/swap.ps1 -Game … -Tprt … -Tersafe …`。

## 3. 实测结果

```
I/YJOffline: tprt SHIM loaded (libgame pre-decrypted; libc table bound)
I/YJOffline: pfn[1] called            ← 返回 1，通过
D/Cocos2dxActivity: model=…           ← Java 层起来了
V/libnb: getTrampoline Cocos2dxRenderer_nativeInit
D/HostConnection: HostRPC::connect sucess
D/EGL_emulation: eglCreateContext …   ← GL 线程起来
I/YJOffline: offline server listening on 127.0.0.1:8077
```

→ **登录场景（莲花台）完整渲染**（截图 `v11/shot_nolegu1.png`）。
乐固的加载期/运行期完整性校验**全部不存在了**：现在可以任意 patch libgame（文件或内存），
不会再出现 exit(15)/exit(25)。

## 4. 现在的卡点（已不是乐固）

### 4.0 离线登录链已经打通（实测）

修好三处后，**checkupdate → login → servicelist 全部跑通**，登录场景稳定、不崩：

| 问题 | 原因 | 修法 |
|---|---|---|
| 请求解不出来 | `Des.java` 还用 8.0 的 key `43((zmIp` | 换 11.4.0 的 `1#(z1Rr>` + 剥掉 `-=4399=-` 前缀 |
| 假登录不触发 | `FnForce` 反射 8.0 的 `org.yjmobile.lib.llL1ii`；11.4.0 里该类叫 `ll丨L1ii`（带 CJK 字符 U+4E28） | 改调 `YJMobileJNIHelper.hummingbirdLoginFinished()`（dex 原型 `()V`，内部转 `nativeHummingbirdLoginFinished` @ `0xe6d060`） |
| 服务器名 `INVALID_STR` | `services[].roleids` 必须是**数字**职业 id | 用 `roleids:[1]` |

右上角现在显示 **用户昵称: 离线玩家 / 版本号: 11.4.0 / 服务器: 单机服**（截图 `v11/shot_final_login.png`）。

**已知可用的 login 应答**（`v11/login.11.4.known_good.json`，也可放 `/sdcard/yj/login.json` 热加载）：

```json
{"code":1000,
 "base":{"host":"127.0.0.1","port":8078,"service":1,
         "pkservice":{"host":"127.0.0.1","port":8078}},
 "pkservice":{"host":"127.0.0.1","port":8078},
 "services":[{"service":1,"name":"单机服","roleids":[1],"isnew":0,
              "createtime":0,"starttime":0,"endtime":0,"state":1,"new":0}],
 "rolelist":{"1":[{"rid":"1001","username":"玩家","roleid":"1","rolelv":"1",
                   "level":"1","exp":0,"vip":0,"wingid":0,"weaponid":0,"suitid":0}]},
 "lastdata":{"service":1,"rid":"1001","username":"玩家"},
 "lastrid":"1001","username":"玩家","create_role_count":{"1":1}}
```

> `/sdcard/yj/<action>.json` 是按 **action 名**覆盖的，所以
> `login` / `login+forserverinfo` / `login+cname`(enterserver) 会拿到同一份应答。
> 要分开得改 `LocalServer.dispatch` 的 override key（例如带上 `forserverinfo` 后缀）。

### 4.1 剩下的唯一目标：莲花台角色

`rolelist` 只喂服务器面板头像（第 22~29 轮结论），主界面莲花台要的是
「当前角色」的完整存档（`roledata.player` / `bag` / `level` / `pet`）。
**但现在可以 patch libgame 做崩溃探针了**，这条线不再是死路。

#### 已做到 / 已知（本轮实测）

1. **`/sdcard/yj/` 覆盖通道已按 login 子类型拆分**（`LocalServer.dispatch`）：
   `login_plain` / `login_serverinfo` / `login_enterserver`，找不到再回落到 `login.json`。
   11.4 用同一个 action 承载三个阶段，不拆开就会互相污染。
2. **能稳定进入「零角色」状态**：`login_plain` 带 services+rolelist，
   `login_serverinfo` 带 `roleids:[]` / `rolelist:{}`，且**两个都不能带 `lastdata`**，得到：

   ```
   rid=INVALID_RID  service=1  servicename=单机服  cname=离线玩家  roleid=None
   ```

   场景不崩、服务器名正确。
3. **`lastdata` 是个陷阱**：只要 login 应答里出现 `lastdata`（哪怕只写 `{"service":1}`），
   客户端就会把 `rid` 解析成 `INVALID_RID`。推测它先于 `rolelist` 被应用
   （JsonCpp 的 `Value` 是 `std::map`，按键名排序遍历，`lastdata` 在 `rolelist` 之前），
   所以永远查不到角色。**要做「当前角色」就得绕开 `lastdata`。**
4. **莲花台仍空、橙色牌匾仍空白且点不动**（用户指出这里应显示「创建角色」）。
   试过 `roleids` 空/数字、`rolelist` 空对象/空数组、`create_role_count` 0/1/缺省、
   `lastdata` 有无、点隐私协议勾选框 —— 牌匾文案和可点状态都没变。

#### 下一步（按性价比）

1. **静态定位牌匾的文案设置点**。`创建角色` 在 libgame `.rodata` VA `0x1bf2ee8`
   （`开始游戏` 在 `0x1c22ad8`），但全文件搜 4 字节指针 **0 命中**——
   说明和协议 key 一样走运行时索引。需要在 Ghidra 里对这两个地址做 PC 相对 xref
   （`v11/_pcref.py` 的路子），找到设置牌匾文字的 UI 函数，再反推它读的状态位。
2. 顺带确认：`YJLoginScene` / `YJLoginCreateRoleLayer` / `YJLoginSelectCreateRoleLayer`
   这三个类只有 mangled 名，需要按 RTTI 或 vtable 反查（第 21 轮的方法）。
3. 若确认「建角色」是服务端驱动的，就还要答 `createrole` 这个 action。

> 探针工具：`v11/swap.ps1` 换 .so 免重打包（约 10 秒一轮），
> 基线镜像是 `native/libgame_local.so`。


## 5. 新武器：运行时 vtable 探针（能按类名拿到虚函数表）

libgame 没有可用的节头，`vtable[-1]` 的 RTTI 指针在文件里是 0（REL 重定位在加载时填），
所以静态找不到 vtable。但在**运行时**那个槽里就是 `base + typeinfo`。

`native/tprt_shim.c` 里加了 `dump_vtable_probe()`：在 `pfn[1]` 首次被调用时
（libgame 已映射且重定位完成）用 `/proc/self/mem` 扫 `.data.rel.ro`，
找出值落在 `[typeinfo, typeinfo+0x40]` 的槽——那个槽就是 `vtable[-1]`，紧跟其后就是 vtable。

实测 `YJCreateRoleSceneVer2`（typeinfo 名字串 `0x1c8e798`）：

```
typeinfo @ +0x1f0d730
vtable   @ +0x1f0a6e0   vt[0..7] = +0x14fea0c +0x1525050 +0xefde6c +0x152ad64
                                   +0x152ad24 +0xea598c +0xea5994 +0x14ea7f4
vtable   @ +0x1f0b3f0   vt[0..]  = +0x14ffa04 +0x151f5cc +0xefde6c …
```

**这意味着现在可以按类名定位任意类的虚函数**（包括 `YJLoginScene`、
`YJLoginCreateRoleLayer`、`YJLoginSelectCreateRoleLayer`），
把「找不到登录场景逻辑」这个长期障碍消掉了。

工具：`v11/native/libtprt-shim-vtable.so`（探针版 shim），换上去跑一次即可。

### 5.1 关键修正：数据段的 VA = 文件偏移 + 0x1000

`libgame.so` 的第二个 LOAD 是 `p_offset=0x1d028e8, p_vaddr=0x1d038e8`，
`.data.rel.ro` 节头也写着 `addr=0x1d14810 / off=0x1d13810` —— **数据段有 +0x1000 的偏移**。
交接书第 19 轮「VA == 文件偏移（恒等）」的结论只对 `.text`/`.rodata` 成立，
对 `.data`/`.data.rel.ro`/`.got` **是错的**。之前 vtable 找不到，根子就在这里。

（`.text` / `.rodata` 在第一个 LOAD，`p_offset == p_vaddr`，恒等成立。）

### 5.2 正确解出的 vtable（离线算，不用跑设备）

REL 重定位的 addend 是就地存放的，所以**文件里的 vtable 项就是函数的 VA**，可以直接读。
算法：找 `typeinfo VA`，在文件里搜这个 4 字节值 → 命中的就是 `vtable[-1]` 槽 →
vtable VA = 文件偏移 + 0x1000 + 4。

| 类 | typeinfo VA | **vtable VA** | vt[1] / vt[2]（析构） |
|---|---|---|---|
| `YJLoginScene` | `0x1f33fec` | **`0x1f33e10`** | `0x16b9ba0` / `0x16b9cfc` |
| `YJLoginManager` | `0x1d20e30` | **`0x1d20d30`** | `0x8735b0` / `0x8737a8` |
| `YJCreateRoleSceneVer2` | `0x1f0e730` | **`0x1f0e528`** | `0x1544ae8` / `0x1544c9c` |
| `YJLoginCreateRoleLayer` | `0x1f31154` | **`0x1f30e30`** | `0x1698f90` / `0x169903c` |
| `YJLoginSelectCreateRoleLayer` | `0x1f31a84` | **`0x1f317b0`** | `0x169c3f4` / `0x169c478` |

五个 vtable 的 12 项全部落在 `.text` 里（`0x5e0000..0x1aa4000`），校验通过。

`YJLoginScene` 的 vtable（前 8 项）：

```
vt[0] +0x18486b5   vt[1] +0x16b9ba0   vt[2] +0x16b9cfc   vt[3] +0x18486b9
vt[4] +0x18486bd   vt[5] +0x18486c5   vt[6] +0x1845393   vt[7] +0x16b1a6c
```

`YJCreateRoleSceneVer2` 的 vtable（前 8 项）：

```
vt[0] +0x18486b5   vt[1] +0x1544ae8   vt[2] +0x1544c9c   vt[3] +0x18486b9
vt[4] +0x18486bd   vt[5] +0x18486c5   vt[6] +0x1845393   vt[7] +0x153ecb0
```

`0x18486b5 / 0x18486b9 / 0x18486bd / 0x18486c5` 是所有类共用的 Thumb 小桩
（`operator delete` 一类），所以 **`vt[1]`/`vt[2]` 是析构，`vt[3]` 之后才是业务方法**。

抽查：`YJLoginScene vt[7]` = `b #0x153ecb0`（尾跳到 `YJCreateRoleSceneVer2 vt[7]` 的同一实现，
说明这个虚函数在共同基类里）；`YJCreateRoleSceneVer2 vt[7]` 会 `ldr r3,[r3,#0x1a4]` 后再虚调用，
是真正的业务入口形状。

### 5.3 探针版 shim

`v11/native/libtprt-shim-vtable.so`：在 `pfn[1]` 首次调用时扫 `.data.rel.ro`、
按 RTTI 反查 vtable 并打日志。注意它对「属性名字符串」会有假阳性，
**可靠的用法是上面的离线算法**（typeinfo → 搜 slot → +0x1000），探针只作交叉验证。

## 5.4 这条路的天花板（实测结论）

继续往下试了三件事，都撞墙，记录下来省得重走：

1. **`YJLoginScene` / `YJCreateRoleSceneVer2` 的 vtable 里没有业务逻辑。**
   两个类的 vtable 从 `vt[8]` 起**完全相同**（都是 cocos2d 基类的同一批 Thumb 小桩），
   只有 `vt[1]`/`vt[2]`（析构）和 `vt[7]` 不同，而两个 `vt[7]` 又是同一个实现
   （`YJLoginScene vt[7]` = `b #0x153ecb0`，就是 `YJCreateRoleSceneVer2 vt[7]` 的入口）。
   → 场景逻辑在**静态 `create()` + 回调**里，vtable 路线到此为止。

2. **`创建角色` / `开始游戏` 在二进制里没有任何静态引用。**
   用 Ghidra 工程（`<path> project `plain`）跑 `Dig4.java` 查
   `0x1bf2ee8`（创建角色）和 `0x1c22ad8`（开始游戏），两个都是 `no references`。
   确认是「运行时按索引取串」，和协议 key 同一个机制。

3. **也不是数据表里的文案。** 把 APK 里 249 个 `.sdb` 全解了（key `43((zmIp`）
   搜 `创建角色`/`开始游戏`/`进入游戏`，**0 命中**。所以文案确实在 libgame 的 `.rodata` 里，
   只是取用方式不可静态追踪。

4. **`strlen` 探针没触发。** 把 `g_tprt_pfn_array[12]`（= `strlen`）换成带日志的包装，
   跑完整登录流程，一条 `STR +` 都没有 → 客户端取 UI 文案**不走**这条 libc 路径。

### 结论 / 下一步建议

UI 文案是「索引 + 偏移」取用的，静态 xref 在这套引擎上系统性失效（交接书第 12 轮已发现）。
要继续，只有两条：

- **A. 动态定位取串函数**：在 Ghidra 里找 `.rodata` 那个大字符串块的「基址 + 偏移」使用者。
  线索：`创建角色` 在 `0x1bf2ee8`，`.rodata` 起点 `0x1bdaf98`，差 `0x17f50`——
  可以在 `.text` 里搜「把 `0x17f50` 这类常量加到某个基址寄存器」的指令序列。
- **B. 换目标**：不再追求「让牌匾显示创建角色」，而是直接用 `YJCreateRoleSceneVer2`
  的**构造函数**（可从 `.ARM.exidx` 的 67,200 个函数边界里，按「写 vtable 地址」的特征筛出来）
  构造一个场景对象，再交给 `Director::runWithScene`。需要先找到 `Director` 单例。

> 已落盘的新工具：`.ARM.exidx` 函数边界表（67,200 项，`v11/_funcs.py`）。

## 5.5 牌匾的真身找到了（本轮最大的定位成果）

从 libgame `.rodata` 里把 UI 资源路径表挖出来（3333 条），登录场景的文字图全在
`assets/images/hd/interface/scenes/scene_login/scene_login_new/scene_login_new.plist` 这个图集里：

```
login_layer_btn_text_create.png      ← 「创建角色」
login_layer_btn_text_enter_game.png  ← 「进入游戏」
login_layer_btn_text_server.png      ← 「服务器」   ← 这个正常显示，证明图集已加载
```

→ **中间那个橙色牌匾就是在这两张图之间切换**：没有角色 → create；有角色 → enter_game。
现在两边都不显示，说明客户端处在一个**第三态**（既没认定「有角色」，也没进「建角色」分支）。

建角色界面自己是独立一层的：

```
interface/scenes/scene_login/scene_login_create/scene_login_create.plist
```

该图集里有 `btn_text_create_n.png`（建角色）、`btn_text_back_n.png`（返回），
APK 里确实存在（22 KB plist + 385 KB pvr.ccz），资源不缺。

### 仍然卡在同一处

`login_layer_btn_text_create.png`、`scene_login_create.plist` 这些串在二进制里
**同样没有任何静态指针引用**（和所有协议 key 一样走运行时索引），
所以「谁把牌匾设成 create 那张图」还是静态找不到。

### 顺带确认的一件事

`lastdata` 是纯毒药：**只要 login 应答里出现 `lastdata`（哪怕只有 `{"service":1}`），
`rid` 和 `service` 就一起坏掉**（INVALID_RID / INT_MIN）。不带 lastdata 才能拿到
`rid=1001, service=1`。但**不带 lastdata 时右上角服务器名又会显示 INVALID_STR**——
两个字段来自不同来源，目前无法同时满足。

### 下一步（唯一还没试的、且便宜的手段）

**hook `AAssetManager_open`**（libgame 的导入符号）打日志，看登录过程中到底加载了哪些
plist/pvr。如果 `scene_login_new.plist` 开了而 `scene_login_create.plist` 没开，
就证明建角色层根本没被实例化，问题在状态机而不是资源。
GOT hook 的家什第 33 轮已经做过一套（`tersafe2_gothook2.c`），可以复用。

## 5.6 两个「非乐固」的致命环境问题（都已定位并修好）

### A. 客户端一直没数据表

用 `fopen` 探针（`g_tprt_pfn_array[5]`）抓引擎打开的所有文件，发现它读的是：

```
/storage/emulated/0/Android/data/com.zmxyol.union.uc/files/update/database/*.sdb
```

而那个目录**不存在**。也就是说 `role.sdb` / `level.sdb` / `item.sdb` / 技能 / 掉落
全都读不到 —— 没有 `role.sdb` 就查不到「职业 1 → 孙悟空模型」，莲花台必然空。

**修法**：从 APK 的 `assets/database/` 抽出全部 **250 个表（14.9 MB，含 `sdb.list`）**，
推到 `files/update/database/`。重启后 logcat 出现完整加载进度
（`progress: 12 → 22 → 25 → 30 → 50 → 99.9`），`sdb.list`/`role.sdb` 均成功打开。

⚠️ **不要同时放 `update/rver.dat`**：一放，客户端就把整个 `update/` 当资源根，
开始找 `update/interface/ui_texts/...`（那里没图）→ 直接黑屏。

⚠️ 引擎**不走 `AAssetManager`**（hook 挂上 0 次触发），它直接用 zip 读 `base.apk`。

### B. 端口 8077 被 8.0 那个包占着

`LocalServer` 报 `cannot bind loopback:8077`。查 `/proc/net/tcp6` 发现
listener 属于 **uid 10080 = `org.yjmobile.zmxy`**（8.0 单机版）——
它在后台常驻，自己的 LocalServer 占着同一个端口。

后果：11.4 的本地服绑不上，客户端的请求**全被 8.0 的服接走**，
表现为「更新检查失败 / 网络传输数据出现错误」，而且**时好时坏**（取决于 8.0 是否在跑）。

**修法**：跑 11.4 前先 `adb shell am force-stop org.yjmobile.zmxy`，
确认 `/proc/net/tcp6` 里 8077 只剩 TIME_WAIT（state 06）而不是 LISTEN（0A）。

### C. 修好这两条之后的定案结果

端口空闲 + 250 张表 + 最优 login 应答，全链路首次干净跑通：

```
offline server listening
BODY {...checkupdate...}
BODY {...,"action":"login",...}
OVERRIDE login [login_serverinfo]
```

`savedata.dat` 达到最完整状态：

```
rid=1001  service=1  cname=离线玩家  roleid=1  servicename=单机服  uniqueid=INT_MIN
```

**但莲花台仍然空、牌匾仍然空白。**

→ 结论：**角色渲染不依赖登录应答里的 rid/service/rolelist/roleids/create_role_count**。
这些字段已全部正确，客户端仍不画。状态机卡在更里面的地方（很可能是
`YJLoginScene` 对 `YJSimpleRoleInfo` 的某个字段，或某个我们没发的 observation）。

## 5.7 结论：静态追踪在这个引擎上「数学上」不可行

把怀疑的几种取串机制逐条排掉，并做了一次量化：

| 假设 | 验证 | 结果 |
|---|---|---|
| 有「偏移表」(id→rodata offset) | 全文件扫 ≥60 项严格递增、且值 <0x200000 的 4 字节串 | 只有 2 段，都不含目标偏移 |
| 有格式串 `login_layer_btn_text_%s` | 搜 .rodata | 0 命中（只有 3 个完整名字） |
| 名字写在布局文件里 | 全 APK 搜 3 个 sprite 名 | 只有图集 plist 引用，没有布局文件 |
| 只是 Ghidra 漏分析 | 直接统计 | **.text 的 543 万条 4 字节字里，只有 75 个落在 .rodata** |

最后一行是决定性的：**整个引擎几乎没有「代码里直接写字符串地址」这回事**，
5,438,369 个字里只有 75 个指向 .rodata。所以「找谁把牌匾设成 create 那张图」
不是在找一个漏网的 xref，而是这个引擎**根本不产生 xref**。

### 还剩的三条路（都超出当前工具面）

1. **逆出字符串管理器本身**：它一定有个初始化函数把所有串读进堆表。
   但「找这个初始化函数」又要靠 xref —— 同一个死循环。
   唯一破法是在堆里找到那张表（`/proc/self/mem` 扫 `0x1c49118+base`），
   然后……没有 ptrace 就没法抓「谁读了这个槽」。
2. **换运行环境**：真机 + Frida（arm64 跑不了 32 位）、或关掉 houdini 的检测。
   交接书第 6 节已确认这台模拟器做不到。
3. **接受现状**：服务端/资源侧能做的已经到顶（乐固已破、登录链通、数据表齐、
   端口冲突已排除、savedata 完全有效），只差客户端最后那一步渲染。

## 6. 反面教材（别再走）

- 不要试图「删掉 libtprt」：`libgame.so` 的 `DT_NEEDED` 里有它，而且要用它的两张表。
- 不要把 `.ARM.exidx` 当加密数据异或。
- `pfn` 表不要一律返回 0（= 校验失败）或一律返回 1（`fopen` 会返回 1 → `fseek` 崩 0x31）。
- **别忘 `iptables -t nat -F OUTPUT`**：上一轮 Route A 留下的 REDIRECT 会让
  「更新检查」直接报「网络连接出错(0)」，看起来像别的问题。
