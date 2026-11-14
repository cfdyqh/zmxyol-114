# 8.0 单机版：等级 90 + 跳过全部引导（已生效）

## 做了什么

8.0 的离线服务端（`org.yjmobile.zmxy`）把数据存在
`/data/data/org.yjmobile.zmxy/files/offline/`，都是**明文 JSON**：

| 文件 | 作用 |
|---|---|
| `roles.json` | 角色列表（`{"roles":[{rid,roleid,username,rolelv,roleexp,service}]}`） |
| `profile.json` / `profile_<rid>.json` | 当前选中角色 |
| `savedata.json` / `savedata_<rid>.json` | 该角色的存档（bag / roledata / level / pet） |

直接改写这三个文件即可，不用重编译 APK。

## 改成了什么

1. **等级 90**：`roles.json` 与 `profile_<rid>.json` 里所有角色的 `rolelv` → 90；
   `savedata_<rid>.json` 的 `roledata.role.rolelv` → 90、`roleexp` → 0。
2. **跳过全部引导**：把引导表（`guide_sequence` 35 条 + `guide_step` 164 条）的
   **199 个 id** 全部写进 `roledata.player.completeguide`。
   （`guide_prince_step` 在 8.0 资源里不存在，不用管。）
3. **关卡全解锁**：`level.psl` 写入 45 个关卡（每关 `s=5` 三星），
   `level.mlru` 写入同样的 45 个 id。

## 脚本

- `v11/_v8fix.py <rid>` — 生成改好的存档（读 `apk/assets/database/*.sdb` 取引导/关卡 id）
- `v11/_v8push.py <rid>` — 停进程 → 推回设备 → 修权限

## 验证

进入游戏 → 客户端发 `roledata2` 下载存档（服务器返回改好的那份）→
登录界面左侧角色列表全部显示 **Lv 90**，莲花台角色正常，
进入游戏后没有再被引导卡住，已经能正常在world map 上活动。
