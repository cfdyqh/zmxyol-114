# 11.4.0 离线版 —— 进游戏的最优配置（WINNER）

日期：2026-11-07 13:4x
环境：LDPlayer9（Android 9 / x86 + houdini 9.0.7a），包 `v11/build/delg-url2.apk`

## 结果

能进游戏（主村庄 + 世界地图），重启后仍在游戏内。

截图：
- `接手材料\ldplayer_shots\ld114_RESTORED_e2.png`（村庄，角色"玩家"站在右侧，HUD 全开）
- `接手材料\ldplayer_shots\ld114_relaunch.png`（重启后在世界地图）

## 配置

应答文件：`v11/login.11.4.WINNER.json`（= `v11/variants/E2_lastdata_arr.json`）

推到设备四个覆盖点（四种 login 子类型共用同一份）：

```
/sdcard/yj/login_plain.json
/sdcard/yj/login.json
/sdcard/yj/login_serverinfo.json
/sdcard/yj/login_enterserver.json
```

启动前清一次客户端存档（让客户端从零解析应答）：

```
adb shell su -c 'rm -f /data/user/0/com.zmxyol.union.uc/files/savedata.dat'
adb shell am start -n com.zmxyol.union.uc/org.yjmobile.zmxy.zmxy
```

约 20 秒后自动进入登录场景，莲花台出现孙悟空、牌匾显示「进入游戏」；点击后进入村庄。

## 关键差异（这是之前 35 轮卡住的点）

`lastdata` 必须写成**空数组 `[]`**，不能是对象：

| `lastdata` | 结果 |
|---|---|
| `{"service":1,"rid":"1001","username":"玩家"}` | `rid=INVALID_RID`，莲花台空、牌匾空白 |
| **`[]`（空数组）** | **`rid=1001`，莲花台出现角色、牌匾变「进入游戏」** ✅ |
| 缺省 | `rid=INVALID_RID` |

依据：16.9 真实服务器应答（`flowlab/fixtures/1f84b0de...json`）里 `lastdata` 就是 `[]`。
这就是"去 16.9 找"的价值。

## 其余生效字段

- `services[]`：`service:1, name:"单机服", starttime:"0", state:1, createtime:0, unique_id:1, pay_unlock_roles:[1], roleids:[1]`
  - `roleids` 是**职业 id**（1=孙悟空），决定服务器面板头像
- `rolelist: {"1":[role]}`：role 用 16.9 的 18 字段版
  `rid/rolelv/roleid/username/dateline/service/wingid/suitid/weaponid/nweaponid/pigmentid/fcpigmentid/dazzleid/fashion_weapon_visible/fashion_equip_visible/fashion_weapon_extra_info/fashion_equip_extra_info/username_color`
- `savedata`：**整份取自 16.9 `roledata2` 真实应答**（`v11/sd169.json`），仅把 `roledata.player.roleid/rid`、`roledata.role.rid/roleid/rolelv` 改成 11.4 的值
- 顶层：`rid:"1001"`、`service:1`、`service_name:"单机服"`、`username:"玩家"`、`cur_role_id:"1001"`、`session`、`bindmsg`
- `base`/`pkservice`：`{host:"127.0.0.1", port:8078, pkservice:{host,port}}`

## 落盘结果

进村后客户端 `savedata.dat`：
`rid=1001  service=1  servicename=单机服  cname=离线玩家  roleid=1  uniqueid=1`

（进村前 `uniqueid` 是 INT_MIN，进游戏后变成 1）

## 反面记录（别再试）

- `lastdata` 用对象 → rid 打回 INVALID_RID（第 35 轮"lastdata 有毒"的结论其实是形状错了）
- 只给 `login169_sd.json`（简单 role + lastdata 对象）→ rid 能到 1001，但顶部服务器显示 INVALID_STR、莲花台空
- `cur_role_id`/`unique_id`/`pay_unlock_roles` 一起加进 lastdata 对象的版本（E1）→ 反而破坏 rid
