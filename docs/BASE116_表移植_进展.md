# base.apk（11.6）表移植 · 侦察结论

日期：2026-11-12　目标：用 11.6 的数据表修 11.4 的「渡劫 / 问仙」

## 一、base.apk 是什么

| 项 | 值 |
|---|---|
| 包名 | `com.zmxyol.union.baidu`（百度渠道） |
| 版本 | 11.6.0 / versionCode 1030 |
| 大小 | 743 MB，单 `classes.dex`（7.9 MB）+ `libgame.so` 32.3 MB |
| 是否离线 mod | **不是**。dex 被混淆（`org/yjmobile/IL1Iii/...`），没有 `LocalServer` 之类服务端 |
| libgame.so | ELF32，符号名明文，`.text` 加密（XOR 0xB5 解不出 ARM），`.rodata` 明文 |

明文里 `prayequips` / `crossdisaster` / `probincreasestone` **一个都没有**，"问仙"只出现在一句说明文字里
→ 11.6 的这两个系统跟 11.4 不是同一套协议，**不能靠对拷客户端代码解决**。

## 二、表加密（重要修正）

11.4 / 11.6 的表**都是**：

```
base64(换行可忽略) -> 3DES-EDE3-ECB，key = 24 字节 "1#(z1Rr>" 重复三次
```

`43((zmIp`（8 字节）是**更早版本**的钥匙，对 11.4/11.6 都解不开（会得到乱码）。
`v11\sdb.py` 里用的就是旧的 8 字节钥匙，**要改**。

## 三、表清单差异（11.6 261 张 / 11.4 249 张）

- 11.6 独有 12 张：`ai_pet_6752*`、`ai_pet_9010/9013/9014/9016*`、`mountain_jump_event_config`、`verification_config`
- 11.4 独有 0 张
- 两边同名但内容有差异 30 张

### 字段结构兼容性（30 张逐表比对）

**29 / 30 字段完全一致，可直接移植。**

唯一不兼容：`pet_passive_skill.sdb`
- 11.6 删掉 `talent_cast_rate`，新增 `talent_cast_rate_factor` / `talent_duration_factor` / `talent_skill_factor`
- 11.4 客户端读的是旧字段 → **这张绝对不能换**

## 四、⚠️ 不能整表覆盖的两张表

### 4.1 `equip.sdb`：11.4 有我们打的问仙上限，11.6 是 0

例（id 1308/1506/2308/…）：

| 字段 | 11.4 | 11.6 |
|---|---|---|
| `godpower_max` / `godpower_min` | 4095 | 0 |
| `immunity_max` | 3630 | 0 |
| `critical` / `dodge` / `magDef` | 0.09 | 0（个别 0.03~0.07） |

`4095 / 3630 / 9%` 正是用户给的**十阶问仙上限**，说明 11.4 的 equip 表已经被我们改过。
整表换成 11.6 会把问仙上限**打回 0**。

### 4.2 `magic.sdb`：`godpower_*` / `immunity_*` 是 11.4 独有

这两个字段组在 11.6 的 magic 表里**根本不存在**（11.4 有），同样是我们加的（四仙剑）。

## 五、11.6 的纯增量（可以安全并进去）

- `equip.sdb` 新增 7 个 id：500431 / 500432 / 500611 / 501500 / 501510 / 520250 / 520260
- 行数增量：item +17、loot +14、pet +8、pet_skill +21、level +1、sublevel +1、bullet +135、buff +30 …
- 无 id 被删除（11.4 独有 id 为空）

## 六、结论与下一步

1. **不能整表替换**。正确做法是**增量合并**：
   - 以 11.6 为底 → 补进新增行
   - `equip.sdb` 保留我们已改的 `godpower_max/min`、`immunity_max`、`critical/dodge/magDef` 等字段
   - `magic.sdb` 保留 `godpower_*` / `immunity_*` 字段和四仙剑改动
   - `pet_passive_skill.sdb` 跳过
   - `level.sdb` / `sublevel.sdb` 只并新增行，保留解锁改动
2. **打包源要改**：`v11\build.ps1` 里的 `$srcApk = $root\base(2).apk` **已经不存在**
   （`base(2).apk` 没有，`<tmp>/fix75.apk` 也没了）。
   只有 `<tmp>/fix251.apk`（683 MB，最新 mod）。
   → 用 fix251 当 repack 源，只换表，不要再重编 dex。
3. 两张表补不了协议：**渡劫后属性**是 `fillCrossDisaster` 拼的 item JSON，
   **问仙点不动**是 `gprayequip` 应答链路。表更新后若症状仍在，需要看
   `LocalServer` 的 `CROSSDISASTER` / `gpray` 日志定位。

## 七、本轮生成的侦察脚本

`_probe116.py` `_probe116b.py` `_probe116so.py` `_probe116elf.py` `_tables_cmp.py`
`_port116_check.py` `_probe116sdbkey.py` `_equip_cmp.py` `_find_pray_cfg.py`
