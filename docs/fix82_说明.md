# fix82 说明（2026-11-08 11:5x）

- 清包开关已删除：`/sdcard/yj/clear_bag`、`clear_bag_done` 都已移除，背包不再被强制清空。
- 冰霜末世已直接写入 `true_spirit_bag`：`dataid=9005`、`spiritid=9005`、S 级、字符串字段，客户端真灵面板可显示「真灵·冰霜末世」。
- 邮件第 3 封「【真灵】冰霜末世」：领取时再次写 `true_spirit_bag`，并回 `truespiritinfo`；附件图标用聚元玄晶占位。
- 神秘商店已下架法宝（整张 `magic` 表），四把诛仙剑 500013~500016 不再出现；货架 2149 件，价格仍 1000 灵魂。
- 闪退补丁保留：`Cocos2dxHelper.playEffect` 直接返回 0，不再进 SoundPool.play。
