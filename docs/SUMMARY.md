# 造梦西游 OL 11.4.0 本地服 — 现状与结论（阶段性汇总）

最后更新：2026-11-06 21:15

## 一、已经做成的

| 能力 | 状态 | 说明 |
|---|---|---|
| 破译通信加密 | ✅ | `-=4399=-` + base64(DES-ECB)，key `1#(z1Rr>`，见 `v11/codec.py` |
| 本地 TLS 服务端 | ✅ | `v11/mitsrv4.py`，伪装 `zmol.4399sj.com`，客户端照常握手 |
| 更新检查 | ✅ | 返回 `code:1000` 即过 |
| 登录 | ✅ | 能进入选服界面（服务器名、面板正常） |
| 服务器列表/头像 | ✅ | `services[].roleids` 决定面板里显示的角色的头像 |
| 角色对象解析 | ✅ | 面板能显示角色头像，证明 `rolelist` 的 `YJSimpleRoleInfo` 被解析 |
| 重启后保持 | ✅ | 存档里 `rid`/`roleid`/`username` 能正确落盘 |
| 联网失败重试缓存 | ✅（已解密） | 登录失败时客户端把待发请求写进 `savedata.cache`，可读出来 |

## 二、还没做成的（目标：莲花台显示角色 + 进入游戏）

| 现象 | 证据 |
|---|---|
| 主界面莲花台上没有角色 | 截图多轮一致 |
| 没有 8.0 那种左侧角色列表 | 8.0 客户端有，11.4.0 没有 |
| 没有「进入游戏」按钮文字 | 8.0 有「进入游戏」按钮，11.4.0 只有橙色空按钮 |
| 客户端不发 `REQUEST_LOGIN_GAME_SERVER` | 请求日志里只有 checkupdate / login / servicelist |
| 存档里 `cname` 永远为空、`uniqueid` 永远 INT_MIN | 导出 `savedata.dat` 多次确认 |

## 三、关键结论（都有实测支撑）

1. **11.4.0 的莲花台不是由 `rolelist` 画的**（8.0 是）。
   同一份 `rolelist` 在 8.0 会显示左侧角色列表+莲花台角色，在 11.4.0 只让服务器面板出头像。

2. **`savedata` 不能放进 login 应答**。
   一放，客户端把角色状态清空（`rid=INVALID_RID`、`service=INT_MIN`）。

3. **客户端不发进入游戏服的请求**，所以为它准备的应答永远送不到。
   `enterserver`（= `login` + `cname` + `forserverinfo`）只在**重放缓存请求**时出现过一次。

4. **客户端侧改代码的路全部被乐固堵死**（逐条实测）：

| 改什么 | 结果 |
|---|---|
| libgame 文件 | 加载期完整性校验 → `exit(25)` |
| libgame 内存代码 | 运行期校验 → `exit(25)`（已能成功写入 `rw-p` 的 .text） |
| libtprt 文件 | 自校验 → `exit(25)` |
| libtprt 内存（把 11 个 `svc exit` 改 no-op） | 仍 `exit(25)`，且 25 不来自那些 svc |
| dex / manifest（去乐固包） | 能改，但 `nativeInit` 里 `exit(15)` |

## 四、已知的协议事实（供后续使用）

```jsonc
// 所有应答都要带
{"code":1000, "servicetime":<unix>,
 "base":{"host":"127.0.0.1","port":8077,"service":1,
         "pkservice":{"host":"127.0.0.1","port":8077}},   // pkservice 是对象！
 "pkservice":{"host":"127.0.0.1","port":8077}}

// 登录应答（8.0 的形状，11.4.0 只兼容其中的角色部分）
{"services":[{"service":1,"name":"单机服","roleids":["1001"],"isnew":0,
              "createtime":0,"starttime":0,"endtime":0,"state":1,"new":0}],
 "rolelist":{"1":[{"rid":"1001","username":"玩家","roleid":"1","rolelv":"1",
                   "rolelevel":"1","level":"1","exp":0,"vip":0,
                   "wingid":0,"weaponid":0,"suitid":0}]},
 "lastdata":{"service":1,"rid":"1001","username":"玩家"},
 "lastrid":"1001","username":"玩家",
 "create_role_count":{"1":1,"2":1,"3":1,"4":1}}
```

存档校验门槛（v800 逆向结果，11.4.0 疑似同样适用）：
`point` 必须存在；`roledata.player.roleid` 必须存在；
`bag` 需 `bagsize`/`maxglobalitemid`/`spiritcount`；
`level.psl` 必须是数组；`pet` 必须是非空数组。

11.4.0 的类型要求：**`rid`/`roleid`/`rolelv` 必须是字符串**（数字会崩）。

## 五、还能怎么继续

1. **拿到一份正版 11.4.0 的登录/进服抓包**（哪怕只有一次真实登录的应答）——
   这是最快路径，能直接补齐莲花台需要的真实字段。
2. **完整逆向乐固壳**（找到并废掉它的完整性校验器），然后在去乐固包里用崩溃探针
   逐层定位 `YJLoginScene` 的角色渲染逻辑。工作量：逆向一个商业加固壳。
3. **接受现状**：11.4.0 停在选服界面；改用 8.0（`v800/build/offline-v800-*.apk`）玩，
   8.0 那条线是能显示角色并进入游戏的（只是那份 8.0 包目前启动时报「更新检查失败」，
   需要把它的内置服务端重新跑通）。

## 六、11.6.0（<downloads>/造梦西游OL.apk.1）实测

- 版本：**11.6.0**（package `com.zmxyol.union.uc`，版本号显示 11.6.0）
- `libgame.so` 33,917,524 字节（11.4.0 是 33,102,420），同样是 **XOR 0xB5 加密**
- `-=4399=-` 前缀、`rolelist/savedata/roledata/lastdata/pkservice` 等 key **完全一致**
- 用同一套本地服跑：**能连、能登录、能进选服界面**（说明 DES key 也一致）
- **结果与 11.4.0 完全相同**：`服务器: INVALID_STR` + 莲花台空 + 无「进入游戏」

→ 换 11.6.0 不解决问题，两版行为一致。
