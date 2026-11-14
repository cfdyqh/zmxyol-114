# 造梦西游OL 11.4 离线版 · 修复笔记与 mod 源码

> 一个 Android 单机（离线）mod 的逆向与修复过程整理：把官服 HTTP 接口换成手机本机的
> `127.0.0.1:8077`，由进程内的 Java 服务应答原版客户端的 JSON 协议。
>
> 本仓库只放**文档、我们自己写的 Java 服务端源码、以及构建/分析脚本**，
> **不含**游戏本体、APK、`libgame.so`、`.sdb` 数据表、截图和日志（见 [NOTICE.md](NOTICE.md)）。

---

## 它在干什么

客户端（`libgame.so`，Cocos2d-x + 自研 YJ* 框架）所有玩法逻辑都在本地，服务端只负责
账号/角色/存档状态。所以离线版的做法是：

1. 把 `libgame.so` 里的官服地址替换成 `htt<path>
2. 在 APK 里塞一个 `classes.dex`，里面 `LocalServer` 起一个 `ServerSocket` 监听 8077；
3. 协议与原版一致：

```
请求 : DES-ECB/PKCS5( {"action":"<name>", "uuid":..., ...} )
应答 : DES-ECB/PKCS5( {"code":1000, "servicetime":<sec>, ...} )
```

`code == 1000` 即成功（`YJNetWorkManager::commonResponseAnalysis`）。
存档 `.sdb` / `savedata_<rid>.json` 是 base64(3DES) 的 JSON（11.4 的密钥与 8.0 不同）。

## 目录结构

```
docs/    修复说明、交接文档、逆向笔记（INDEX.md 有逐篇索引）
src/     离线 mod 的 Java 源码（org/yjmobile/zmxy/offline/*.java）
tools/   构建与打包脚本（javac/d8/zipalign/apksigner、dex 替换、sdb 解密、Ghidra 辅助）
extra/   同一套思路在 8.0 上的移植（至尊传承 / 多存档 / 本命神 90 级）
```

## 几个关键修复（按时间顺序）

| 主题 | 文档 | 一句话结论 |
|---|---|---|
| 至尊传承「比例 0」 | [docs/至尊传承_修复说明_fix256.md](docs/至尊传承_修复说明_fix256.md) | 客户端要的那张 `extremeCnf` 必须**放在 `roledata2` 应答的顶层**；只发规则不够 |
| 传承挂接没实现 | 同上 | 客户端发的 `handleaction` 是 **type 54**（不是官服的 27），要写 `CD`+`CQ` 并回 `update_cg` |
| `CV` 语义搞错 | 同上 | `CV` 是**传承等级上限**（官服恒 100），不是比例 |
| 锻升/聚炼材料 | 同上 | 客户端 `material[].A4` 恒 0，成本得服务端自己算（熔炼丹 / 仙气 / 元灵精魄） |
| 真灵（true_spirit） | [docs/真灵_修复说明_fix259.md](docs/真灵_修复说明_fix259.md) | 字段表对齐官服仍然崩 → 11.4 的渲染路径本身不安全，**默认关闭**（fix260） |
| 8.0 至尊 | [extra/v8-0/README.md](extra/v8-0/README.md) | 根因是服务端存档 `bag` 里**没有 `item` 数组**（邮件附件从没入库），`findItem` 永远找不到装备 |
| 多存档 | 同上 | 每个 rid 一份 `savedata_<rid>.json`；列表按 service 分组；别自动铺职业占位角色 |
| 本命神 90 级 | 同上 | 等级由 `exp` 反推：`lv = floor(sqrt(exp+1))`，90 级 = **exp 8099**，`degree` 上限 89 |

## 构建

需要：

* **JDK 21**（`javac` / `java`）
* **Android SDK build-tools**（`d8`、`zipalign`、`apksigner`）
* Python 3（打包脚本）

```powershell
# 1) 编译 mod 的 java → classes.dex
powershell -ExecutionPolicy Bypass -File tools/build.ps1
# 2) 把 classes.dex 换进底包（改 _packXXX.py 里的路径）
python tools/_pack256.py
# 3) 对齐 + 签名（keystore 自己生成；口令别提交）
zipalign -p -f 4 in.unsigned out.aligned
apksigner sign --ks my.jks --ks-pass pass:*** --out out.apk out.aligned
```

> `tools/build.ps1` 里的 `JAVA_HOME`、SDK 路径、keystore 都是本机路径，使用前先改成你自己的。

## 免责声明

* 本仓库**不包含**游戏本体、资源、二进制库和数据表，也不提供任何成品 APK；
* 所有内容为个人学习/研究逆向的记录，**禁止商用、禁止倒卖**；
* 游戏及其素材版权归原厂商所有；如相关方有异议，请开 issue，会立刻删除。
