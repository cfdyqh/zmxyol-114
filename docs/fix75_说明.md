# fix75 说明（2026-11-08）

安装包：`v11/build/fix75.apk`（716,498,718 字节，包名 `com.zmxyol.union.uc`）
手机：Redmi K70 Ultra / Android 17 / HyperOS，已用 `pm install -r -d` 覆盖安装，数据保留。

## 1. 手机闪退（SoundPool）

根因不变：`Cocos2dxSound.doPlayEffect -> SoundPool.play -> HyperOS libsoundpool/audio HAL`
空指针 SIGSEGV，属系统音频栈问题。

修复方式：直接改 `classes.dex` 里
`org.cocos2dx.lib.Cocos2dxHelper.playEffect(String,boolean,int)` 的字节码为
`const/4 v0, 0; return v0`，其余指令填 nop。这样战斗音效不再进入
`SoundPool.play`，系统音频层不会被触发；音乐（MediaPlayer）和 SoundPool 的
加载/初始化保留。

验证：`classes.dex` 已重新计算 SHA-1 / Adler-32；baksmali 反编译确认方法体为
`const/4 v0, 0x0 / return v0`。手机已装 fix75，进战斗不再走播放路径。

## 2. 邮件

11.4 打开邮件面板发的是：

```json
{"action":"mailnew","offset":0,"type":2,...}
```

旧代码把「带 type」当成领取，`mailnew` 永远走 `putMailReward`，回包没有
`mails`，所以面板空白。

现在：
- `mailnew` / `mail` 一律按**列表**处理，回 `mails` + `total_page_num`。
- 领取只认 `receivereward(type=37)` 和 `mailreceivebatch`。
- `maildelete` 单独处理。
- 邮件对象字段：`id/title/desc/from/dateline/expired/state/mtype/type/is_user/reward`。

实测：手机邮件面板能看到两封（奖励邮件 + 系统邮件），日期/来源/附件都正常。

## 3. 神秘商店

按用户要求：**所有道具和装备**上架，统一 **1000 灵魂**。

实现：`mystGoods()` 依次扫描
`item / equip / noblest_equip / magic / stone / pet_pill / pet_equip / forge`
八张表，忽略表里的 `spirit_cost`，价格固定 `itemJson(503031, 1000)`；
按 id 去重。

实测（本机 HTTP 直接打 8077，绕过 UI）：

```
SHOP mysteryshop2 goods=2176
code=1000 goods=2176 price 全部 1000
```

购买：`fillBuy` 对装备改用 `equipJson(id,0)` 写存档（以前写 `itemJson`，
A9=8 会变成残件，开背包/穿戴会崩）；装备数量固定 1，不合并。

## 4. 真灵

真灵不是宠物：数据走独立的 `true_spirit_bag`，动作走 `truespirit*`
（`truespiritinfo / truespiritfusion / truespirituplevel / ...`），
**没有**写进 `pet.petinfo`。

`buildSavedataBlock()` 现在注入：

```json
"true_spirit_bag": {
  "bagsize": 8,
  "maxspiritid": 439999,
  "truespiritinfo": [ { "dataid":"9005", "spiritid":"9005", ... } ],
  "list": [ ... ]
}
```

条目字段照 16.9 真实存档样本（数值全部字符串）：`basedef/basehp/basemp/basestr/
bonelevel/comprehension/dataid/defimprove/ferity/hpimprove/iselite/isspecialchange/
level/lifetime/mixlevel/mpimprove/petequips/petexp/petid/spiritpower/strimprove/
tamestate/triones`，另加 `spiritid`。注入后客户端没弹「存档数据已损坏」，
`true_spirit_blood_config.sdb` 也被加载，说明容器和条目至少过了校验。

调试开关：
- `/sdcard/yj/true_spirit.json`：整个 bag 的覆盖文件，改完重启游戏生效。
- `/sdcard/yj/no_true_spirit`：存在则不注入，用于排查存档损坏。

## 5. 构建方式（下一次改 Java）

```powershell
powershell -ExecutionPolicy Bypass -File v11\build.ps1 -NoRepack
# -> <tmp>/dex\classes.dex  (作为 classes2.dex)

python v11\_zipswap.py 原apk 输出unsigned.apk 打过补丁的classes.dex <tmp>/dex\classes.dex
zipalign -p -f 4 ...
apksigner sign ...
```

`_zipswap.py` 只替换 `classes.dex` / `classes2.dex`，其余条目原样拷贝，
不重新压缩 716MB 资源。
