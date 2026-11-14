# 造梦西游OL 8.0.0（尝鲜版）· 至尊修复

> 2026-11-14 · 参考 11.4 的修法（fix256~258）
> 产物：`out\造梦西游OL-8.0-至尊修复版.apk`（303,439,576 B，同签名，覆盖安装保档）

---

## 一、这一轮做了什么

8.0 的离线 mod 是老版 `LocalServer`（只有至尊**升品/升星**），照 11.4 这一轮查出来的
问题逐条对齐：

| # | 11.4 的问题 | 8.0 原来 | 现在 |
|---|---|---|---|
| 1 | 缺 `extremeCnf`（官服 roledata2 顶层规则表） | 没有 | **roledata2 顶层下发官方 `extremeCnf`（10 条规则）** |
| 2 | 缺 `slot_stunt_*` 传承槽配置 | 没有 | 下发官服四张表（`lv_power` 50 项版本） |
| 3 | 传承挂接（inlay）没实现 | 没有 | 支持 `type 27` / `54`：写 `CD` + `CQ` + 回 `update_cg` |
| 4 | 锻升（`type 6`）没实现 | 没有 | CE+1（上限 CV=100）+ 官方费用（熔炼丹 + 仙气/元灵精魄） |
| 5 | 聚炼（`type 9`）没实现 | 没有 | CE<100 检查 + 扣聚炼丹 + CV=100 |
| 6 | `CV` 被当成比例写（CV=CE） | 没写 CV | 一律 CV=100（官方语义：传承等级上限） |
| 7 | 升品写 `CL`，客户端读 `CF` | 写 `CL` | **CF 和 CL 都写**（8.0 客户端读哪个都能用） |
| 8 | `applyNoblestStats` 查 `enhance_data_id` 查不到 → 升星不加属性 | 同样问题 | 改查 `noblest_enhance_data_id`，查不到再回退 |
| 9 | 材料消耗按客户端 `material[].A4`（恒 0）→ 永远免费 | 同样 | 锻升/聚炼按官方费用扣，升品/升星维持原样 |

另外给 8.0 补了和 11.4 一样的诊断日志：`HANDLEACTION done type=… CE=… CD=… CQ=… result=…`。

## 二、为什么 8.0 能这么改

8.0 的 mod dex 只有 122 KB / 44 个类，而且是**我们自己的 Java**（没混淆），所以这一轮把它
反编译回可编译源码（`v800\java`，20 个 .java），修掉 jadx 的 catch 变量别名等 30 处
编不过的地方，再按 11.4 的改法改，重新编译替换 `classes.dex`。

- 类列表与原包**逐字节比对一致**（44 个类，无增无减）
- `assets/`、`lib/`、`classes2.dex`（游戏本体 dex）**一个字节没动**
- 签名证书与修改前一致（`ad9634aa…`），可直接覆盖安装

## 三、构建

```powershell
powershell -ExecutionPolicy Bypass -File v800\build.ps1
# 默认以 <downloads>/造梦西游OL-8.0-尝鲜版.apk 为底包，产物 <tmp>/fix80.apk
```

目录：

| 路径 | 内容 |
|---|---|
| `v800\java\` | 反编译 + 修复后的 mod 源码（20 个 java） |
| `v800\tools\fix_*.py` | 把 jadx 产物修到能编译的脚本（catch 别名 / MemPatch / SdkForce / DexAppender） |
| `v800\tools\patch_noblest.py` | 至尊修复补丁脚本（对 LocalServer 做那 9 处改动） |
| `v800\tools\repack80.py` | 换 `classes.dex` 的打包脚本 |
| `v800\build.ps1` | 一键 javac → d8 → repack → zipalign → 签名 |

## 四、验证要点

1. 装 `out\造梦西游OL-8.0-至尊修复版.apk`（同签名，覆盖安装保档）。
2. 背包 → 至尊装备：**升品/升星**面板的「消耗」应开始显示数字（不再是 0/0）。
3. **传承**页：放一件传说/魂器进传承槽 → 日志 `HANDLEACTION inlay target=… <- source=…`。
4. **锻升** → CE 涨、扣熔炼丹+仙气；**聚炼** → 扣聚炼丹。
5. 日志（8.0 的离线目录）：
   ```
   adb shell "grep -a -E 'HANDLEACTION|NOBLEST' /data/data/org.yjmobile.zmxy/files/offline/protocol.log | tail -30"
   ```

## 五、风险与未验证

- **没有真机/模拟器回归**（本机 adb 无设备）。改动集中在 `LocalServer`，其余 19 个类
  只是从反编译源码重新编译（逻辑等价，`MemPatch`/`SdkForce` 里被 jadx 搅乱的
  try/finally 做了等价重写）。
- 8.0 的 `libgame.so` 是加密的（SecNeo），无法从客户端确认它读的是 `CF` 还是 `CL`，
  所以**两个都写**；也无法确认它是否读 `extremeCnf`/`slot_stunt_*` —— 多发的键
  客户端不认识会忽略，不会崩。
- 8.0 的**聚炼**没有写 `CL` 到期时间戳（8.0 的品阶写在 `CL`，怕打架），所以
  8.0 上聚炼目前只是「扣料 + CV=100」；要真倒计时，得先确认 8.0 客户端读哪个字段。
- 真灵没有动（8.0 这版客户端连字符串都是加密的，没法像 11.4 那样核字段）。

---

# 2026-11-14 补：真正的根因（用 adb 直接对话本地服查出来的）

前一次「至尊面板还是老样子」的真正原因**不是字段名**，而是：

## 1. 服务端存档里 `bag` 没有 `item` 数组

```
HANDLEACTION dbg bagNull=false itemsNull=true tgtA5=500223
HANDLEACTION target not found
```

客户端周期上传的 `savedata` 里 `bag` **没有 `item` 成员**（8.0 就是这样），而
`buildSavedataBlock()` 只「读存档 → 拼一份发给客户端」**从不写回**：

- 邮件附件（`claimMail()`）只回给客户端，没进服务端背包；
- 下发时补的材料、至尊装备也不在存档里；
- 于是 `handleAction` 的 `findItem` 永远找不到那件装备 → 整个分支跳过 →
  应答是 `{"result":1,"update":[],"msg":"升品成功"}`，面板「点了没反应」。

修：

- `buildSavedataBlock()` → 包一层 `buildSavedataBlock0()`，拿到结果后
  `ensureNoblestMaterials()` + `writeSave()` **回写存档**（以后存档和下发一致）；
- `claimMail()` → 新增 `grantAttachmentsToSave()`，附件真正入包；
- `handleAction()` → `findItem` 为空时用请求里的 `target` **补进背包**（防御）；
- `ensureNoblestMaterials()` → 熔炼丹 503206 / 聚炼丹 503212 / 元灵精魄 503199 /
  六色玄晶 503200-503205 / 三种碎片 503208-503210 各补到 9999。

## 2. 验证（真机 adb 直接对话，不用点界面）

工具：`v800\tools\probe80.py`（DES/base64 直接 POST 到 mod 的 127.0.0.1:8077）

```
adb forward tcp:8077 tcp:8077
python probe80.py login
python probe80.py ha 6 500223
```

修之前：`{"info":{"result":1,"update":[],"msg":"升品成功"}}`（空转）
修之后（adopt 生效那版）：`{"info":{"result":0,"update":[{"A5":500223,...}],
"msg":"材料不足：熔炼丹×5、仙气×500"}}` —— 已经真正走进锻升逻辑了。
本版再把材料补齐 + 存档回写，理论上应回 `锻升成功` 且 `CE=1`。

## 3. 本版产物

`out\造梦西游OL-8.0-至尊修复版.apk`（303,439,576 B，同签名，覆盖安装保档）

**注意**：最后这版（fix80e，加了存档回写 + 材料补齐）**没来得及在设备上跑完验证**
（安装被中途取消），前一步（fix80d）已验证「能走进锻升逻辑、只差材料」。
装完请点一次锻升，看是否 `CE` 涨、材料扣；日志：
```
adb shell "tail -40 /sdcard/Android/data/org.yjmobile.zmxy/files/offline/protocol.log"
```

---

# 2026-11-14 多存档（参考 11.4）

> 产物：`out\造梦西游OL-8.0-多存档版.apk`（303,439,576 B，同签名，覆盖安装保档）
> 5.5 那套（`com.apocalua`）是别人做的离线 mod，`LocalServer` 只有 280 字节的桩，
> 没有多存档逻辑；所以这份是照 **11.4** 的做法改的。

## 8.0 原来的问题（和 11.4 踩过的是同一个坑）

11.4 的 `ensurePresetRoles()` 里留着一句注释，说的就是这个病：

> 「以前这里为了绕开『多角色进游戏 GL 线程崩』把所有角色删到只剩 1 个，结果玩家
> 在游戏里新建的角色，一退出登录就被删掉 —— 表现就是『新建的存档不保存，
> 只剩孙悟空那一个档』。」

8.0 正好反过来：`ensurePresetRoles()` **每次登录都按 6 个职业各补一个角色**
（孙悟空/唐僧/猪八戒/沙僧/猫妖/异界王子）。后果：

- 角色列表里全是没玩过的占位角色，玩家自己建的号被淹没；
- 新建角色的 `rid = max+1` 跟这些占位号混在一起；
- 列表发给客户端时又是「全部角色塞进 service 1..5 每一个键」，同一个角色重复出现。

## 改动（都在 `LocalServer`）

| # | 改动 |
|---|---|
| 1 | `ensurePresetRoles()` 重写：**不再自动增加任何角色**。只在 `roles.json` 为空（首次进游戏）时种一个默认角色；之后只做 ①`service<=0` 归到 1 服 ②每个角色补自己的 `profile_<rid>.json` ③`roles.json` 的等级**以该角色的存档为准**（否则客户端拿列表等级把存档压回 1 级） |
| 2 | 清理占位号：`roles.json` 里**没有存档文件**、又不是当前/上次登录角色的记录，直接删掉（旧版自动铺的那 5 个占位会被清走） |
| 3 | `roleListAll()` 改成**按 service 分组**（照 11.4），不再把同一份列表塞进每个服的键 |
| 4 | `simpleRole()` 的字段改成**字符串**（8.0 登录模板里 `"rid":"1001"`、`"rolelv":"1"` 全是字符串），避免列表解析类型不一致 |
| 5 | 新增 `saveFileOf/profileFileOf/readSaveObjOf/writeSaveOf`：按 rid 操作各自的存档文件（`savedata_<rid>.json`、`profile_<rid>.json`；rid=1 仍是老的 `savedata.json`） |

已有的机制保留：每个请求按 `rid` 切 `sCurRid`（`dispatch` 开头）、
新建角色 `createrole2` 分配 `max+1` 的 rid 并把客户端上传的初始档写到该 rid 的文件里、
`lastrid.txt` 记住上次登录的角色。

## 验证步骤

1. 装 `out\造梦西游OL-8.0-多存档版.apk`（同签名，覆盖安装保档）；
2. 进游戏 → 角色选择页：应只剩**你自己的角色**（旧版自动铺的占位被清掉）；
3. **新建第二个角色**（换职业）→ 退出到角色选择 → 两个角色都在；
4. 分别进两个角色，各升一级 / 改一下背包 → 退出再进，**两边的进度互不影响**；
5. 日志（每次登录都会打）：
   ```
   adb shell "grep -aE 'ensureRoles|createrole|roleListAll' /sdcard/Android/data/org.yjmobile.zmxy/files/offline/protocol.log | tail -20"
   ```
   能看到 `ensureRoles: drop unplayed role rid=…`（清理占位）、
   `ensureRoles: seeded profile rid=…`、`ensureRoles: roles synced from saves`。

## 没验证的部分

这次改动**没有在设备上回归**（安装由用户手动完成）：改动集中在角色表维护和列表组装，
逻辑是从 11.4 直接搬过来的；`roles.json` 的清理只在「没有存档文件」时才动手，
当前角色和上次登录的角色永远保留，所以不会误删正在玩的号。

---

# 2026-11-14 改：预置 6 职业 + 每个角色一份初始存档

8.0 客户端角色页**没有"创建角色"的加号**，新角色只能由服务端预置。所以按用户要求改成：

1. `ensurePresetRoles()` **保证 6 个职业各有一个角色**（孙悟空/唐僧/猪八戒/沙僧/猫妖/异界王子，
   `PRESET_CLASSES = {1,2,3,4,5,6}`），已经有了就不动；
2. 每个角色检查自己的存档，**没有就现造一份初始档**（`initialSave()`）：
   - 优先**以本机任意一份真实存档为模板克隆**（结构块/版本串一个不缺，最容易过校验），
     然后清进度：`point=0`、`bag.item=[]`、货币清零、`level.psl=[]`、`pet.petinfo=[]`、
     `roledata.role.rolelv=1/roleexp=0`、`player.completeguide=[]`；
   - 并把**职业相关块删掉**（`roleskill` / `fategod` / `artifactinfo` / `fashionassist`），
     再把 `roledata.role.roleid`、`roledata.player.roleid` 改成目标职业 ——
     塞别的职业的技能表会直接「存档数据已损坏」，所以宁可留空让客户端按职业初始化；
   - 版本串统一 `8.0.0`（避免「版本过低」）；
   - 找不到任何模板时退回 `initialSaveMinimal()` 手搓的最小形状；
3. 每个角色补 `profile_<rid>.json`；`roles.json` 的等级以该角色存档为准；
4. 角色列表按 service 分组（同一份列表不再塞进每个服）。

## 产物

`out\造梦西游OL-8.0-多存档版.apk`（303,439,576 B，同签名，覆盖安装保档）

## 验证

1. 装完进角色选择页：应有 6 个角色（6 个职业各一个）；
2. 任选一个之前没玩过的职业进去：应能建角进游戏（服务端已给它一份初始档）；
3. 玩一会儿退出，再进另一个职业：两边进度独立；
4. 日志：
```
adb shell "grep -aE 'ensureRoles' /sdcard/Android/data/org.yjmobile.zmxy/files/offline/protocol.log | tail -20"
```
应看到 `ensureRoles: preset class=N rid=…`、`ensureRoles: seeded initial save rid=… class=…`。

## 风险

初始档是「克隆模板 + 清进度 + 清职业相关块 + 改 roleid」，**没在设备上验证过**。
如果进某个职业时弹「存档数据已损坏」，那说明客户端要求 `roleskill` 之类的块不能为空 ——
下一步就得按职业注入正确的技能表（`role_creation` 那套），把日志发我即可。

---

# 2026-11-14 补：角色列表被旧调试覆盖文件挡住（\"只有猴子\"的真相）

设备上 `/sdcard/yj/login.json`（11-12 22:53 留下的调试快照）会被 mod 当**覆盖应答**直接用：

```
REQ  {"action":"login",...}
OVERRIDE login                 ← 命中 /sdcard/yj/login.json，直接返回文件内容
RESP ... "rolelist":{"1":[{"rid":"1001","roleid":"1","username":"玩家",...}]}
```

那份文件里写死了「只有一个角色」，所以服务端 `roles.json` 里即使已经有 6 个角色，
客户端也只看到 1 个。

处理：

1. 设备上把这两个旧覆盖改名备份（可逆）：
   ```
   /sdcard/yj/login.json            → login.json.bak
   /sdcard/yj/login_enterserver.json → login_enterserver.json.bak
   ```
   改完 probe login 立刻回到 6 个角色（实测见 README 上一节）。
2. 代码兜底（fix80k）：覆盖文件命中时，如果 action 是 login/rolelist，
   把应答里的 `rolelist` 换成 `roleListAll()` 的实时结果（`patchRoleList()`），
   以后再有人放回静态覆盖文件也不会挡住角色列表。

## 验证过的结果（设备实测）

```
service 1 roles 6
   rid=1001 roleid=1 玩家        （已有角色）
   rid=1002 roleid=2 唐僧
   rid=1003 roleid=3 猪八戒
   rid=1004 roleid=4 沙僧
   rid=1005 roleid=5 猫妖
   rid=1006 roleid=6 异界王子
```

`roledata2`（rid=1002 唐僧）返回：`role.roleid=2`、`lv=90`、`bag items=15`、`point=1e8`、`psl=全部关卡 5★`
—— 说明每个预置角色都拿到了自己的初始存档。

---

# 2026-11-14 本命神（fategod）修复 —— 参考 11.4

## 根因

8.0 的 mod 在 `buildSavedataBlock0()` 里把本命神写死成：

```java
FATE_GOD_LV  = 70
FATE_GOD_EXP = 999999
fategod.put("lv", 70); put("level", 70); put("exp", 999999);
```

而客户端的等级是**从 exp 反推**的（11.4 的 `fillFateGod` 注释：

> 「5.5 handleFateGodLevelUp 同款公式：exp 反推等级」

即 `lv = floor(sqrt(exp + 1))`）。所以：

| exp | 客户端算出的等级 |
|---|---|
| 999999 | **1000**（上限 90，直接越界） |
| 8099 | 90 ✅（90×90−1） |

等级越界就是「本命神有 bug」的来源：面板等级/属性/晋阶全都按 1000 级去查表。

另外 8.0 从来不写 `fategod.degree`，而客户端的晋阶表 `fategod_raise_degree.sdb`
的 grade 是 **1..90**，对应 `degree` 只能 **0..89**（11.4 同一套语义：
`degree/10` = 阶、`degree%10` = 阶内级）。

## 改动（`patch_fategod.py`）

| # | 改动 |
|---|---|
| 1 | `FATE_GOD_LV` 70 → **90**，`FATE_GOD_EXP` 999999 → **8099**（= 90 级），新增 `FATE_GOD_DEGREE = 89` |
| 2 | force 块补 `degree = 89`（下发的 fategod 直接是满级满阶） |
| 3 | 新增 `sanitizeFateGod(sd)`（11.4 同款）：`exp` 夹到 0..8099、`degree` 夹到 0..89、按 exp 回写 `lv/level`、`skills` 缺就补 `[1220,1120,1230]`；**下发前和客户端上传后都跑一遍**（上传路径在 `storeSaveUpload` 开头） |
| 4 | 新增 `fillFateGod(action, req, r)`：action 以 `fategod` 开头时——<br>· 晋阶（raisedegree/stage）：扣命火 **503371 × 11**，`degree = min(89, degree+1)`，材料不够回 `state=1, msg=命火不足，需要 N 个`；<br>· 升级：`exp` 推到 `min(8099, lv*lv-1)`，`type=1` 一次 1 级、否则一次 10 级 |

## 验证

1. 装包 → 进游戏 → 本命神面板：等级应显示 **90（满级）**，不再是 1000/异常；
2. 点晋阶：扣 11 个命火、`degree +1`（不超过 89）；
3. 日志：
```
adb shell "grep -aE 'fategod' /sdcard/Android/data/org.yjmobile.zmxy/files/offline/protocol.log | tail -10"
```

## 没验证的部分

`fillFateGod` 挂的是 `action.startsWith("fategod")` 这一族名字（11.4 用的是
`fategod*` / `upfategod` / `fategodlevelup` / `fategodraisedegree`）。8.0 客户端
如果走的是 `handleaction` 的某个 type，日志里会看到对应的 action/type，把那一行发我就能精确接上。
下发路径（满级 90 / degree 89 / exp 夹紧）是确定会生效的。
