package org.yjmobile.zmxy.offline;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;

/**
 * Offline replacement for the 造梦西游OL backend.
 *
 * The client owns all gameplay logic; the server only owns account/role state.
 * libgame.so was patched to talk to htt<path> instead of
 * htt<path> so this class answers the exact
 * same JSON contract:
 *
 *   request  : DES-ECB/PKCS5( {"action":"<name>", "uuid":..., ...} )
 *   response : DES-ECB/PKCS5( {"code":1000, "servicetime":<sec>, ...} )
 *
 * code == 1000 means success (YJNetWorkManager::commonResponseAnalysis).
 */
public final class LocalServer implements Runnable {
    /** Serve the reference build's canned payloads (see Canned.java). */
    /** 灵魂 x20000, in the exact item shape the client serialises (A5 = item id,
     *  A4 = count). Id 503031 comes from item.sdb. */
    private static final String SOUL_ITEM =
        "{\"dataid\":503031,\"A2\":503031,\"A4\":990000,\"A5\":503031,\"A6\":0,\"A9\":8,"
        + "\"AQ\":0,\"AT\":0,\"AV\":0,\"AW\":0.0,\"B7\":0,"
        + "\"BG\":[{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0},"
        + "{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0}]}";

    private static final boolean CANNED = false;
    /** Pre-create a default role instead of showing the create-role screen. */
    private static final boolean AUTO_ROLE = false;

    /** Currency granted in every reply (the client keeps it in bag.spiritcount). */
    private static final int GRANT_SPIRIT = 100000000;

    /** Same text as the startup dialog in NoticeHook; served as the 公告 body. */
    private static final String NOTICE =
            "严禁倒卖，倒卖无姆，禁止外传，仅供个人怀旧使用（本产品完全免费，有能力的支持一下），24小时内删除。";

    /** item.sdb 503031 = 灵魂 (the currency the mystery shop is priced in). */
    private static final int SPIRIT_ITEM_ID = 503031;
    /** 神秘商店统一售价（灵魂）。 */
    private static final int MYST_PRICE = 1000;

    /**
     * 所有「第一形态」宠物（pet.sdb 里 id == base_id 的那些）。
     *
     * 必须走 savedata.pet，不能走商城条目、也不能在 native 里调
     * YJPetManager::randomAddPet()：
     *   - 商城条目由 YJStoreItem::create(id) 走物品表解析，宠物行解析不出来
     *     （列表单元格在 +0xc 解空指针，3.0.2 实测崩过）；
     *   - randomAddPet() 用 createPet(id, NULL, NULL) 建宠物，没有 action manager，
     *     大约 30 秒后 GLThread SIGSEGV。
     * savedata.pet 是客户端自己的恢复路径：
     *   YJPetManager::deserializeFromJsonValue -> YJPetFactory::createPet(value, ...)
     *   -> beCatched(当前角色)，带的是真正的 scheduler / actionManager。
     * 每个条目最小形态就是 {"dataid": <pet id>}，其余属性由工厂按 pet.sdb 的
     * min~max 随机生成，也就是「宝宝品质」。
     *
     * ⚠️ 只能放「客户端注册过构造函数的」宠物 id。
     * YJPetFactory::createPet(Value) @0x6ef558 是这么干的：
     *     snprintf(buf, "YJPet%d", dataid);
     *     在 std::map<string, factory-fn> 里按这个名字查；查不到就 return NULL
     * 而 .rodata 里只有 YJPet210..YJPet236、YJPet238、YJPet239、YJPet244..YJPet247，
     * **没有 240/241/242/243**（青龙太子/青龙王/玄武圣女/玄武女皇 那两条链）。
     * 一旦给了没注册的 id，createPet 返回 NULL →
     * YJPetManager::deserializeFromJsonValue 中途 break、返回 0 →
     * analyzeAndApplyDataToGame 判失败 → 进游戏弹「存档数据已损坏!」。
     */
    private static final int[] GIFT_PETS = {
            229,   // 青龙妖圣（专属 5 技能 + 全部被动，见 petSkillsFor）
            253,   // 青龙邪君（分身带无敌；资质 2000/1600/1600/620）
            217,   // 融合副宠：beast_mix_rule 6820000 的 auxiliary_pet
    };

    /**
     * 龙仔(226) / 青龙妖圣(229)：`patch_sdb.py` 已经把 pet.sdb 里这两行的**资质**上限
     * 抬到 hp 2000 / mp 1400 / str 1400 / def 620（原 800/850/900/400），
     * 和 4.1.1 那次改的数值一致。所以 petJson() 里用 `_max` 取资质。
     */
    private static final int PET_LONGZAI = 226;
    private static final int PET_YAOSHENG = 229;
    /** 青龙邪君 253：分身自带无敌；资质上限单独抬到 2000/1600/1600/620。 */
    private static final int PET_XIEJUN = 253;

    /** `receivereward` 里的奖励来源：37 = 邮件附件（实测报文里就是这个值）。 */
    private static final int REWARD_TYPE_MAIL = 37;

    /**
     * cocos 层第一次启动会把 assets/ 整包**解包到外置目录**
     * （`<externalFilesDir>/assets/...`，见 OfflineInit 里建的那个 assets 目录），
     * 之后一直读那份拷贝 —— 所以只改 APK 里的 assets/database/*.sdb 是不生效的！
     * 这里在起服前把我们打过补丁的表覆盖过去（文件都很小，直接重写）。
     */
    /** 弹窗点「确认」后写这个标记，checkupdate 才会放行。 */
    static void markConfirmed() {
        try {
            File f = new File(dir(), "confirmed.flag");
            FileOutputStream out = new FileOutputStream(f);
            out.write(1);
            out.close();
        } catch (Throwable t) {
            logLine("markConfirmed failed " + t);
        }
    }

    /** 等到玩家点过「确认」为止（最多等 180 秒，避免真的卡死）。 */
    static void awaitConfirmed() {
        File f = new File(dir(), "confirmed.flag");
        for (int i = 0; i < 900 && !f.exists(); i++) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        trace("checkupdate released (confirmed=" + f.exists() + ")");
    }

    /** 给 Java 浮层用的公告文本。 */
    static String noticeText() {
        return NOTICE;
    }

    static void refreshDataTables(Context ctx) {        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) {
                return;
            }
            File db = new File(new File(ext, "assets"), "database");
            if (!db.isDirectory() && !db.mkdirs()) {
                return;
            }
            // 这里必须把 patch_sdb.py 改过的表**全部**列上，漏一个就是“改了没效果”：
            // 客户端只读第一次解包出来的这份外置副本，APK 里的新表不会自动生效。
            // （equip.sdb 之前就漏了 → 凌火剑攻击改了没反应。）
            String[] names = {"pet.sdb", "pet_skill.sdb", "bullet.sdb", "buff.sdb",
                    "level.sdb", "sublevel.sdb", "equip.sdb", "aura_ball.sdb", "sdb.list"};
            for (int i = 0; i < names.length; i++) {
                InputStream in = ctx.getAssets().open("database/" + names[i]);
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                }
                in.close();
                byte[] data = buf.toByteArray();
                File dst = new File(db, names[i]);
                // 一定要按**内容**判断，不能只比长度：改数值（2000→2100、attackType 0→1）
                // 时文件长度一模一样，比长度就会漏掉更新，客户端继续读旧表。
                if (dst.length() == data.length && sameBytes(dst, data)) {
                    continue;
                }
                FileOutputStream out = new FileOutputStream(dst);
                out.write(data);
                out.close();
                Log.i(TAG, "data table refreshed: " + names[i] + " (" + data.length + "B)");
            }
        } catch (Throwable t) {
            Log.i(TAG, "refreshDataTables failed: " + t);
        }
    }

    /** 和目标文件逐字节比较（长度相同时才调用）。 */
    private static boolean sameBytes(File f, byte[] data) {
        java.io.DataInputStream in = null;
        try {
            if (f.length() != data.length) {
                return false;
            }
            in = new java.io.DataInputStream(new java.io.FileInputStream(f));
            for (int i = 0; i < data.length; i++) {
                if (in.read() != (data[i] & 0xff)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 登录奖励：点券 / 灵魂 / 仙气 / 战功 / 冥币 各 99 万（每次"开始游戏"补满）。 */
    private static final int LOGIN_BONUS = 100000000;



    private static final String TAG = "YJOffline";
    public static final int PORT = 8077;
    /** Compile-time kill switch used while bisecting startup crashes. */
    public static final boolean ENABLED = true;

    private static volatile boolean sStarted = false;
    private static Context sCtx;

    public static synchronized void start(Context ctx) {
        if (!ENABLED) {
            Log.i(TAG, "offline server disabled at build time");
            return;
        }
        if (sStarted) {
            return;
        }
        sStarted = true;
        sCtx = ctx.getApplicationContext();
        Thread t = new Thread(new LocalServer(), "yj-offline-server");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "offline server starting on 127.0.0.1:" + PORT);
    }

    // ---------------------------------------------------------------- state

    private static File dir() {
        File d = new File(sCtx.getFilesDir(), "offline");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    /**
     * 当前请求对应的角色 id。v8 每个请求体里都带 "rid"（多角色就是靠它区分），
     * dispatch() 里逐条更新；0/1 时沿用老的单角色文件，保证老存档不用迁移。
     */
    private static volatile int sCurRid = 0;

    /** 当前请求是否来自 13.x 客户端（13.x 只吃原生存档，不能套 11.4 的加料）。 */
    private static volatile boolean sV13 = false;

    private static File saveFile() {
        return new File(dir(), sCurRid > 1 ? ("savedata_" + sCurRid + ".json") : "savedata.json");
    }

    private static File profileFile() {
        return new File(dir(), sCurRid > 1 ? ("profile_" + sCurRid + ".json") : "profile.json");
    }

    private static File rolesFile() {
        return new File(dir(), "roles.json");
    }

    /**
     * 内置多角色：六个职业各留一个档（孙悟空/唐僧/猪八戒/沙僧/猫妖/异界王子），
     * 分别挂在「单机服1..6」上，玩家在登录界面点「服务器」就能换角色，
     * 每个角色的存档各自独立（savedata_<rid>.json）。
     * 已有的角色（老存档）保持不动，只补齐缺的职业。
     */
    private static final int[] PRESET_CLASSES = {1, 2, 3, 4, 5, 6};
    private static final String[] PRESET_NAMES = {"孙悟空", "唐僧", "猪八戒", "沙僧", "猫妖", "异界王子"};

    /** 某个 rid 的存档文件（rid<=1 用老的单角色文件名）。 */
    private static java.io.File saveFileOf(int rid) {
        return new java.io.File(dir(), rid > 1 ? ("savedata_" + rid + ".json") : "savedata.json");
    }

    private static java.io.File profileFileOf(int rid) {
        return new java.io.File(dir(), rid > 1 ? ("profile_" + rid + ".json") : "profile.json");
    }

    /** 读某个 rid 的存档，不存在或损坏返回 null。 */
    private static JSONObject readSaveObjOf(int rid) {
        try {
            java.io.File f = saveFileOf(rid);
            if (!f.exists() || f.length() < 2) {
                return null;
            }
            return new JSONObject(new String(readAll(new java.io.FileInputStream(f)), "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 当前存档用的版本号。
     *
     * /sdcard/yj/login_enterserver.json 那份模板里写的是 **16.8.2**（那是 16.8 的
     * 客户端导出的），直接拿去给新角色当底，客户端 11.4.0 一读就判定
     * 「上仙版本过低，请更新至新版本!」。所以新档一律套用本机真实存档里的版本号。
     */
    private static String currentSaveVersion() {
        try {
            JSONArray roles = readRoles();
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                JSONObject sd = readSaveObjOf(r.optInt("rid", 0));
                JSONObject rd = sd == null ? null : sd.optJSONObject("roledata");
                String ver = rd == null ? null : rd.optString("appversion", null);
                if (ver != null && ver.length() > 0) {
                    return ver;
                }
            }
        } catch (Throwable ignored) {
        }
        return "11.4.0";
    }

    /**
     * 找一份**本机客户端自己生成的**存档当参照（一般是等级最高的主号）。
     *
     * 千万别用 /sdcard/yj/login_enterserver.json 那份 —— 它是 16.8.2 导出的，
     * 里面的 fate_god_raise_degree_cost / true_spirit_bag / firstpass 等块
     * 结构和 11.4 不一样，塞进新角色的档里会让客户端判「存档数据已损坏!」。
     */
    private static JSONObject referenceSave(int exceptRid) {
        try {
            JSONArray roles = readRoles();
            JSONObject best = null;
            int bestLv = -1;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                int rid = r.optInt("rid", 0);
                if (rid == exceptRid) {
                    continue;
                }
                JSONObject sd = readSaveObjOf(rid);
                if (sd == null) {
                    continue;
                }
                int lv = r.optInt("rolelv", 0);
                if (lv > bestLv) {
                    bestLv = lv;
                    best = sd;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把新角色存档里**客户端没上传的结构块**补齐，缺一块就会「存档数据已损坏!」。
     *
     * 实测 diff（主号存档 vs 新建嫦娥存档）缺的是：
     *   roledata.role.roleskill.initiative_skill   ← 技能栏（整个 key 都没有）
     *   roledata.role.fategod.id / exp / skills
     *   level.mlru / level.phfiel
     *   prayequips / saled_equips_list / bag.item
     * 结构和类型都跟客户端自己的存档一致，所以从主号（或任意一份真实存档）里借，
     * 但只借**结构**，不借数值（职业、等级、背包、关卡进度都保留新角色自己的）。
     */
    private static void completeNewSave(JSONObject store, int rid) {
        try {
            JSONObject ref = referenceSave(rid);
            if (ref == null) {
                return;
            }
            JSONObject rd = store.optJSONObject("roledata");
            if (rd == null) {
                rd = new JSONObject();
                store.put("roledata", rd);
            }
            JSONObject role = rd.optJSONObject("role");
            if (role == null) {
                role = new JSONObject();
                rd.put("role", role);
            }
            JSONObject refRd = ref.optJSONObject("roledata");
            JSONObject refRole = refRd == null ? null : refRd.optJSONObject("role");

            // 技能栏
            JSONObject rsk = role.optJSONObject("roleskill");
            if (rsk == null) {
                rsk = new JSONObject();
                role.put("roleskill", rsk);
            }
            JSONArray ini = rsk.optJSONArray("initiative_skill");
            if (ini == null || ini.length() == 0) {
                JSONObject rrsk = refRole == null ? null : refRole.optJSONObject("roleskill");
                JSONArray refIni = rrsk == null ? null : rrsk.optJSONArray("initiative_skill");
                if (refIni != null) {
                    rsk.put("initiative_skill", new JSONArray(refIni.toString()));
                }
            }
            if (rsk.optJSONArray("passive_skill") == null) {
                JSONObject rrsk = refRole == null ? null : refRole.optJSONObject("roleskill");
                JSONArray refPas = rrsk == null ? null : rrsk.optJSONArray("passive_skill");
                if (refPas != null) {
                    rsk.put("passive_skill", new JSONArray(refPas.toString()));
                }
            }

            // 唤神（fategod）
            JSONObject fg = role.optJSONObject("fategod");
            if (fg == null || !fg.has("id")) {
                JSONObject refFg = refRole == null ? null : refRole.optJSONObject("fategod");
                if (refFg != null) {
                    role.put("fategod", new JSONObject(refFg.toString()));
                }
            }

            // level 结构
            JSONObject lv = store.optJSONObject("level");
            JSONObject refLv = ref.optJSONObject("level");
            if (lv == null) {
                lv = new JSONObject();
                store.put("level", lv);
            }
            if (!lv.has("psl")) {
                lv.put("psl", new JSONArray());
            }
            if (refLv != null) {
                java.util.Iterator<String> it = refLv.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!lv.has(k) && !"psl".equals(k)) {
                        lv.put(k, refLv.get(k));
                    }
                }
            }

            // 顶层缺的块
            String[] topKeys = {"prayequips", "saled_equips_list", "equips", "mire",
                    "eggdata", "interaction_data", "true_spirit_bag", "pet", "point"};
            for (int i = 0; i < topKeys.length; i++) {
                if (!store.has(topKeys[i]) && ref.has(topKeys[i])) {
                    store.put(topKeys[i], ref.get(topKeys[i]));
                }
            }
        } catch (Throwable t) {
            trace("completeNewSave failed " + t);
        }
    }

    /** 把新档的版本号/来源字段改成和本机客户端一致，否则会弹「上仙版本过低」。 */
    private static void fixSaveVersion(JSONObject sd) {        try {
            if (sd == null) {
                return;
            }
            String ver = currentSaveVersion();
            JSONObject rd = sd.optJSONObject("roledata");
            if (rd == null) {
                rd = new JSONObject();
                sd.put("roledata", rd);
            }
            rd.put("appversion", ver);
            rd.put("saveversion", ver);
            if (!sd.has("is_saved_from")) {
                sd.put("is_saved_from", 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void ensurePresetRoles() {
        // 2026-11-09：**不再裁剪角色**。
        // 以前这里为了绕开「多角色进游戏 GL 线程崩」把所有角色删到只剩 1 个，
        // 结果玩家在游戏里新建的角色，一退出登录就被删掉 —— 表现就是
        // 「新建的存档不保存，只剩孙悟空那一个档」。
        // 现在只做两件事：
        //   ① 每个角色都必须有一份可用的存档（缺了就复制一份，空档进游戏会崩）；
        //   ② roles.json 里的等级以存档里的真实等级为准（否则客户端会拿列表的
        //      等级去覆盖存档 → 每次登录等级变 1）。
        try {
            JSONArray roles = readRoles();
            if (roles == null || roles.length() == 0) {
                return;
            }
            boolean rolesChanged = false;
            // 官服是「一个服里多个角色」，所以不再按 service 拆开。
            // 只把明显异常的 service（<=0）归到 1 服。
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r != null && r.optInt("service", 1) <= 0) {
                    r.put("service", 1);
                    rolesChanged = true;
                }
            }
            // 先找一个「有存档」的角色做模板
            int templateRid = 0;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r != null && readSaveObjOf(r.optInt("rid", 0)) != null) {
                    templateRid = r.optInt("rid", 0);
                    break;
                }
            }
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                int rid = r.optInt("rid", 0);
                if (rid <= 0) {
                    continue;
                }
                JSONObject sd = readSaveObjOf(rid);
                if (sd == null && templateRid > 0 && templateRid != rid) {
                    // 没有存档的角色：只有在「职业和模板一样」时才拿模板复制一份，
                    // 否则会把这套职业的技能/属性塞进别的职业，进游戏弹
                    // 「存档数据已损坏!」。职业不同就留空，让客户端自己建。
                    int tplCls = 0;
                    for (int k = 0; k < roles.length(); k++) {
                        JSONObject tr = roles.optJSONObject(k);
                        if (tr != null && tr.optInt("rid", 0) == templateRid) {
                            tplCls = tr.optInt("roleid", 0);
                            break;
                        }
                    }
                    JSONObject tpl = null;
                    if (tplCls > 0 && tplCls == r.optInt("roleid", 0)) {
                        tpl = templateSave();
                        if (tpl == null) {
                            tpl = readSaveObjOf(templateRid);
                        }
                    }
                    if (tpl != null) {
                        try {
                            JSONObject copy = new JSONObject(tpl.toString());
                            JSONObject rd = copy.optJSONObject("roledata");
                            JSONObject role = rd == null ? null : rd.optJSONObject("role");
                            if (role != null) {
                                int lv = r.optInt("rolelv", 1);
                                role.put("rolelv", lv > 0 ? lv : 1);
                                role.put("roleexp", 0);
                            }
                            JSONObject pl = rd == null ? null : rd.optJSONObject("player");
                            if (pl != null) {
                                pl.put("roleid", r.optInt("roleid", 1));
                            }
                            // 模板是 16.8.2 的，必须把版本改成本机客户端的版本，
                            // 否则进游戏弹「上仙版本过低，请更新至新版本!」
                            fixSaveVersion(copy);
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(saveFileOf(rid));
                            fo.write(copy.toString().getBytes("UTF-8"));
                            fo.close();
                            sd = copy;
                            trace("seeded save for rid=" + rid);
                        } catch (Throwable t) {
                            trace("seed save failed rid=" + rid + " " + t);
                        }
                    }
                }
                if (sd == null) {
                    continue;
                }
                JSONObject rd = sd.optJSONObject("roledata");
                JSONObject role = rd == null ? null : rd.optJSONObject("role");
                int lv = role == null ? 0 : role.optInt("rolelv", 0);
                if (lv > 0 && r.optInt("rolelv", -1) != lv) {
                    r.put("rolelv", lv);
                    r.put("rolelevel", lv);
                    rolesChanged = true;
                }
                if (profileFileOf(rid).exists() == false) {
                    try {
                        JSONObject p = new JSONObject();
                        p.put("rid", rid);
                        p.put("roleid", r.optInt("roleid", 1));
                        p.put("username", r.optString("username", "玩家"));
                        p.put("rolelv", lv > 0 ? lv : 1);
                        p.put("roleexp", r.optInt("roleexp", 0));
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(profileFileOf(rid));
                        fo.write(Des.bytes(p.toString()));
                        fo.close();
                        trace("seeded profile for rid=" + rid);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (rolesChanged) {
                writeRoles(roles);
                trace("roles level synced from saves");
            }
        } catch (Throwable t) {
            trace("ensurePresetRoles failed " + t);
        }
    }

    /** 角色等级上限：用户要求所有角色一律 1 级（B 方案：主号也降到 1 级）。 */
    private static final int MAX_ROLE_LV = 90;   // 仅用于「新建角色」的初始等级

    /** 把一份存档里的角色等级/经验清成 1 级 0 经验（给预建新档用）。 */
    private static void resetRoleLevel(JSONObject sd) {
        try {
            JSONObject rd = sd.optJSONObject("roledata");
            JSONObject role = rd == null ? null : rd.optJSONObject("role");
            if (role != null) {
                role.put("rolelv", MAX_ROLE_LV);
                role.put("roleexp", 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把 roles.json 里每个角色的等级写成 90、经验清零。
     *
     * @param persist true 时落盘
     * @return 是否改动过
     */
    private static boolean forceMaxRoleLevel(JSONArray roles, boolean persist) {
        if (roles == null) {
            return false;
        }
        boolean changed = false;
        try {
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                if (r.optInt("rolelv", 1) != MAX_ROLE_LV) {
                    r.put("rolelv", MAX_ROLE_LV);
                    r.put("rolelevel", MAX_ROLE_LV);
                    changed = true;
                }
                if (r.optInt("roleexp", 0) != 0) {
                    r.put("roleexp", 0);
                    changed = true;
                }
            }
            if (changed && persist) {
                writeRoles(roles);
            }
        } catch (Throwable t) {
            trace("forceMaxRoleLevel failed " + t);
        }
        return changed;
    }

    /**
     * 「新建角色」开关：开局公告里的按钮会把它置上，下次登录时我们故意回一个
     * 空的 rolelist，客户端就会走它自己的创建角色流程；createrole 一到就清掉。
     * （v8 的登录界面没有 v14 那个「+」按钮，所以只能这样开新角色。）
     */
    private static File newRoleFlag() {
        return new File(dir(), "newrole.flag");
    }

    /**
     * 已废弃：现在登录界面直接显示多角色列表（客户端自带），不再需要
     * 「新建角色」模式。保留方法只为兼容旧调用点，永远返回 false，
     * 顺便把历史遗留的 flag 文件清掉。
     */
    static boolean wantNewRole() {
        try {
            File f = newRoleFlag();
            if (f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static void setWantNewRole(boolean on) {
        try {
            if (on) {
                FileOutputStream fo = new FileOutputStream(newRoleFlag());
                fo.write(1);
                fo.close();
            } else {
                File f = newRoleFlag();
                if (f.exists()) {
                    f.delete();
                }
            }
        } catch (Throwable t) {
            trace("setWantNewRole failed " + t);
        }
    }

    /**
     * 切到下一个角色（多开存档用）。返回 "名字(LvX)" 方便 toast 提示；
     * 没有角色或只有一个时返回 null。
     */
    static String cycleRole() {
        try {
            JSONArray roles = readRoles();
            if (roles.length() < 2) {
                return null;
            }
            int cur = lastRid();
            int idx = -1;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r != null && r.optInt("rid", 0) == cur) {
                    idx = i;
                    break;
                }
            }
            int next = (idx + 1) % roles.length();
            JSONObject r = roles.optJSONObject(next);
            if (r == null) {
                return null;
            }
            int rid = r.optInt("rid", 1);
            sCurRid = rid;
            FileOutputStream lo = new FileOutputStream(new File(dir(), "lastrid.txt"));
            lo.write(String.valueOf(rid).getBytes("UTF-8"));
            lo.close();
            trace("cycleRole: -> rid=" + rid);
            return r.optString("username", "角色") + " Lv" + r.optInt("rolelv", 1);
        } catch (Throwable t) {
            trace("cycleRole failed " + t);
            return null;
        }
    }

    /** 上次进游戏的角色 id（登录/服务器列表这些不带 rid 的请求用它）。 */
    private static int lastRid() {
        try {
            File lr = new File(dir(), "lastrid.txt");
            if (lr.exists()) {
                int rid = Integer.parseInt(new String(readAll(lr), "UTF-8").trim());
                if (rid > 0) {
                    return rid;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            JSONArray roles = readRoles();
            if (roles.length() > 0) {
                JSONObject r = roles.optJSONObject(0);
                if (r != null) {
                    return r.optInt("rid", 1);
                }
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private static synchronized JSONObject readProfileFrom(File f) {
        try {
            if (f == null || !f.exists()) {
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            if (sb.length() < 2) {
                return null;
            }
            return new JSONObject(sb.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The character behind the current request (rid), or null if none yet. */
    private static synchronized JSONObject readProfile() {
        return readProfileFrom(profileFile());
    }

    private static synchronized void writeProfile(JSONObject p) {
        try {
            FileOutputStream fo = new FileOutputStream(profileFile());
            fo.write(Des.bytes(p.toString()));
            fo.close();
        } catch (Throwable t) {
            trace("writeProfile failed " + t);
        }
    }

    /**
     * 所有角色的一览表（roles.json）。老版本只存了一个 profile.json，
     * 第一次读的时候把它迁移成 roles.json，不动原来的文件。
     */
    private static synchronized JSONArray readRoles() {
        try {
            File f = rolesFile();
            if (f.exists()) {
                String s = Des.utf8(readAll(f));
                if (s != null && s.length() > 1) {
                    JSONArray a = new JSONObject(s).optJSONArray("roles");
                    if (a != null) {
                        return a;
                    }
                }
            }
        } catch (Throwable t) {
            trace("readRoles failed " + t);
        }
        JSONArray a = new JSONArray();
        try {
            JSONObject legacy = readProfileFrom(new File(dir(), "profile.json"));
            if (legacy != null) {
                a.put(roleRecord(legacy.optInt("rid", 1), legacy.optInt("roleid", 1),
                        legacy.optString("username", "玩家"), legacy.optInt("rolelv", 1),
                        legacy.optInt("roleexp", 0), 1));
                writeRoles(a);
                trace("roles.json migrated from profile.json rid=" + legacy.optInt("rid", 1));
            }
        } catch (Throwable t) {
            trace("roles migrate failed " + t);
        }
        return a;
    }

    private static synchronized void writeRoles(JSONArray a) {
        try {
            JSONObject o = new JSONObject();
            o.put("roles", a);
            FileOutputStream fo = new FileOutputStream(rolesFile());
            fo.write(Des.bytes(o.toString()));
            fo.close();
        } catch (Throwable t) {
            trace("writeRoles failed " + t);
        }
    }

    private static JSONObject roleRecord(int rid, int roleid, String name, int lv, int exp, int service) {
        JSONObject r = new JSONObject();
        try {
            r.put("rid", rid);
            r.put("roleid", roleid);
            r.put("username", name);
            r.put("rolelv", lv);
            r.put("roleexp", exp);
            r.put("service", service);
        } catch (Throwable ignored) {
        }
        return r;
    }

    /** 把 roles.json 里某个 rid 的记录同步成当前等级/经验。 */
    private static void touchRole(int rid, int roleid, String name, int lv, int exp) {
        if (rid <= 0) {
            return;
        }
        try {
            JSONArray roles = readRoles();
            boolean found = false;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r == null || r.optInt("rid", 0) != rid) {
                    continue;
                }
                r.put("rolelv", Math.max(lv, r.optInt("rolelv", 1)));
                r.put("roleexp", Math.max(exp, r.optInt("roleexp", 0)));
                if (roleid > 0) {
                    r.put("roleid", roleid);
                }

                found = true;
                break;
            }
            if (!found) {
                roles.put(roleRecord(rid, roleid, name, lv, exp, 1));
            }
            writeRoles(roles);
        } catch (Throwable t) {
            trace("touchRole failed " + t);
        }
    }

    /** 平板没有 root，/data/data 读不到；同一份日志再镜像到外部目录，adb 就能 pull。 */
    private static File extDir() {
        try {
            File ext = sCtx.getExternalFilesDir(null);
            if (ext == null) {
                return null;
            }
            File d = new File(ext, "offline");
            if (!d.exists() && !d.mkdirs()) {
                return null;
            }
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized void logLine(String s) {
        try {
            File f = new File(dir(), "protocol.log");
            if (f.length() > 512 * 1024) {
                File old = new File(dir(), "protocol.old.log");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
            // NOTE: RandomAccessFile only accepts r/rw/rws/rwd - "a" throws.
            byte[] line = Des.bytes(String.valueOf(System.currentTimeMillis()) + " " + s + "\n");
            FileOutputStream fo = new FileOutputStream(f, true);
            fo.write(line);
            fo.close();
            File ext = extDir();
            if (ext != null) {
                File ef = new File(ext, "protocol.log");
                if (ef.length() > 512 * 1024) {
                    File old = new File(ext, "protocol.old.log");
                    if (old.exists()) {
                        old.delete();
                    }
                    ef.renameTo(old);
                }
                FileOutputStream eo = new FileOutputStream(ef, true);
                eo.write(line);
                eo.close();
            }
        } catch (Throwable t) {
            // logging must never break the server
        }
    }

    private static synchronized String readSave() {
        try {
            File f = saveFile();
            if (!f.exists()) {
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized void writeSave(String json) {
        try {
            File f = saveFile();
            File tmp = new File(dir(), "savedata.tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(Des.bytes(json));
            o.close();
            if (f.exists()) {
                f.delete();
            }
            tmp.renameTo(f);
        } catch (Throwable t) {
            logLine("WRITE SAVE FAILED " + t);
        }
    }

    // --------------------------------------------------------------- server

    @Override
    public void run() {
        ServerSocket ss = null;
        for (int attempt = 0; attempt < 40 && ss == null; attempt++) {
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 64);
            } catch (Throwable t) {
                ss = null;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (ss == null) {
            Log.e(TAG, "cannot bind loopback:" + PORT);
            return;
        }
        Log.i(TAG, "offline server listening");
        while (true) {
            try {
                Socket s = ss.accept();
                // One thread per connection. This used to be a single accept loop
                // calling handle() inline, so an idle socket (the client and the
                // TPNS SDK both leave them open, some only send a lone "P") blocked
                // the next real request for the whole read timeout. Measured with
                // v411/probe_time.py: first request 15 s, second 6 s, then 0 s.
                Thread t = new Thread(new ConnHandler(this, s), "yjconn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                // keep serving
            }
        }
    }

    /** Reads /sdcard/yj/&lt;key&gt;.json verbatim, or null when absent/empty. */
    private static String readOverride(String key) {
        try {
            java.io.File ov = new java.io.File("/sdcard/yj/" + key + ".json");
            if (!ov.exists() || ov.length() <= 0) {
                return null;
            }
            byte[] raw = new byte[(int) ov.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(ov);
            try {
                int off = 0;
                while (off < raw.length) {
                    int got = in.read(raw, off, raw.length - off);
                    if (got < 0) {
                        break;
                    }
                    off += got;
                }
            } finally {
                in.close();
            }
            return new String(raw, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 一份「完整形状」的 1 级人物存档，用来给新角色打底。
     *
     * /sdcard/yj/login_enterserver.json（以及 login.json）里的 savedata 就是
     * 客户端认得的完整形状（bag/level/pet/firstpass/roledata…）。新角色只拿到
     * bag+roledata 的话，进游戏会因为空 psl 崩溃或被判「存档数据已损坏」。
     */
    private static JSONObject templateSave() {
        String[] keys = {"login_enterserver", "login"};
        for (int i = 0; i < keys.length; i++) {
            String s = readOverride(keys[i]);
            if (s == null) {
                continue;
            }
            try {
                JSONObject o = new JSONObject(s);
                JSONObject sd = o.optJSONObject("savedata");
                if (sd != null && sd.length() > 0) {
                    return sd;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Mirror protocol activity into logcat; the first N requests only. */
    private static void trace(String msg) {
        if (sTraceLeft > 0) {
            sTraceLeft--;
            Log.i(TAG, msg);
        }
    }

    private static int sTraceLeft = 200;

    /** Raw-capture mode for the 4.1.1 port (codec not yet known). */
    private static final boolean sCapture =
            new java.io.File("/sdcard/zmxy_capture.on").exists();

    private void captureRaw(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        try {
            s.setSoTimeout(1500);
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                all.write(b, 0, n);
            }
        } catch (Throwable ignored) {
        }
        byte[] raw = all.toByteArray();
        StringBuilder hex = new StringBuilder();
        StringBuilder txt = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            hex.append(String.format("%02x", raw[i] & 0xff));
            char ch = (char) (raw[i] & 0xff);
            txt.append(ch >= 0x20 && ch < 0x7f ? ch : '.');
        }
        trace("RAW " + raw.length + " bytes: " + hex);
        trace("RAW-TXT " + txt);
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(
                    "/sdcard/zmxy_capture.log", true);
            o.write(Des.bytes("=== " + raw.length + " bytes\n" + txt + "\n" + hex + "\n"));
            o.close();
        } catch (Throwable ignored) {
        }
        java.io.OutputStream os = s.getOutputStream();
        os.write(Des.bytes("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
        os.flush();
    }

    /** True once the client actually asked for the role list (i.e. it got past the splash). */
    private static volatile boolean sSawLogin = false;

    static boolean sawLogin() {
        return sSawLogin;
    }

    /**
     * What the 4399 SDK gets instead of the (now dead) real endpoint.
     *
     * Must mirror the real server's CURRENT answer, which is a failure:
     *   {"code":"99","result":null,"message":"服务已关闭"}
     * Returning a fake "success" instead makes the SDK invoke
     * OnInitFinishedListener with a NULL list, and the game's wrapper
     * (org.yjmobile.lib.a.a) then dereferences it:
     *   NullPointerException: List.iterator() on a null object reference -> crash.
     * The failure path, on the other hand, is handled.
     */
    private static final String SDK_OK =
            "{\"code\":\"99\",\"result\":null,\"message\":\"服务已关闭\"}";

    /** Serves one connection; called from ConnHandler on its own thread. */
    void serve(Socket s) throws Exception {
        // Loopback-only traffic: a request line is there within milliseconds, so a
        // short idle timeout is enough. It no longer blocks other connections.
        s.setSoTimeout(8000);
        // 4.1.1 discovery mode: dump the raw request (its codec is still unknown).
        // Enabled by creating /sdcard/zmxy_capture.on
        if (sCapture) {
            captureRaw(s);
            return;
        }
        InputStream in = s.getInputStream();
        String requestLine = readLine(in);
        if (requestLine == null) {
            return;
        }
        trace("REQ-LINE " + requestLine);
        int contentLength = 0;
        boolean expectContinue = false;
        String h;
        StringBuilder hdr = new StringBuilder();
        while ((h = readLine(in)) != null && h.length() > 0) {
            hdr.append(h).append(" | ");
            int c = h.indexOf(':');
            if (c > 0) {
                String k = h.substring(0, c).trim();
                String v = h.substring(c + 1).trim();
                if (k.equalsIgnoreCase("content-length")) {
                    contentLength = Integer.parseInt(v);
                } else if (k.equalsIgnoreCase("expect") && v.toLowerCase().indexOf("100-continue") >= 0) {
                    expectContinue = true;
                }
            }
        }
        if (expectContinue) {
            // libcurl adds Expect: 100-continue for bodies > 1 KiB and blocks until answered.
            OutputStream pre = s.getOutputStream();
            pre.write(Des.bytes("HTTP/1.1 100 Continue\r\n\r\n"));
            pre.flush();
        }
        byte[] body = new byte[contentLength];
        int off = 0;
        while (off < contentLength) {
            int n = in.read(body, off, contentLength - off);
            if (n <= 0) {
                break;
            }
            off += n;
        }

        String path = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "";

        // ---- 4399 OperateCenter SDK --------------------------------------
        // The SDK's config API is dead ("服务已关闭"), and depending on the device
        // its init never calls back, which wedges the splash at "初始化中".
        // OfflineInit installs a JVM proxy pointing here, so these arrive as
        // absolute-form requests ("GET htt<path> HTTP/1.1") and
        // are plain HTTP - never DES.
        if (path.startsWith("htt<path>") || path.startsWith("http<path>")
                || path.startsWith("/openapi/") || path.startsWith("http:")) {
            // Absolute-form means the JVM proxy or the port-80 REDIRECT delivered a
            // request that was NOT meant for us: log everything so the caller can be
            // identified (host header, body length, user agent).
            String hdrs = hdr.toString();
            trace("SDKREQ " + requestLine + " ## " + (hdrs.length() > 300 ? hdrs.substring(0, 300) : hdrs)
                    + " ## body=" + contentLength);
            // Two different jobs here:
            //  * the dead 4399 endpoints of the 3.0.2 build - answer with the real
            //    server's failure payload (see SDK_OK);
            //  * everything else the game's SDKs try to reach (Tencent egame, TPNS,
            //    umeng) - those are alive but unreachable from a blocked network,
            //    and a hanging connect stalls startup for 20s+ (observed: SYN-SENT
            //    to 125.39.240.55:443 / 58.251.139.182:443, then ANR).
            //    Fail them FAST with 502 + close so the client falls through.
            boolean dead4399 = path.contains("4399api.net")
                    || path.contains("4399api.com")   // 4.1.1 uses this host
                    || path.contains("4399.com");
            if (!dead4399) {
                if (body != null && body.length > 0) {
                    StringBuilder bh = new StringBuilder();
                    int lim = Math.min(body.length, 160);
                    for (int i = 0; i < lim; i++) {
                        bh.append(String.format("%02x", body[i] & 0xff));
                    }
                    trace("SDK-BODY " + requestLine + " " + bh);
                }
                trace("SDK-FAIL " + requestLine);
                OutputStream osf = s.getOutputStream();
                osf.write(Des.bytes("HTTP/1.1 502 Bad Gateway\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n"));
                osf.flush();
                return;
            }
            byte[] fake = Des.bytes(SDK_OK);
            trace("SDK " + requestLine);
            logLine("SDK " + requestLine);
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            hs.write(Des.bytes("HTTP/1.1 200 OK\r\n"));
            hs.write(Des.bytes("Content-Type: application/json\r\n"));
            hs.write(Des.bytes("Content-Length: " + fake.length + "\r\n"));
            hs.write(Des.bytes("Connection: close\r\n\r\n"));
            OutputStream os0 = s.getOutputStream();
            os0.write(hs.toByteArray());
            os0.write(fake);
            os0.flush();
            return;
        }

        byte[] respBytes;
        try {
            String plain = Des.utf8(Des.decrypt(body));
            logLine("REQ " + path + " " + plain);
            trace("BODY " + (plain.length() > 400 ? plain.substring(0, 400) : plain));
            String out = dispatch(plain);
            // Currency grant: the client keeps spirit/point/money inside the bag
            // block it uploads, so rewriting them in every reply is enough.
            out = out.replace("\"spiritcount\":0", "\"spiritcount\":" + GRANT_SPIRIT)
                     .replace("\"spiritcount\": 0", "\"spiritcount\":" + GRANT_SPIRIT)
                     .replace("\"spirit\":0", "\"spirit\":" + GRANT_SPIRIT)
                     .replace("\"aura\":0", "\"aura\":" + GRANT_SPIRIT)
                     .replace("\"exploits\":0", "\"exploits\":" + GRANT_SPIRIT)
                     .replace("\"point\":0", "\"point\":" + GRANT_SPIRIT)
                     .replace("\"money\":0", "\"money\":" + GRANT_SPIRIT);
            out = grantCurrencies(out);
            logLine("RESP " + out);
            trace("RESP " + (out.length() > 400 ? out.substring(0, 400) : out));
            respBytes = Des.encrypt(Des.bytes(out));
        } catch (Throwable t) {
            logLine("ERR " + t);
            trace("ERR " + t);
            try {
                respBytes = Des.encrypt(Des.bytes("{\"code\":1000}"));
            } catch (Throwable t2) {
                respBytes = new byte[0];
            }
        }

        ByteArrayOutputStream hd = new ByteArrayOutputStream();
        hd.write(Des.bytes("HTTP/1.1 200 OK\r\n"));
        hd.write(Des.bytes("Content-Type: application/json\r\n"));
        hd.write(Des.bytes("Content-Length: " + respBytes.length + "\r\n"));
        hd.write(Des.bytes("Connection: close\r\n\r\n"));
        OutputStream os = s.getOutputStream();
        os.write(hd.toByteArray());
        os.write(respBytes);
        os.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
            if (b.size() > 8192) {
                break;
            }
        }
        if (c == -1 && b.size() == 0) {
            return null;
        }
        return new String(b.toByteArray(), "UTF-8");
    }

    // ------------------------------------------------------------ dispatch

    private static String dispatch(String plain) throws Exception {
        try {
            if (plain != null && plain.contains("\"action\":\"login\"")) {
                sSawLogin = true;
            }
        } catch (Throwable ignored) {
        }
        JSONObject req = new JSONObject(plain);
        String action = req.optString("action", "");
        sV13 = isV13(req);
        // v8 每个请求都带 rid；多角色存档就是按它分文件的。
        int reqRid = req.optInt("rid", 0);
        if (reqRid <= 0) {
            reqRid = lastRid();
        }
        if (reqRid > 0 && reqRid != sCurRid) {
            sCurRid = reqRid;
            try {
                FileOutputStream lo = new FileOutputStream(new File(dir(), "lastrid.txt"));
                lo.write(String.valueOf(reqRid).getBytes("UTF-8"));
                lo.close();
            } catch (Throwable ignored) {
            }
        }

        // ---- dev override: /sdcard/yj/<action>.json is served verbatim.
        // Lets us iterate the v8 wire contract without rebuilding the 300MB APK.
        //
        // 11.4 用同一个 action "login" 承载三个阶段，必须分开应答，否则
        // 「服务器信息」和「进服」会拿到同一份 body，登录场景状态机就卡在
        // 「有角色」分支上（橙色牌匾不显示「创建角色」、莲花台不出人）。
        String[] ovKeys;
        if ("login".equals(action)) {
            boolean fs = req.optInt("forserverinfo", 0) != 0;
            boolean hasCname = req.optString("cname", "").length() > 0;
            String sub = fs ? (hasCname ? "login_enterserver" : "login_serverinfo")
                            : "login_plain";
            ovKeys = new String[] { sub, "login" };
            // 「第二次登录送四剑」：只在「选好角色进服」这一阶段计数，
            // 一次启动只会走到这里一次。
            if ("login_enterserver".equals(sub)) {
                bumpLoginCount();
            }
        } else {
            ovKeys = new String[] { action };
        }
        for (String key : ovKeys) {
            String hit = readOverride(key);
            if (hit != null) {
                trace("OVERRIDE " + action + (key.equals(action) ? "" : " [" + key + "]"));
                // 模板里的数据是 1 级新号的（rolelist.rolelv=1、savedata 生命80），
                // 直接用会让登录界面显示 Lv 1，并被客户端同步回存档。
                // 这里换成当前存档的真实数据（形状仍然照抄模板）。
                if ("login".equals(action)) {
                    try {
                        hit = personalizeLogin(hit, req);
                    } catch (Throwable t) {
                        trace("personalizeLogin failed " + t);
                    }
                }
                return hit;
            }
        }

        // ---- 存档备份/恢复的调试端点（同样走 DES 传输，只在本机 127.0.0.1 上）
        //   {"action":"_save_pull"}                    → {"savedata": <整份存档>}
        //   {"action":"_save_push","savedata":{...}}   → {"ok":1,"len":N}
        // 手机非 root、app 不可调试，改坏了存档就没法救；有了这两个端点，
        // 调参前先 _save_pull 存一份，出事 _save_push 推回去。
        if (action.startsWith("_save_")) {
            JSONObject dbg = new JSONObject();
            dbg.put("code", 1000);
            if ("_save_pull".equals(action)) {
                JSONObject st = readSaveObj();
                dbg.put("savedata", st);
                dbg.put("len", st.toString().length());
            } else if ("_save_push".equals(action)) {
                Object sdIn = req.opt("savedata");
                if (sdIn instanceof JSONObject) {
                    String txt = ((JSONObject) sdIn).toString();
                    writeSave(txt);
                    dbg.put("ok", 1);
                    dbg.put("len", txt.length());
                } else {
                    dbg.put("ok", 0);
                    dbg.put("msg", "savedata must be an object");
                }
            } else {
                dbg.put("ok", 0);
                dbg.put("msg", "unknown _save_ action");
            }
            return dbg.toString();
        }

        // Serve the reference build's payloads verbatim: the client's own
        // handlers then create skills/levels/items through their normal paths.
        // Persist any uploaded character state BEFORE anything can return early.
        //
        // This is what makes level progress survive: the only request that
        // carries savedata.level is the level-clear upload ("lvvictory",
        // {"level":{"phfiel":0,"psl":[{"id":1,"s":5}]}, "bag":..., "roledata":...}),
        // and that action is answered from Canned.VICTORY further down - so a
        // storeSaveUpload() placed after those returns never ran for it, and the
        // cleared sublevels were thrown away on every victory.
        Object persisted = req.opt("savedata");
        // ⚠️ createrole2 的 savedata 是**新角色**的初始档，此时 rid 还是上一个角色；
        // 如果走这里，会把新角色的 roledata（职业/本命神/技能）合并进旧角色，
        // 旧角色的职业和主动技能就被污染了。新角色由下面的 createrole 分支单独落盘。
        if (persisted instanceof JSONObject && !action.startsWith("createrole")) {
            storeSaveUpload((JSONObject) persisted);
            syncProfileFromSave((JSONObject) persisted);
        }
        // The equipped-gear list rides OUTSIDE savedata in "savedata"/"lvvictory"
        // uploads ({"equips":[...],"removedequips":[...],"savedata":{...}}), so it
        // has to be picked up separately or it is lost on every save.
        if (req.opt("equips") instanceof JSONArray) {
            JSONObject keep = readSaveObj();
            JSONArray eqs = (JSONArray) req.get("equips");
            // 给背包塞一件 10 阶盘龙铠（equip 表 dataid 3510，AV = 阶数）
            boolean hasPj = false;
            for (int i = 0; i < eqs.length(); i++) {
                JSONObject e = eqs.optJSONObject(i);
                if (e != null && e.optInt("dataid", 0) == 3510) {
                    hasPj = true;
                }
            }
            if (!hasPj) {
                JSONObject pj = new JSONObject();
                pj.put("dataid", 3510);
                pj.put("enhance", 1);
                pj.put("AV", 10);
                eqs.put(pj);
                trace("bag: +10J 盘龙铠");
            }
            keep.put("equips", eqs);
            if (req.opt("removedequips") instanceof JSONArray) {
                keep.put("removedequips", req.get("removedequips"));
            }
            writeSave(keep.toString());
        }

        // This replaces patching around data the retail server used to provide.
        if (CANNED) {
            String canned = null;
            String a = action.toLowerCase();
            if (a.contains("login") || a.contains("serverlist") || a.contains("rolelist")) {
                canned = Canned.LOGIN;
            } else if (a.contains("bag") && !a.contains("buy")) {
                canned = Canned.BAG;
                // currencies are plain fields in the bag block, not items
                canned = grantCurrencies(canned);
            }
            // NOTE: "victory" is deliberately NOT served from Canned.VICTORY any
            // more. That shortcut returned before the level-clear branch below, so
            // awardVictory() never ran and the character never gained exp or stored
            // the new psl - which reads as "exp/level progress is not saved".
            if (canned != null) {
                trace("CANNED " + action);
                return personalize(canned);
            }
        }

        JSONObject r = new JSONObject();
        r.put("code", 1000);
        // 服务器时间：活动/限时玩法的开放窗口都按它判定。2026 年的"现在"会让
        // 11.4 时代（2020-12）的活动全部过期 -> 活动面板空白、八仙这类限时副本进不去。
        // 放一个 /sdcard/yj/servicetime（epoch 秒）就能把世界时间拨回 11.4 的档期。
        long st = System.currentTimeMillis() / 1000L;
        try {
            java.io.File tf = new java.io.File("/sdcard/yj/servicetime");
            if (tf.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(tf)));
                String line = br.readLine();
                br.close();
                if (line != null && line.trim().length() > 0) {
                    st = Long.parseLong(line.trim());
                    trace("servicetime override = " + st);
                }
            }
        } catch (Throwable ignored) {
        }
        r.put("servicetime", st);

        // Level clear. In the retail design the SERVER owns exp, drops and the
        // cleared-sublevel record; Canned.VICTORY hardcodes level 700001 with
        // exp 0, so with that shortcut in place the character never gained exp and
        // the psl never grew. Award + persist here, then lay the canned result
        // block (win screen: result/win/passed/star/drops) on top for the keys the
        // client still needs.
        if (action.contains("victory") || action.endsWith("lvictory")) {
            awardVictory(req, r);
            mergeSave(r);
            try {
                JSONObject c = new JSONObject(Canned.VICTORY);
                Iterator<String> it = c.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!r.has(k)) {
                        r.put(k, c.get(k));
                    }
                }
            } catch (Throwable ignored) {
            }
            // Report the level that was actually cleared, not the canned 700001.
            try {
                int lid = req.optInt("levelid", 0);
                JSONObject lv = r.optJSONObject("level");
                if (lv == null) {
                    lv = new JSONObject();
                }
                if (lid > 0) {
                    lv.put("id", lid);
                }
                if (!lv.has("star")) {
                    lv.put("star", 3);
                }
                if (!lv.has("pass")) {
                    lv.put("pass", 1);
                }
                r.put("level", lv);
            } catch (Throwable ignored) {
            }
            // 仙盟副本 / 龙门福地 这些子关有自己的 drop 表，按表发货
            // （Canned.VICTORY 里写死的 4300 会先被铺上，这里覆盖掉）。
            try {
                applyLevelDrops(r, req.optInt("levelid", 0));
            } catch (Throwable ignored) {
            }
            return r.toString();
        }

        // Any request that carries the character state was already persisted at
        // the top of this function (it has to happen before the canned returns).

        if (isLogin(action)) {
            fillLogin(req, r);
        } else if ("rolelist".equals(action)) {
            r.put("services", services());
            r.put("rolelist", readRoles().length() > 0 ? roleListAll() : new JSONObject());
        } else if (action.startsWith("createrole")) {
            // 多角色：给新角色分配一个没人用过的 rid，各自的存档分文件写，
            // 这样第二个/第三个角色不会把前一个的档覆盖掉。
            JSONArray roles = readRoles();
            int maxRid = 0;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject prev = roles.optJSONObject(i);
                if (prev != null) {
                    maxRid = Math.max(maxRid, prev.optInt("rid", 0));
                }
            }
            int newRid = maxRid + 1;
            int roleid = req.optInt("roleid", 1);
            String name = req.optString("username", "玩家");
            // 官服：同一个服里可以放多个角色，所以新角色跟当前请求的服走
            int service = req.optInt("service", 1);
            if (service <= 0) {
                service = 1;
            }
            sCurRid = newRid;
            JSONObject p = new JSONObject();
            p.put("rid", newRid);
            p.put("roleid", roleid);
            p.put("username", name);
            p.put("rolelv", 1);
            p.put("roleexp", 0);
            p.put("service", service);
            writeProfile(p);
            roles.put(roleRecord(newRid, roleid, name, 1, 0, service));
            writeRoles(roles);
            setWantNewRole(false);
            trace("createrole: rid=" + newRid + " roleid=" + roleid + " name=" + name
                    + " service=" + service + " total=" + roles.length()
                    + " body=" + req.toString());
            // The client hands us the brand-new character state here; keep it
            // so a later roledata2/savedata request can be answered with it.
            //
            // 这里**必须用客户端自己上传的那份**（bag+roledata，约 300 字节）。
            // 之前拿 /sdcard/yj/login_enterserver.json 的 savedata 当底去合并，
            // 会把这套「孙悟空的职业数据/技能」塞进嫦娥的档里 →
            // 进游戏直接弹「存档数据已损坏!」。模板只补缺块，不覆盖 roledata。
            JSONObject upload = req.optJSONObject("savedata");
            JSONObject fresh;
            try {
                fresh = upload == null ? new JSONObject() : new JSONObject(upload.toString());
            } catch (Throwable t) {
                fresh = new JSONObject();
            }
            // 缺的结构块（level/pet/…）从**本机自己生成的存档**补，
            // 但 roledata 绝不碰，也不用 16.8 的模板（格式不一样，会判损坏）。
            try {
                JSONObject ref = referenceSave(newRid);
                if (ref != null) {
                    java.util.Iterator<String> it = ref.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if ("roledata".equals(k) || fresh.has(k)) {
                            continue;
                        }
                        fresh.put(k, ref.get(k));
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                JSONObject rd = fresh.optJSONObject("roledata");
                if (rd == null) {
                    rd = new JSONObject();
                    fresh.put("roledata", rd);
                }
                JSONObject role = rd.optJSONObject("role");
                if (role == null) {
                    role = new JSONObject();
                    rd.put("role", role);
                }
                role.put("rolelv", 1);
                role.put("roleexp", 0);
                JSONObject player = rd.optJSONObject("player");
                if (player == null) {
                    player = new JSONObject();
                    rd.put("player", player);
                }
                player.put("roleid", roleid);
                // 模板是 16.8.2 的存档，必须改成本机版本，否则进游戏弹「上仙版本过低」
                fixSaveVersion(fresh);
                // 和 16.9 的 role_creation.initial_response 一样：建号时就把
                // 本命神技能/初始主动技能按职业写进新档，别等客户端自己猜。
                // 13.x 的原生新档自己带技能，不要给它套 11.4 的字段。
                if (!sV13) {
                    reconcileRoleSkills(fresh);
                }
                java.io.FileOutputStream fo = new java.io.FileOutputStream(saveFileOf(newRid));
                fo.write(fresh.toString().getBytes("UTF-8"));
                fo.close();
                trace("createrole: save seeded rid=" + newRid + " bytes=" + fresh.toString().length());
            } catch (Throwable t) {
                trace("createrole: save write failed " + t);
            }
            r.put("rid", newRid);
            r.put("point", 0);
        } else if (action.startsWith("roledata") || "savedata".equals(action)) {
            fillSave(req, r, action);
            if (action.startsWith("roledata") && !sV13) {
                applyServerBlocks(r);
            }
        } else if ("handleaction".equals(action)) {
            // 至尊装备升品/升星（请求 tag = REQUEST_SAVE_DATA_WITH_HANDLE）。
            handleAction(req, r);
        } else if (isNoblestAction(action)) {
            // fix257：13.0 的官方分发器还认 action="noblest"/"noblest_quality"/…，
            // 11.4 的客户端目前看到的是 handleaction，但版本差异下有可能换名字。
            // 统一按 handleaction 处理，type 缺省时按有没有 source 判断是不是挂接。
            if (!req.has("type")) {
                try {
                    req.put("type", inheritSourceUid(req, req.optJSONObject("target")) > 0
                            ? HA_INLAY : HA_UPGRADE_QUALITY);
                } catch (Throwable ignored) {
                }
            }
            trace("NOBLEST-ACTION " + action + " type=" + req.optInt("type", 0)
                    + " keys=" + req.length());
            handleAction(req, r);
        } else if (action.startsWith("servicelist") || action.startsWith("services")) {
            r.put("list", services());
        } else if (action.startsWith("checkupdate")) {
            // no update packages available offline
            r.put("resourcepkg", "");
            r.put("datapkg", "");
        } else if (action.equals("levelmapdata")) {
            // 16.9 真实应答（flowlab fixtures/e64a6d52…）就是这个形状：
            // 三个空字典 + code/servicetime。11.4 的 worldmap 解析走同一套
            // YJLevelManager，缺这三个键时 JsonCpp 给 null，随后被当字符串用
            // -> std::logic_error -> terminate。照抄 16.9 的形状最安全。
            r.put("spirit_root_level_env_type", new JSONObject());
            r.put("spirit_boss_root_type", new JSONObject());
            r.put("role_double_levels", new JSONObject());
        } else if (action.equals("teamlevellist")) {
            // 组队关卡列表：给空集合，别让客户端拿到 null。
            r.put("teamlevel", new JSONObject());
            r.put("teamlevels", new JSONArray());
            r.put("level", new JSONArray());
            r.put("list", new JSONArray());
        } else if (action.equals("wildbossrefresh") || action.startsWith("wildboss")) {
            r.put("wildboss", new JSONObject());
            r.put("boss", new JSONArray());
            r.put("list", new JSONArray());
        } else if (action.startsWith("eightimm")) {
            // 八仙过海·蓬莱登岛（YJBaXianLevelEntranceBoard）。
            // 面板三处都读服务端数据：今日剩余挑战次数、选择BOSS、灵咒/奖励。
            // 键名未知，先把常见写法全给上（多余的键客户端会忽略），
            // boss 用 sublevel 里的 700171..700178（eight_immortal_bg_enroll_700171.png
            // 就是这个 id 的报名底图）。
            org.json.JSONArray bx = new org.json.JSONArray();
            // 16.9 的 flowlab/dungeon_catalog.json 里 100056=八仙过海（required_role_level 80），
            // children 就是这套关卡：700171 夜 / 700172 昼 / 700173 蓬莱登岛 / 700201..700210 八仙主题。
            int[] bxs = {700171, 700172, 700173,
                    700201, 700202, 700203, 700204, 700205, 700206, 700207, 700208, 700209, 700210};
            for (int id : bxs) {
                bx.put(id);
            }
            r.put("bossid", 700171);
            r.put("curboss", 700171);
            r.put("boss", 700171);
            r.put("bossindex", 0);
            r.put("bosslist", bx);
            r.put("bosses", bx);
            for (String k : new String[]{"times", "count", "num", "remain", "left", "chance", "chances", "cishu",
                    "daytimes", "daycount", "daynum", "dayremain", "todaytimes", "todaycount",
                    "remaincount", "leftcount", "surplus", "canchallenge", "maxcount"}) {
                r.put(k, 5);
            }
            r.put("state", 0);
            r.put("msg", "");
            r.put("imprecation", new JSONObject());
            r.put("imprecations", new JSONArray());
            r.put("stone", new JSONObject());
            r.put("stones", new JSONArray());
            r.put("reward", new JSONArray());
            r.put("rewards", new JSONArray());
        } else if (action.equals("open_beast_bag") || action.startsWith("beast_")) {
            fillBeast(action, req, r);
        } else if (action.startsWith("fategod") || "upfategod".equals(action)
                || "fategodlevelup".equals(action)
                || "REQUEST_FATE_GOD_LEVEL_UP".equals(action)
                || "YJ_ACTION_FATE_GOD_LEVEL_UP".equals(action)
                || "fategodraisedegree".equals(action)
                || "fategodchangeskills".equals(action)
                || action.toLowerCase().contains("swallow")
                || action.toLowerCase().contains("devour")
                || action.toLowerCase().contains("fate_god")) {
            fillFateGod(action, req, r);
        } else if (action.startsWith("thule")) {
            // 极北（Thule）任务链。只回客户端明确会读的 is_can_start_task
            // （libgame 0x1bf1050 附近的 key 表），别的一律不加 —— 上一版塞了
            // ongoing_task_list/chain_type 等猜测字段，客户端在
            // postNotification → CCHttpClient::dispatchResponseCallbacks 里直接崩。
            r.put("is_can_start_task", 1);
            r.put("msg", "");
        } else if (action.startsWith("truespirit")) {
            // 真灵（兽）系统。16.9 的真实 roledata2 应答里形状是：
            //   "true_spirit_bag": {"bagsize":8, "maxspiritid":439999, "list":[]}
            // 这里把同一份数据同时按 true_spirit_bag 和 truespiritinfo 两个键下发，
            // 客户端用哪个都能拿到。
            JSONObject tsb = trueSpiritBag();
            r.put("true_spirit_bag", tsb);
            r.put("truespiritinfo", tsb.optJSONArray("list"));
            r.put("truespiritlist", tsb.optJSONArray("list"));
            r.put("state", 0);
            r.put("msg", "");
        } else if (action.startsWith("leveldata")) {
            // 从 16.9 的 flowlab/dungeons.py::preview 1:1 搬过来的形状：
            //   leveldata  -> lvdata { "<levelid>": {"canenter":1} }
            //   leveldata2 -> levels [ {"level_id":..,"limit_level":..,"canenter":1} ]
            // 客户端靠它决定每个子关卡"能不能进"，缺了就显示暂未开放/不存在。
            JSONObject lvdata = new JSONObject();
            JSONArray levels = new JSONArray();
            JSONArray ids = req.optJSONArray("levelids");
            if (ids == null) {
                ids = req.optJSONArray("level_ids");
            }
            try {
                if (ids != null) {
                    for (int i = 0; i < ids.length(); i++) {
                        String id = String.valueOf(ids.opt(i));
                        JSONObject c = new JSONObject();
                        c.put("canenter", 1);
                        lvdata.put(id, c);
                        JSONObject row = new JSONObject();
                        row.put("level_id", ids.optInt(i, 0));
                        row.put("limit_level", 0);
                        row.put("canenter", 1);
                        levels.put(row);
                    }
                }
            } catch (Throwable ignored) {
            }
            r.put("lvdata", lvdata);
            r.put("levels", levels);
        } else if (action.startsWith("levelenter")) {
            // 16.9 dungeons.py: response.update(state=0, canenter=1, flag=1)
            // state 必须是 0（入场分支），canenter 是地图上的独立标志。
            //
            // 极北（Thule）的 7005xx 子关是 16.9 内容，11.4 的 sublevel.sdb /
            // level.sdb 里没有对应行；客户端进这种关卡会在 GLThread 里直接
            // SIGILL（houdini/libgame 渲染缺资源）。这里先挡住未知关卡，
            // 避免进图闪退。
            int lid = req.optInt("level_id", req.optInt("levelid", 0));
            // 只挡极北 7005xx 的未知子关；九重天这里是 levelid=700001 /
            // superlvid=100001，levelExists(700001) 查不到，不能一起挡。
            if (lid >= 700500 && lid <= 700599 && !levelExists(lid)) {
                r.put("state", 1);
                r.put("canenter", 0);
                r.put("flag", 0);
                r.put("msg", "极北地图资源缺失，已阻止进入（避免闪退）");
                trace("LEVELENTER blocked unknown level " + lid);
            } else {
                r.put("state", 0);
                r.put("canenter", 1);
                r.put("flag", 1);
                r.put("msg", "");
                r.put("spotdata", new JSONObject());
            }
        } else if (action.startsWith("level")) {
            r.put("state", 0);
            r.put("canenter", 1);
            r.put("flag", 1);
            r.put("msg", "");
            r.put("spotdata", new JSONObject());
        } else if (action.startsWith("lvlottery") || action.endsWith("lottery")) {
            // 16.9 dungeons.py::finish(lvlottery):
            //   {point, bind_point, reward:[item], reward_list:[[card],...]}
            r.put("discardcost", 0);
            r.put("not_give_up_card", 1);
            r.put("reward", new JSONArray());
            r.put("reward_list", new JSONArray());
        } else if (action.startsWith("lvvictory") || action.startsWith("lv")) {
            // 16.9 dungeons.py::finish(lvvictory):
            //   {point, bind_point, discardcost, not_give_up_card, list:[卡片...]}
            r.put("discardcost", 0);
            r.put("not_give_up_card", 1);
            r.put("list", new JSONArray());
            r.put("msg", "");
        } else if (action.startsWith("task") || action.startsWith("mission")) {
            // YJMissionManager::_handleMissionListGotten (0x560744) 读
            // dailytask / timelimit / boss；领取动作读 task + tid。
            r.put("dailytask", new JSONObject());
            r.put("storytask", mainTaskList());
            r.put("timelimit", new JSONObject());
            r.put("boss", new JSONObject());
            r.put("holiday_task", new JSONObject());
            if (!"tasklist".equals(action)) {
                trace("TASK claim action=" + action + " body=" + req.toString());
                markPetsClaimed();
                r.put("reward", new JSONArray());
            }
        } else if (isShopList(action)) {
            // 商城/神秘商店：带 buy_count / goods_unique_mark / goodsid 的是购买。
            // 以前一律回商品列表 -> 客户端重建列表（滚动条跳回最上层），而且
            // 拿不到买到的道具。
            if (req.has("buy_count") || req.has("goods_unique_mark")
                    || req.has("goodsid") || req.has("buy_num")) {
                fillBuy(req, r);
            } else {
                fillShop(action, r);
            }
        } else if ("bulletin".equals(action)) {
            // YJChatManager::_handleBulletinReceived @0x51b9f0 reads "message";
            // without it the 公告 window pops up empty on every login.
            r.put("message", NOTICE);
        } else if ("mail".equals(action) || "mailnew".equals(action)) {
            // 11.4 的列表请求就是 mailnew，且**必带 "type":2**（表示邮件分类）。
            // 以前把「带 type」当成领取，于是 mailnew 永远走 putMailReward，
            // 回包没有 mails，面板就是空的。领取走的是 receivereward(type=37)
            // 和 mailreceivebatch，跟列表请求区分开。
            r.put("mails", mailList());
            r.put("total_page_num", 1);
            r.put("mail_type", req.optInt("type", 2));
            r.put("nextrefreshtime", 0);
            trace("MAIL list type=" + req.optInt("type", 2) + " offset=" + req.optInt("offset", 0)
                    + " n=" + mailList().length());
        } else if ("mailreceivebatch".equals(action)) {
            trace("MAIL receivebatch body=" + req.toString());
            putMailReward(r);
            r.put("mails", mailList());
            r.put("total_page_num", 1);
        } else if ("maildelete".equals(action)) {
            r.put("reward", new JSONArray());
            r.put("rewards", new JSONArray());
            r.put("mails", mailList());
            r.put("total_page_num", 1);
        } else if ("receivereward".equals(action)) {
            // ⚠️ 邮件「领取」发的**不是** mail，是这个 action。实测报文：
            //   {"action":"receivereward","id":0,"is_user":1,"mtype":1,"type":37,...}
            // （native 侧 FUN_03b60ba8 @0x03b60ba8 组装 {id,type,mtype,is_user}，
            //   action id 0xe。）以前没有这个分支，领取就落到通用应答里，
            //   客户端拿不到 reward → 点下去毫无反应。
            // type 37 = 邮件；签到/活动/任务那套走别的 type，仍旧回空数组。
            if (req.optInt("type", 0) == REWARD_TYPE_MAIL) {
                trace("MAIL receivereward body=" + req.toString());
                if (req.optInt("id", -1) == 3 || req.optInt("id", -1) == 4) {
                    grantTrueSpiritMail(r);
                } else {
                    putMailReward(r);
                }
            } else {
                r.put("reward", new JSONArray());
                r.put("rewards", new JSONArray());
            }
        } else if ("tasklist".equals(action)) {
            // YJMissionManager::_handleMissionListGotten (0x560744) 读三个键：
            //   dailytask / timelimit / boss
            // 每条任务由 YJMissionItem::deserializeFromJsonValue (0x75a1e0) 解析：
            //   tid targetprogress target endtime encodetitle isserverstarttime isserverendtime
            // 客户端没有本地任务表（assets/database 里没有 task/mission 表），
            // 所以任务完全由服务端定义 —— 包括标题 encodetitle（base64 UTF-8）。
            r.put("dailytask", new JSONObject());
            r.put("storytask", mainTaskList());
            r.put("timelimit", new JSONObject());
            r.put("boss", new JSONObject());
            r.put("holiday_task", new JSONObject());
        } else if (action.toLowerCase().contains("task")) {
            // 接任务 / 领奖（_requestToAcceptMission 0x55fc48，读 task + tid）。
            // 宠物目前仍是登录即送，这里只记一笔 + 解锁标记，方便以后改成"领取才给"。
            trace("TASK claim action=" + action + " body=" + req.toString());
            markPetsClaimed();
            r.put("reward", new JSONArray());
        } else if ("rolesigninfo".equals(action)) {
            fillSignIn(r);
        } else if (action.toLowerCase().contains("sign")) {
            // 领取签到（_acquireSignInFromServer，带 rid+type）。
            // 它的应答处理函数是空的，客户端随后会再拉一次 rolesigninfo。
            claimSignIn();
            r.put("rewards", new JSONArray());
        } else if (action.startsWith("mail")) {
            // 兜底：任何未识别的 mail* 都当列表请求，绝不在这里发奖。
            // 领取只认 receivereward(type=37) / mailreceivebatch。
            r.put("mails", mailList());
            r.put("total_page_num", 1);
            trace("MAIL fallback action=" + action);
        } else if (action.equals("chatpreview")) {
            // _handleChatPreviewRecieved 只在 isMember 时才去解析 message/topmsg（
            // 那两个是 YJChatMsg 对象，不是字符串），所以只回 unread 最安全。
            r.put("unread", 0);
            trace("chatpreview");
        } else if (action.equals("ginfo")) {
            // 仙盟信息。YJGuildManager::_handleGuildInfoResponse @0x6883fc 读顶层
            //   permission（权限表）/ ginfo（对象）/ list（数组）
            // 其中 ginfo 交给 YJGuildInfo::deserializeAndUpdateFromJsonValue
            // @0x670168，成功返回 1。返回的对象不对 -> showGameTip(仙盟信息获取失败)
            // -> guildInfo 一直是 null -> 其它仙盟请求被守卫挡下并再次弹这个提示。
            fillGuildInfo(r);
        } else if (action.equals("gbuildinginfo")) {
            // 打开建筑面板：必须回 type=1 才会进得去（type=2 只刷新信息）
            fillGuildBuildingInfo(req, r);
        } else if (action.equals("gconstruction") || action.startsWith("gbuild")
                || action.startsWith("gbup") || action.startsWith("gbdone")
                || action.equals("gbuypurview") || action.equals("gbuypurviewprice")
                || action.equals("gbuyincrease") || action.equals("gbuyincreaseprice")
                || action.equals("gblist")) {
            // 建造 / 升级 / 建筑信息：统一回 type=2 + 满级仙盟状态，
            // 客户端据此重建 buildinglist（见 fillGuildBuilding）。
            fillGuildBuilding(action, r);
        } else if (action.startsWith("gprayequip")) {
            // 问仙：应答必须带 prayequips 数组，否则点了没反应
            fillPrayEquip(action, req, r);
        } else if (action.equals("gbless") || action.startsWith("gdungeon")
                || action.equals("gstorage") || action.equals("gstoragedonate")
                || action.equals("gskillsave")
                || action.equals("gskillup") || action.equals("gdomain")
                || action.equals("gdomaindetail")) {
            // 这些按钮只要求「刷新一下仙盟信息」，type=2 正合适
            fillGuildBuilding(action, r);
        } else if (action.equals("ghosttown")) {
            fillGhostTown(r);
        } else if (action.equals("ghostshop")) {
            r.put("ghostshop", new JSONObject());
            r.put("list", new JSONArray());
        } else if (action.equals("ghosttownrank")) {
            r.put("list", new JSONArray());
        } else if ("buymagicattrprice".equals(action) || "buymagicattr".equals(action)) {
            // 法宝开孔（买孔）。请求里带整份 savedata，孔位由客户端自己加再上传，
            // 服务端只要别回空应答（之前完全没接 → 客户端报「发生不可预知的错误」）。
            r.put("price", 100);
            r.put("orignprice", 100);
            r.put("orign_price", 100);
            r.put("currentprice", 100);
            r.put("cost", 100);
            r.put("coupon", 0);
            r.put("attrprice", 0);
            r.put("magicattrprice", 0);
            r.put("type", req.optInt("type", 1));
            r.put("index", req.optInt("index", 0));
            r.put("rideid", req.optInt("rideid", 0));
            r.put("ridesid", req.optInt("rideid", 0));
            r.put("globalid", req.optLong("globalid", 0L));
            trace("MAGICATTR " + action + " rideid=" + req.optInt("rideid", 0)
                    + " index=" + req.optInt("index", 0)
                    + " globalid=" + req.optLong("globalid", 0L));
        } else if (action.startsWith("crossdisasterinfo")) {            // YJCrossDisasterManager::_handleCrossDisasterInfoResponse @0x53e950 读
            //   probability / probbaseequip / probincreaseequip /
            //   probbasestone / probincreasestone
            fillCrossDisasterInfo(r);
        } else if (action.startsWith("crossdisaster")) {
            // YJCrossDisasterManager::_handleCrossDisasterResponse @0x52eb3c：
            //   removeCurrencyForcely(auracosts) -> removeItemFromBag(材料,1)
            //   if (isthrough) { item = createItemByJson(target);
            //                    if (item) { addItemToBag; 若原装备在身上则卸下再穿 }
            //                    removeItemFromBag(原装备,1);  <-- 无论建没建成都会删！
            //                  }
            // 所以 target 必须能被 createItemByJson @0x6a69d0 建出物品，否则装备直接消失。
            // createItemByJson 认 A2（必须是 equip 表里真实存在的行 id）和 A5（实例 id），
            // 建完再调 item->vtable[0x20](json) 反序列化属性，返回 0 就销毁并 return null。
            fillCrossDisaster(req, r);
        } else if (action.startsWith("REQUEST_FAIRY_EQUIP_")
                || action.startsWith("fairydevice")
                || action.startsWith("enlivener")) {
            // 仙器培养：11.4 的动作名是 REQUEST_FAIRY_EQUIP_*，16.9 是
            // fairydevice*/enlivener*。客户端侧基本按 target 物品本地改，
            // 服务端至少要把 target 回显并落盘，且不能带 base（会触发存盘
            // 观察者把改动盖回去）。
            fillFairyEquip(action, req, r);
        } else if ("buybagprice".equals(action) || "REQUEST_BAG_OPEN_PRICE".equals(action)) {
            // 背包扩充询价。必须回 point + price（物品对象），不能落到通用
            // fillBuy 的 goods/goodsId，否则客户端解析不了 price，弹
            // 「发生不可预知的错误」。
            fillBagPrice(req, r);
        } else if ("buybag".equals(action) || "REQUEST_BAG_OPEN".equals(action)) {
            fillBuyBag(req, r);
        } else if (action.contains("goodsprice") || action.contains("itemprice")) {
            // YJStoreManager::_handleGoodsPriceRequestSuccess (0x57d884) 只认 "price"，
            // 缺了它客户端就弹「获取物品价格出错!」。price 要是个物品对象
            // （和神秘商店的 price 一样，createStoreItemFromJson 从它取 currentPrice）。
            int gid = req.optInt("goodsid", req.optInt("id", 0));
            r.put("goodsId", gid);
            r.put("price", itemJson(SPIRIT_ITEM_ID, 200));
            trace("PRICE goodsId=" + gid);
        } else if (action.startsWith("buy") || action.endsWith("buy") || action.contains("buy")) {
            // 注意 mysteryshopbuy2 —— 结尾是 "2" 不是 "buy"，只按 endswith 判断
            // 会把它当"货架列表请求"，客户端拿到 goods 数组 -> 「成功花费0购买0个」。
            fillBuy(req, r);
        }
        // Every reply carries "base": whatever request YJSaveLoadManager's
        // completion observer belongs to, analyzeAndApplyDataToGame validates it
        // through that single key (missing -> FUN_0042af20(), string ->
        // "存档数据已损坏！"). Stamping it centrally removes the guesswork.
        //
        // ⚠️ handleaction 例外：带上 "base" 会触发存盘观察者，把刚才 info.update
        // 应用的物品改动又盖回去（实测 panel 仍是 +0）。retail 的这条应答也没有
        // base，所以这里跳过。
        if (!r.has("base") && !"handleaction".equals(action)
                && !action.startsWith("REQUEST_FAIRY_EQUIP_")
                && !action.startsWith("fairydevice")
                && !action.startsWith("enlivener")) {
            r.put("base", baseBlock());
        }
        return r.toString();
    }

    // ------------------------------------------------------- 仙盟 / 渡劫
    //
    // 仙盟：YJGuildManager::_handleGuildInfoResponse @0x6883fc
    //   permission -> _refreshGuildPermissionTable
    //   ginfo      -> isObject 检查后交给 YJGuildInfo::deserializeAndUpdateFromJsonValue
    //                 @0x670168（成功 return 1）；失败弹「仙盟信息获取失败」并把
    //                 guildInfo 清空，之后每个仙盟请求都会被守卫再次挡下。
    //   list       -> _refreshGuildList（仙盟列表，数组）
    // ginfo 里客户端会读的字段（按 0x670168 的 isMember 顺序）：
    //   gid gname gtop external internal pname gmember gmembermax members
    //   isapply dateline skinid prosperity glevel level buildinglist
    //   monsterinvasion warid declared applysetting
    // members 是数组，每项由 YJGuildMember::create() 再走 vtable+0x1c 解析；
    // 未知字段留空数组，客户端在 empty() 时直接跳过，不会崩。

    private static final int GUILD_ID = 1;
    private static final String GUILD_NAME = "单机仙盟";
    private static final int GUILD_LEVEL = 99;
    /** 一切建筑的等级都拉满。客户端拿 levellist/buildinglist 里的 level 直接显示。 */
    private static final int GUILD_BUILDING_LEVEL = 99;
    /**
     * 建筑索引。YJGuildInfo::_createBuildingInfoByID @0x66e67c 用
     * yj_const_int_value(6,6,0,1,0,0,N) 比较，那个函数（@0x5124e0）是
     * 「把 n 个数字按十进制拼起来」，所以 6 个数字 6,0,1,0,0,N = 60100N。
     * 也就是说 id 就是 guildtown_building.sdb 的行 id：601000..601006
     *   601000 议事楼 / 601001 祈福 / 601002 任务 / 601003 商店
     *   601004 仓库 / 601005 副本 / 601006 祭坛
     * （601999 神秘建筑在客户端没有对应类，不下发。）
     */
    private static final int GUILD_BUILDING_BASE = 601000;
    private static final int GUILD_BUILDING_COUNT = 7;

    /**
     * 单个建筑对象的字段，来自
     * YJGuildBuildingInfo::deserializeAndUpdateFromJsonValue @0x66a8c8：
     *   level state extrastate duration completetime shieldend shield_num
     *   frozenremain frozenend upgradedesc is_next_invasion
     * 「建没建」看的是 state（0 = 未建造，其余是已建/建造中/升级中）。
     * 为了方便试值，state 可以用 /sdcard/zmxy_guild_state.txt 覆盖，不用重打包。
     */
    private static int guildBuildState() {
        try {
            java.io.File f = new java.io.File("/sdcard/zmxy_guild_state.txt");
            if (f.exists() && f.length() > 0) {
                String s = new String(readAll(f), "UTF-8").trim();
                if (s.length() > 0) {
                    return Integer.parseInt(s.split("\\s+")[0]);
                }
            }
        } catch (Throwable ignored) {
        }
        return 2;
    }

    private static JSONObject guildBuildingEntry(int id) throws Exception {
        int st = guildBuildState();
        JSONObject b = new JSONObject();
        b.put("id", id);
        b.put("level", GUILD_BUILDING_LEVEL);
        b.put("state", st);
        b.put("extrastate", 0);
        b.put("duration", 0);
        b.put("completetime", 0);
        b.put("shieldend", 0);
        b.put("shield_num", 0);
        b.put("frozenremain", 0);
        b.put("frozenend", 0);
        b.put("upgradedesc", "");
        b.put("is_next_invasion", 0);
        b.put("remaintimes", 99);
        b.put("maxtotalremaintimes", 99);
        b.put("totalremaintimes", 99);
        return b;
    }

    private static JSONArray guildBuildingList() throws Exception {
        JSONArray a = new JSONArray();
        for (int t = 0; t < GUILD_BUILDING_COUNT; t++) {
            a.put(guildBuildingEntry(GUILD_BUILDING_BASE + t));
        }
        return a;
    }

    /** 一个完整的 YJGuildInfo。ginfo 回包和建筑回包共用。 */
    private static JSONObject guildPayload() throws Exception {
        JSONObject g = new JSONObject();
        g.put("gid", GUILD_ID);
        g.put("gname", GUILD_NAME);
        g.put("gtop", GUILD_LEVEL);
        g.put("external", "本服为单机离线版，仙盟由本地服务端提供。");
        g.put("internal", "欢迎！");
        g.put("pname", "盟主");
        g.put("gmember", 1);
        g.put("gmembermax", 50);
        g.put("members", new JSONArray());
        g.put("isapply", 0);
        g.put("dateline", 0);
        g.put("skinid", 0);
        g.put("prosperity", 999999);
        g.put("glevel", GUILD_LEVEL);
        g.put("level", GUILD_LEVEL);
        g.put("buildinglist", guildBuildingList());
        g.put("monsterinvasion", new JSONObject());
        g.put("warid", 0);
        g.put("declared", 0);
        g.put("position", 1);
        return g;
    }

    private static void fillGuildInfo(JSONObject r) throws Exception {
        r.put("ginfo", guildPayload());
        r.put("list", new JSONArray());
        // _handleGuildInfoResponse 还单独在**顶层**读 buildinglist，
        // 交给 YJGuildInfo::refreshBuildingListInfo @0x66f29c（isArray 分支，
        // 每项取 "id" 当建筑索引，再把整项交给该建筑的 deserialize）。
        r.put("buildinglist", guildBuildingList());
        trace("ginfo -> guild " + GUILD_NAME + " buildings=" + GUILD_BUILDING_LEVEL);
    }

    /**
     * 仙盟建筑相关的请求（建造 / 升级 / 建筑信息）。
     *
     * YJGuildManager::_handleGuildBuildingInfoResponse @0x681d08 先看顶层 "type"：
     *   type == 2 -> 直接把这个应答丢给 YJGuildInfo::deserializeAndUpdateFromJsonValue
     *                （即整份仙盟状态），然后弹一个提示
     *   type == 1 -> 按子类型分发到各建筑的 _handleGuildXxxInfoGotten
     * 所以这里统一回 type=2 + 一份满级仙盟状态：客户端会把本地仙盟数据换成我们的，
     * 建筑也就跟着变成满级。
     */
    // ------------------------------------------------- 建筑面板的内容
    //
    // YJGuildStore::deserializeAndUpdateFromJsonValue @0x6735d0   -> goods[]（YJGuildStoreItem）+ totalremaintimes + maxtotalremaintimes
    // YJGuildBlessBuilding @0x66a2ac                              -> list[]（YJGuildBlessTypeInfo）+ totalremaintimes + maxtotalremaintimes
    // YJGuildDungeonBuilding @0x66d640                            -> levellist[]（YJGuildDungeonInfo）+ dungeontimesremain + totalremaintimes + maxtotalremaintimes
    // 三者都先调 YJGuildBuildingInfo 基类反序列化（level/state/...）。
    // 叶子项的字段名没有可靠来源，这里按名字最直观的方式给一份超集：
    // 客户端读不到某个键只会留默认值，不会崩。

    /** 仙盟商店的商品。
     * YJGuildStoreItem::deserializeFromJsonValue @0x673230 读：
     *   goodsmark category price amount levelrequired attr
     * 其中 attr 是**物品对象**，客户端拿它去建物品 —— 缺了就是空指针，
     * 这就是之前一开商店就闪退的原因。
     */
    private static JSONArray guildStoreGoods() throws Exception {
        int[] ids = {502000, 502001, 502002, 503004, 504008, 503093, 503001};
        String[] names = {"玄铁", "檀木", "丝绸", "三倍经验丹", "九转还童丹", "鬼道轮回传送石", "幸运符"};
        JSONArray a = new JSONArray();
        for (int i = 0; i < ids.length; i++) {
            JSONObject g = new JSONObject();
            g.put("goodsmark", i);
            g.put("category", 0);
            g.put("price", 0);
            g.put("amount", 99);
            g.put("levelrequired", 1);
            g.put("attr", itemJson(ids[i], 1));
            g.put("name", names[i]);
            a.put(g);
        }
        return a;
    }

    /** 仙盟祈福。
     * YJGuildBlessTypeInfo::deserializeFromJsonValue @0x669c90 读：
     *   type remaintimes limitlevel name desc price costs reward
     * price/costs 的形态不确定，先只给能确定的；缺键只会留默认值，比给错类型安全。
     */
    private static JSONArray guildBlessList() throws Exception {
        String[] names = {"普通祈福", "至尊祈福"};
        String[] descs = {"消耗贡献值获得仙气和战功，每日一次", "消耗点券获得更多仙气和战功，每日一次"};
        JSONArray a = new JSONArray();
        for (int t = 0; t < names.length; t++) {
            JSONObject b = new JSONObject();
            b.put("type", t);
            b.put("name", names[t]);
            b.put("desc", descs[t]);
            b.put("remaintimes", 1);
            b.put("limitlevel", 1);
            b.put("reward", new JSONArray());
            a.put(b);
        }
        return a;
    }

    /** 仙盟副本。
     * YJGuildDungeonInfo::deserializeFromJsonValue @0x66d04c 读：
     *   levelid requiredglevel remaintimes desc contrcost
     *
     * 这里的 levelid 填的是**子关 id**（700xxx），不是关卡 id：
     * 客户端进战斗用的 levelenter 带的就是 700xxx（日志里 levelid:700122 这种）。
     * 子关表 sublevel.sdb 里，仙盟副本 = 100039，下面是
     *   700107 魔物饕餮 ← enemy 418「魔物饕餮」，3 百万血的 Boss
     *   700110..700113 兜率宫地下四层
     * 仙盟守卫战 = 100040 → 700108 / 700109
     * 所以直接从表里生成，饕餮自然排在第一个。
     */
    private static final String[] GUILD_DUNGEON_LEVEL_IDS =
            {"100039", "100040", "100041", "100045"};

    private static JSONArray guildDungeonList() throws Exception {
        JSONArray a = new JSONArray();
        JSONObject sub = table("sublevel");
        if (sub == null) {
            return a;
        }
        for (int li = 0; li < GUILD_DUNGEON_LEVEL_IDS.length; li++) {
            JSONArray rows = sub.optJSONArray(GUILD_DUNGEON_LEVEL_IDS[li]);
            if (rows == null) {
                continue;
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                int sid = r.optInt("id", 0);
                if (sid <= 0) {
                    continue;
                }
                JSONObject d = new JSONObject();
                d.put("levelid", sid);
                d.put("requiredglevel", 1);   // 需求副本建筑物等级
                d.put("remaintimes", 99);     // 剩余挑战次数
                d.put("desc", r.optString("name", "仙盟副本"));
                d.put("contrcost", 0);        // 消耗贡献度
                a.put(d);
            }
        }
        return a;
    }

    /**
     * 祭坛的技能升级列表。
     * YJGuildAltarBuilding::deserializeAndUpdateFromJsonValue @0x667b90 读：
     *   skilllist prayequips
     * 每项（@0x6676b8）读：
     *   skillid skill_level is_unlock damage damage_unsaved up_money
     *   up_contribution level_required skill_limit
     * 技能 id 不能瞎编 —— 直接从存档里玩家真正学会的 initiative_skill 取。
     */
    private static JSONArray guildAltarSkills() {
        JSONArray a = new JSONArray();
        try {
            JSONObject store = readSaveObj();
            JSONArray learned = findArray(store, "initiative_skill");
            if (learned == null) {
                return a;
            }
            for (int i = 0; i < learned.length(); i++) {
                JSONObject s = learned.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                int sid = s.optInt("skillid", 0);
                if (sid <= 0) {
                    continue;
                }
                JSONObject k = new JSONObject();
                k.put("skillid", sid);
                k.put("skill_level", Math.max(1, s.optInt("skillgrade", 1)));
                k.put("is_unlock", 1);
                k.put("damage", 0);
                k.put("damage_unsaved", 0);
                k.put("up_money", 0);
                k.put("up_contribution", 0);
                k.put("level_required", 1);
                k.put("skill_limit", 99);
                a.put(k);
            }
        } catch (Throwable t) {
            trace("guildAltarSkills failed " + t);
        }
        return a;
    }

    // ----------------------------------------------------------- 关卡掉落
    //
    // 仙盟副本和龙门福地的子关在 sublevel.sdb 里都带 "drop": [物品 id...]。
    // 客户端拿到应答里的 drops/dropsarr/list（数组，每项交给 createItemByJson）
    // 就会把东西塞进背包，所以按表把 drop 列表原样发回去就行。
    // 装备类要给唯一实例 id（A5），否则会堆成同一件。

    private static long sDropGid = 950000L;

    private static synchronized long nextDropGid() {
        return sDropGid++;
    }

    private static JSONObject dropItem(int id) {
        JSONObject it = itemJson(id, 1);
        try {
            if (inTable("equip", id)) {
                it.put("A5", nextDropGid());
            }
        } catch (Throwable ignored) {
        }
        return it;
    }

    private static void applyLevelDrops(JSONObject r, int lid) {
        if (lid <= 0) {
            return;
        }
        try {
            JSONObject sub = table("sublevel");
            if (sub == null) {
                return;
            }
            JSONObject row = null;
            for (int gi = 0; gi < GUILD_DUNGEON_LEVEL_IDS.length && row == null; gi++) {
                JSONArray rows = sub.optJSONArray(GUILD_DUNGEON_LEVEL_IDS[gi]);
                if (rows == null) {
                    continue;
                }
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject e = rows.optJSONObject(i);
                    if (e != null && e.optInt("id", 0) == lid) {
                        row = e;
                        break;
                    }
                }
            }
            if (row == null) {
                return;
            }
            JSONArray drop = row.optJSONArray("drop");
            if (drop == null || drop.length() == 0) {
                return;
            }
            JSONArray items = new JSONArray();
            for (int i = 0; i < drop.length(); i++) {
                int id = drop.optInt(i, 0);
                if (id > 0) {
                    items.put(dropItem(id));
                }
            }
            if (items.length() == 0) {
                return;
            }
            String js = items.toString();
            r.put("drops", new JSONArray(js));
            r.put("dropsarr", new JSONArray(js));
            r.put("list", new JSONArray(js));
            JSONObject lv = r.optJSONObject("level");
            if (lv != null) {
                lv.put("drops", new JSONArray(js));
            }
            logLine("DROPS level=" + lid + " (" + row.optString("name", "?") + ") n=" + items.length());
        } catch (Throwable t) {
            trace("applyLevelDrops failed " + t);
        }
    }

    // --------------------------------------------------------- 酆都鬼城
    //
    // YJLevelManager::_handleLevelGhostTownDataGotten @0x562550 读：
    //   spotinfo（对象，成员名是 792xxx 的点位 id）
    //   ghostshop（对象）-> YJLevelGhostTownHelper::deserializeGhostTownStore @0x761210
    //   sublevel（对象）-> deserializeGhostTownSubLevelObjs @0x7621dc
    //       注意是**对象**：成员名就是子关 id 的字符串，每项 create 一个
    //       YJLevelGhostTownSubLevelObj
    // 之前只回通用应答，所以鬼城面板只有「更多酆都鬼城副本敬请期待」。
    // 酆都鬼城 = level 100041，子关 700114 决战黄泉路 / 700115 白骨魔窟。

    private static final String GHOST_TOWN_LEVEL_ID = "100041";

    private static void fillGhostTown(JSONObject r) throws Exception {
        JSONObject subs = new JSONObject();
        JSONObject sub = table("sublevel");
        if (sub != null) {
            JSONArray rows = sub.optJSONArray(GHOST_TOWN_LEVEL_ID);
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject e = rows.optJSONObject(i);
                    if (e == null) {
                        continue;
                    }
                    int id = e.optInt("id", 0);
                    if (id <= 0) {
                        continue;
                    }
                    JSONObject v = new JSONObject();
                    v.put("id", id);
                    v.put("levelid", id);
                    v.put("name", e.optString("name", ""));
                    v.put("desc", e.optString("name", ""));
                    v.put("state", 0);
                    v.put("times", 99);
                    v.put("timesremain", 99);
                    subs.put(String.valueOf(id), v);
                }
            }
        }
        r.put("sublevel", subs);
        r.put("ghostshop", new JSONObject());
        r.put("spotinfo", new JSONObject());
        logLine("ghosttown sublevel=" + subs.length());
    }

    // --------------------------------------------------------------- 问仙
    //
    // 11.4 的应答结构（libgame 0xf8125c）：
    //   prayequips: [ { globalitemid, saved?, unsaved?, unsaved_10? } ]
    // 每个条目交给 YJEquipItem::deserializeParyPlusFromJsonValue：
    //   - "saved"   必须是 object 且非空：清空两套属性，整体读入（已保存的最终值）
    //   - "unsaved" 必须是 object 且非空：只清空未保存那套，读入本次预览
    // 属性对象就是 {属性名: 数值}，例如 {"hp":1000,"critical":3}。
    // 百分比属性（暴击/闪避/魔抗）由 YJItem::isAttributeInDecimal 判断后 ÷100，
    // 所以这里直接发百分数（3 = 3%）。
    //
    // **以前的 {p,v,l} 是错的**：那会被当成三条名叫 p/v/l 的属性，面板一条都
    // 对不上，于是「保存按钮亮了，数值不变」。
    //
    // 数值规则（用户提供，权威）：
    //   阶数 N（1..10）决定每条属性的总上限：
    //     生命 1000N / 魔法 500N / 攻击 100N / 防御 50N
    //     暴击、闪避、魔抗 min(9, N)%
    //     神力 / 豁免按 10 阶表（PRAY_GODPOWER / PRAY_IMMUNITY）
    //   10 阶不再涨暴击/闪避/魔抗，只加四维 + 神力/豁免。
    //   每次问仙随机挑一条没满的属性，加 1..总上限/10；十连就是连续 10 次。
    //
    // 状态存 savedata.yj_pray（服务端权威）：
    //   {"<gid>": {"degree":10, "saved":{...}, "unsaved":{...}}}
    // 同时写回 bag.item 的 saved/unsaved/BO，并重建 savedata.prayequips，
    // 这样无论客户端从应答还是从读档路径拿，都能看到。
    //
    // 调试：/sdcard/yj/pray_probe.json 存在时，把它的 unsaved（或整个对象）
    // 原样当作本次结果返回，用来试属性键，不用重打包。
    private static final Object PRAY_LOCK = new Object();

    /** 面板九条属性。 */
    private static final String[] PRAY_PROPS = {
            "hp", "mp", "str", "def", "godpower", "dodge", "critical", "magDef", "immunity"
    };
    /**
     * 神力/豁免的每阶上限（用户表）：
     * 1:45/39 2:171/150 3:378/333 4:666/588 5:1035/915
     * 6:1485/1314 7:2016/1785 8:2628/2328 9:3321/2943 10:4095/3630
     */
    private static final int[] PRAY_GODPOWER =
            {45, 171, 378, 666, 1035, 1485, 2016, 2628, 3321, 4095};
    private static final int[] PRAY_IMMUNITY =
            {39, 150, 333, 588, 915, 1314, 1785, 2328, 2943, 3630};

    private static final java.util.Random PRAY_RND = new java.util.Random();

    // ------------------------------------------------- 10 阶传说装备峰值
    //
    // 用户规则：传说装备/八仙法宝/传说剑升到 10 阶后，问仙的九条属性可以直接
    // 吃到峰值；诛仙剑阵（500026）不能问仙，也就没有峰值。
    //   生命 10000 / 魔法 5000 / 攻击 1000 / 防御 500
    //   暴击 9% / 闪避 9% / 魔抗 9% / 神力 4095 / 豁免 3630
    // 实现上不再依赖客户端的问仙随机链路：读档/存盘时把四维直接叠进物品的
    // A7..AA（客户端本来就把它们当装备基础属性），并把暴击/闪避/魔抗/
    // 神力/豁免叠进角色 attribute（只对穿在身上的 10 阶装备生效）。
    // yj_peak 记录每件物品的原始 A7..AA，保证每次同步都是“原始值 + 峰值”，
    // 不会因为客户端反复上传而越叠越高。
    private static final int PEAK_HP = 10000;
    private static final int PEAK_MP = 5000;
    private static final int PEAK_STR = 1000;
    private static final int PEAK_DEF = 500;
    private static final int PEAK_CRIT = 9;
    private static final int PEAK_DODGE = 9;
    private static final int PEAK_MAGDEF = 9;
    private static final int PEAK_GODPOWER = 4095;
    private static final int PEAK_IMMUNITY = 3630;

    /**
     * 传说装备（equip.sdb grade 7）的物品 id。
     * 装备的阶数字段不是 AV（AV 在装备上通常是 0/强化等级），
     * 所以不能只靠 AV>=10 判断；这里按 id 直接兜住。
     */
    private static final int[] LEGEND_EQUIP = {
            1308, 1315, 1506, 1512, 2308, 2315, 2506, 2512, 3308, 3315,
            3506, 3513, 4309, 4322, 4506, 4512, 5309, 5508, 6308, 6315,
            6506, 6512, 7315, 7512, 500220, 500221, 500222, 520190, 520220
    };

    private static boolean peakEligible(JSONObject it) {
        if (it == null) {
            return false;
        }
        int id = it.optInt("A2", 0);
        if (id <= 0 || id == 500026) {
            return false;
        }
        if (it.optInt("AV", 0) >= 10) {
            return true;
        }
        for (int i = 0; i < LEGEND_EQUIP.length; i++) {
            if (LEGEND_EQUIP[i] == id) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLegendEquip(int id) {
        for (int i = 0; i < LEGEND_EQUIP.length; i++) {
            if (LEGEND_EQUIP[i] == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * 至尊装备（noblest_equip）里能做铸魂底材的几件。
     * 铸魂面板顶上的结果槽/“飞升至尊装备”需要装备带 fairy_grade 才会被认，
     * 但 500223/500224 不在传说装备名单里，单独补。
     */
    private static final int[] FAIRY_EXTRA = {500223, 500224};

    private static boolean isFairyMark(int id) {
        if (isLegendEquip(id)) {
            return true;
        }
        for (int i = 0; i < FAIRY_EXTRA.length; i++) {
            if (FAIRY_EXTRA[i] == id) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFairyExtra(int id) {
        for (int i = 0; i < FAIRY_EXTRA.length; i++) {
            if (FAIRY_EXTRA[i] == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * 给传说装备补仙器标记。
     * 11.4 的仙器面板解锁条件就是装备上有 fairy_grade（16.9 在线玩家
     * 的 equips 里也是 {dataid, enhance, fairy_grade} 这种形状）。
     * 0 是最低品阶（准仙器），客户端认这个字段就会开面板。
     */
    private static void markFairyEquip(JSONObject sd) {
        if (sd == null) {
            return;
        }
        try {
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    int id = it.optInt("A2", 0);
                    // fix249 给至尊装备硬塞 fairy_grade 会导致客户端闪退；
                    // 这里把残留字段清掉，回到 fix248 的稳定状态。
                    if (isFairyExtra(id)) {
                        it.remove("fairy_grade");
                        it.remove("fairy_value");
                        continue;
                    }
                    if (!isLegendEquip(id)) {
                        continue;
                    }
                    if (!it.has("fairy_grade")) {
                        it.put("fairy_grade", 0);
                    }
                    if (!it.has("fairy_value")) {
                        it.put("fairy_value", 0);
                    }
                }
            }
            JSONArray eq = sd.optJSONArray("equips");
            if (eq != null) {
                for (int i = 0; i < eq.length(); i++) {
                    JSONObject e = eq.optJSONObject(i);
                    if (e == null) {
                        continue;
                    }
                    int id = e.optInt("dataid", 0);
                    if (isFairyExtra(id)) {
                        e.remove("fairy_grade");
                        e.remove("fairy_value");
                        continue;
                    }
                    if (!isLegendEquip(id)) {
                        continue;
                    }
                    if (!e.has("fairy_grade")) {
                        e.put("fairy_grade", 0);
                    }
                }
            }
        } catch (Throwable t) {
            trace("markFairyEquip failed " + t);
        }
    }

    private static JSONObject peakMap() {
        JSONObject m = new JSONObject();
        try {
            // 四维已经直接写进 A7..AA；这里只放“问仙专属”的五条，
            // 避免客户端把 saved.hp 再叠加一次（面板会变成双倍）。
            m.put("critical", PEAK_CRIT);
            m.put("dodge", PEAK_DODGE);
            m.put("magDef", PEAK_MAGDEF);
            m.put("godpower", PEAK_GODPOWER);
            m.put("immunity", PEAK_IMMUNITY);
        } catch (Throwable ignored) {
        }
        return m;
    }

    /**
     * 把 10 阶传说装备的峰值属性叠进物品 / 角色属性。
     * 幂等：原始四维存在 yj_peak[gid] 里，每次都用“原始 + 峰值”重算。
     */
    private static void applyPeakPray(JSONObject store) {
        if (store == null) {
            return;
        }
        if (new java.io.File("/sdcard/yj/no_peak").exists()) {
            return;
        }
        try {
            JSONObject all = store.optJSONObject("yj_peak");
            if (all == null) {
                all = new JSONObject();
                store.put("yj_peak", all);
            }
            JSONObject rd = store.optJSONObject("roledata");
            JSONObject role = rd == null ? null : rd.optJSONObject("role");
            JSONObject attr = role == null ? null : role.optJSONObject("attribute");
            if (attr == null && role != null) {
                attr = new JSONObject();
                role.put("attribute", attr);
            }
            JSONObject base = all.optJSONObject("_role");
            if (base == null) {
                base = new JSONObject();
                if (attr != null) {
                    Iterator<String> it = attr.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        base.put(k, attr.opt(k));
                    }
                }
                all.put("_role", base);
            }
            // 先恢复到原始值，再按“当前穿着的 10 阶装备”重新累加。
            String[] specs = {"critical", "dodge", "magDef", "godpower", "immunity"};
            for (int i = 0; i < specs.length; i++) {
                String k = specs[i];
                if (base.has(k)) {
                    if (attr != null) {
                        attr.put(k, base.opt(k));
                    }
                } else if (attr != null) {
                    attr.remove(k);
                }
            }
            JSONObject bag = store.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (!peakEligible(it)) {
                        continue;
                    }
                    long gid = it.optLong("A5", 0L);
                    String key = String.valueOf(gid);
                    JSONObject rec = all.optJSONObject(key);
                    if (rec == null) {
                        rec = new JSONObject();
                        rec.put("A7", it.optInt("A7", 0));
                        rec.put("A8", it.optInt("A8", 0));
                        rec.put("A9", it.optInt("A9", 0));
                        rec.put("AA", it.optInt("AA", 0));
                        all.put(key, rec);
                    }
                    it.put("A7", rec.optInt("A7", 0) + PEAK_HP);
                    it.put("A8", rec.optInt("A8", 0) + PEAK_MP);
                    it.put("A9", rec.optInt("A9", 0) + PEAK_STR);
                    it.put("AA", rec.optInt("AA", 0) + PEAK_DEF);
                    it.put("saved", peakMap());
                    it.put("BO", 1);
                    // 法宝物品本身也写神力/豁免；客户端若从物品 JSON 读属性，
                    // 法宝面板就能直接显示这两条。
                    it.put("godpower", PEAK_GODPOWER);
                    it.put("immunity", PEAK_IMMUNITY);
                    // 同时写进 yj_pray，这样读档时 savedata.prayequips 里也会带
                    // 上 equip_pray_attr -> saved，客户端若在载入阶段解析问仙
                    // 属性，九条（含神力/豁免）都能直接吃到峰值。
                    JSONObject yp = store.optJSONObject("yj_pray");
                    if (yp == null) {
                        yp = new JSONObject();
                        store.put("yj_pray", yp);
                    }
                    JSONObject st = new JSONObject();
                    st.put("degree", 10);
                    st.put("saved", peakMap());
                    st.put("unsaved", new JSONObject());
                    yp.put(key, st);
                }
            }
            store.put("yj_peak", all);
            // 把峰值同步进 savedata.prayequips（读档路径也可能用得到）。
            praySyncStore(store);
        } catch (Throwable t) {
            trace("applyPeakPray failed " + t);
        }
    }

    private static void putPeakAttr(JSONObject attr, JSONObject base, String key, int add) {
        if (attr == null) {
            return;
        }
        try {
            if (add <= 0) {
                return;
            }
            double b = base.optDouble(key, 0);
            // 角色 attribute 里的暴击/闪避/魔抗通常是小数（0.16 = 16%），
            // 而问仙表里的 9 是百分点；按原值的量纲决定加 9 还是 0.09。
            double v = b + ((b > 0 && b < 1) ? (add / 100.0) : add);
            // 存档里的角色属性都是字符串，保持同样的类型。
            attr.put(key, String.valueOf(v));
        } catch (Throwable ignored) {
        }
    }

    private static boolean prayIsPercent(String prop) {
        return "critical".equals(prop) || "dodge".equals(prop) || "magDef".equals(prop);
    }

    /** 某条属性在某阶的总上限。 */
    private static int prayCap(String prop, int degree) {
        int d = Math.max(1, Math.min(10, degree));
        if ("hp".equals(prop)) {
            return 1000 * d;
        }
        if ("mp".equals(prop)) {
            return 500 * d;
        }
        if ("str".equals(prop)) {
            return 100 * d;
        }
        if ("def".equals(prop)) {
            return 50 * d;
        }
        if (prayIsPercent(prop)) {
            return Math.min(9, d);
        }
        if ("godpower".equals(prop)) {
            return PRAY_GODPOWER[d - 1];
        }
        if ("immunity".equals(prop)) {
            return PRAY_IMMUNITY[d - 1];
        }
        return 0;
    }

    /** 单次问仙的增量上限；百分比固定 1，其余为总上限的 1/10。 */
    private static int prayStep(String prop, int degree) {
        int cap = prayCap(prop, degree);
        if (cap <= 0) {
            return 0;
        }
        if (prayIsPercent(prop)) {
            return 1;
        }
        return Math.max(1, cap / 10);
    }

    private static long prayGidOf(Object key) {
        try {
            return Long.parseLong(String.valueOf(key));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 在 bag.item（以及 equips）里按实例 id 找物品。 */
    private static JSONObject prayFindItem(JSONObject store, long gid) {
        if (store == null || gid <= 0) {
            return null;
        }
        try {
            JSONObject bag = store.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null && it.optLong("A5", 0L) == gid) {
                        return it;
                    }
                }
            }
            JSONArray eq = store.optJSONArray("equips");
            if (eq != null) {
                for (int i = 0; i < eq.length(); i++) {
                    JSONObject it = eq.optJSONObject(i);
                    if (it != null && it.optLong("A5", 0L) == gid) {
                        return it;
                    }
                }
            }
        } catch (Throwable t) {
            trace("prayFindItem failed " + t);
        }
        return null;
    }

    private static JSONObject prayCopy(JSONObject o) {
        try {
            return o == null ? new JSONObject() : new JSONObject(o.toString());
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 把 saved/unsaved 写进物品本身（客户端读档路径也会看这两个键）。 */
    private static void prayWriteItem(JSONObject item, JSONObject saved, JSONObject unsaved) {
        if (item == null) {
            return;
        }
        try {
            if (saved != null && saved.length() > 0) {
                item.put("saved", prayCopy(saved));
                item.put("BO", 1);
            } else {
                item.remove("saved");
            }
            if (unsaved != null && unsaved.length() > 0) {
                item.put("unsaved", prayCopy(unsaved));
            } else {
                item.remove("unsaved");
            }
        } catch (Throwable t) {
            trace("prayWriteItem failed " + t);
        }
    }

    /**
     * 应答条目。
     *
     * 11.4 的实际形状（0x7bd1dc 起）：数组元素是
     *   { "equip_pray_attr": { "globalitemid": <gid>, "saved": {...}, "unsaved": {...} } }
     * 客户端先取 element["equip_pray_attr"]，再在里面取 globalitemid 找物品，
     * 最后把整个 equip_pray_attr 对象交给 0xf8125c 解析 saved/unsaved。
     * 以前把 globalitemid 放在数组元素顶层，客户端取不到 equip_pray_attr，
     * 于是整条被跳过 —— 这是「保存按钮亮了但数值不变」的第二个原因。
     */
    private static JSONObject prayEntry(long gid, JSONObject item, JSONObject saved, JSONObject unsaved) {
        JSONObject e = new JSONObject();
        try {
            JSONObject attr = new JSONObject();
            attr.put("globalitemid", gid);
            if (item != null) {
                if (item.has("A2")) {
                    attr.put("A2", item.opt("A2"));
                }
                if (item.has("A5")) {
                    attr.put("A5", item.opt("A5"));
                }
            }
            if (saved != null && saved.length() > 0) {
                attr.put("saved", prayCopy(saved));
            }
            if (unsaved != null && unsaved.length() > 0) {
                attr.put("unsaved", prayCopy(unsaved));
            }
            e.put("equip_pray_attr", attr);
            // 兼容早期猜过的顶层形状；客户端只读 equip_pray_attr，多余键无害。
            e.put("globalitemid", gid);
        } catch (Throwable t) {
            trace("prayEntry failed " + t);
        }
        return e;
    }

    /**
     * 把 yj_pray 同步回物品与 savedata.prayequips。
     * 读档路径和每次存盘上传之后都调用，保证客户端序列化时丢掉这些键也能恢复。
     */
    private static void praySyncStore(JSONObject store) {
        if (store == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            JSONObject all = store.optJSONObject("yj_pray");
            if (all != null) {
                Iterator<String> it = all.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject st = all.optJSONObject(k);
                    if (st == null) {
                        continue;
                    }
                    long gid = prayGidOf(k);
                    if (gid <= 0) {
                        continue;
                    }
                    JSONObject item = prayFindItem(store, gid);
                    JSONObject saved = st.optJSONObject("saved");
                    JSONObject unsaved = st.optJSONObject("unsaved");
                    if (item != null) {
                        prayWriteItem(item, saved, unsaved);
                    }
                    JSONObject e = prayEntry(gid, item, saved, unsaved);
                    JSONObject attr = e.optJSONObject("equip_pray_attr");
                    if (attr != null && (attr.has("saved") || attr.has("unsaved"))) {
                        arr.put(e);
                    }
                }
            }
            store.put("prayequips", arr);
        } catch (Throwable t) {
            trace("praySyncStore failed " + t);
        }
    }

    /** /sdcard/yj/pray_resp.json：调试用，整段 prayequips 应答原样返回。 */
    private static JSONArray readPrayRespOverride() {
        try {
            java.io.File f = new java.io.File("/sdcard/yj/pray_resp.json");
            if (!f.exists() || f.length() == 0) {
                return null;
            }
            String s = new String(readAll(new java.io.FileInputStream(f)), "UTF-8").trim();
            if (s.startsWith("[")) {
                return new JSONArray(s);
            }
            JSONObject o = new JSONObject(s);
            return o.optJSONArray("prayequips");
        } catch (Throwable t) {
            return null;
        }
    }

    /** /sdcard/yj/pray_probe.json：调试用，直接作为本次 unsaved 返回。 */
    private static JSONObject readPrayProbe() {        try {
            java.io.File f = new java.io.File("/sdcard/yj/pray_probe.json");
            if (!f.exists() || f.length() == 0) {
                return null;
            }
            JSONObject o = new JSONObject(new String(readAll(new java.io.FileInputStream(f)), "UTF-8"));
            if (o.optJSONObject("unsaved") != null) {
                return o.optJSONObject("unsaved");
            }
            if (o.optJSONObject("saved") != null) {
                return o.optJSONObject("saved");
            }
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 连抽 rolls 次，返回本次预览（属性名 -> 新的总值）。 */
    private static JSONObject prayRoll(JSONObject saved, int degree, int rolls) {
        JSONObject pending = new JSONObject();
        try {
            for (int n = 0; n < rolls; n++) {
                java.util.ArrayList<String> avail = new java.util.ArrayList<String>();
                for (int i = 0; i < PRAY_PROPS.length; i++) {
                    String p = PRAY_PROPS[i];
                    int cap = prayCap(p, degree);
                    int cur = Math.max(saved.optInt(p, 0), pending.optInt(p, 0));
                    if (cap > cur) {
                        avail.add(p);
                    }
                }
                if (avail.isEmpty()) {
                    break;
                }
                String p = avail.get(PRAY_RND.nextInt(avail.size()));
                int cap = prayCap(p, degree);
                int step = prayStep(p, degree);
                int cur = Math.max(saved.optInt(p, 0), pending.optInt(p, 0));
                int add = 1 + PRAY_RND.nextInt(Math.max(1, step));
                pending.put(p, Math.min(cap, cur + add));
            }
        } catch (Throwable t) {
            trace("prayRoll failed " + t);
        }
        return pending;
    }

    private static void fillPrayEquip(String action, JSONObject req, JSONObject r) throws Exception {
        JSONArray override = readPrayRespOverride();
        if (override != null) {
            r.put("prayequips", override);
            logLine("PRAY override " + action + " " + override);
            return;
        }
        JSONArray a = new JSONArray();
        long gid = req.optLong("globalitemid", 0L);
        boolean isSave = action != null && action.contains("save");
        int rolls = Math.max(1, Math.min(10, req.optInt("type", 1)));
        synchronized (PRAY_LOCK) {
            try {
                JSONObject store = readSaveObj();
                JSONObject item = prayFindItem(store, gid);
                if (gid > 0 && item != null) {
                    int dataid = item.optInt("A2", req.optInt("goodsid", 0));
                    if (dataid == 500026) {
                        // 诛仙剑阵不能问仙（客户端也不让它出现在列表里，这里兜底）。
                        logLine("PRAY refused sword-array gid=" + gid);
                    } else {
                        int degree = (int) Math.round(req.optDouble("degree", 0));
                        if (degree <= 0) {
                            degree = item.optInt("AV", 1);
                        }
                        degree = Math.max(1, Math.min(10, degree));

                        JSONObject all = store.optJSONObject("yj_pray");
                        if (all == null) {
                            all = new JSONObject();
                            store.put("yj_pray", all);
                        }
                        String key = String.valueOf(gid);
                        JSONObject st = all.optJSONObject(key);
                        if (st == null) {
                            st = new JSONObject();
                        }
                        JSONObject saved = prayCopy(st.optJSONObject("saved"));
                        JSONObject unsaved = prayCopy(st.optJSONObject("unsaved"));
                        int outDegree = degree;
                        if (isSave) {
                            if (req.optInt("cancel_save", 0) == 1) {
                                unsaved = new JSONObject();
                            } else {
                                Iterator<String> it = unsaved.keys();
                                while (it.hasNext()) {
                                    String p = it.next();
                                    saved.put(p, unsaved.opt(p));
                                }
                                unsaved = new JSONObject();
                            }
                        } else {
                            JSONObject probe = readPrayProbe();
                            if (probe != null) {
                                unsaved = prayCopy(probe);
                            } else {
                                unsaved = prayRoll(saved, degree, rolls);
                            }
                            outDegree = degree;
                        }
                        st.put("degree", outDegree);
                        st.put("saved", saved);
                        st.put("unsaved", unsaved);
                        all.put(key, st);
                        prayWriteItem(item, saved, unsaved);
                        praySyncStore(store);
                        writeSave(store.toString());
                        a.put(prayEntry(gid, item, saved, unsaved));
                        logLine("PRAY " + action + " gid=" + gid + " item=" + dataid
                                + " degree=" + outDegree + " saved=" + saved
                                + " unsaved=" + unsaved + " rolls=" + rolls);
                    }
                }
            } catch (Throwable t) {
                trace("fillPrayEquip failed " + t);
            }
        }
        r.put("prayequips", a);
    }
    private static JSONArray findArray(Object o, String name) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONArray a = j.optJSONArray(name);
            if (a != null) {
                return a;
            }
            Iterator<String> it = j.keys();
            while (it.hasNext()) {
                JSONArray r = findArray(j.opt(it.next()), name);
                if (r != null) {
                    return r;
                }
            }
        } else if (o instanceof JSONArray) {
            JSONArray arr = (JSONArray) o;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray r = findArray(arr.opt(i), name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void fillGuildBuilding(String action, JSONObject r) throws Exception {
        r.put("type", 2);
        JSONObject g = guildPayload();
        Iterator<String> it = g.keys();
        while (it.hasNext()) {
            String k = it.next();
            r.put(k, g.get(k));
        }
        r.put("buildinglist", guildBuildingList());
        trace("GUILD building " + action + " -> lv" + GUILD_BUILDING_LEVEL);
    }

    /**
     * 打开某个建筑面板（gbuildinginfo）。
     *
     * _handleGuildBuildingInfoResponse @0x681d08 的 type==1 分支会按请求里记下的
     * 建筑类型分发到各自的面板刷新函数（议事楼/祈福/仓库/商店/副本/任务/祭坛），
     * 并且把那个类型挂到通知上，UI 才会真的把面板弹出来 —— 这就是为什么
     * 回 type=2 只会得到「仙盟建筑信息已刷新」却进不去。
     *
     * 各面板刷新函数都直接拿**整份应答**去反序列化自己那个子对象，所以这里把
     * 能给出的键一次性都放上，客户端只挑自己要的那个。
     */
    private static void fillGuildBuildingInfo(JSONObject req, JSONObject r) throws Exception {
        int bid = req.optInt("buildingid", 0);
        r.put("type", 1);
        JSONObject g = guildPayload();
        Iterator<String> it = g.keys();
        while (it.hasNext()) {
            String k = it.next();
            r.put(k, g.get(k));
        }
        r.put("buildinglist", guildBuildingList());
        r.put("totalremaintimes", 99);
        r.put("maxtotalremaintimes", 99);
        r.put("hall", guildBuildingEntry(GUILD_BUILDING_BASE));
        r.put("dungeontimesremain", 99);
        // 各面板只认自己那个键，其余给空数组（缺键会让 operator[] 拿到 null）
        r.put("goods", new JSONArray());
        r.put("list", new JSONArray());
        r.put("levellist", new JSONArray());
        r.put("skilllist", new JSONArray());
        r.put("prayequips", new JSONArray());
        if (bid == 601003) {            // 商店
            r.put("goods", guildStoreGoods());
        } else if (bid == 601001) {     // 祈福
            r.put("list", guildBlessList());
        } else if (bid == 601005) {     // 副本
            r.put("levellist", guildDungeonList());
        } else if (bid == 601006) {     // 祭坛（含问仙）
            r.put("skilllist", guildAltarSkills());
        }
        logLine("GUILD buildinginfo " + bid + " type1 goods=" + r.optJSONArray("goods").length()
                + " list=" + r.optJSONArray("list").length()
                + " levellist=" + r.optJSONArray("levellist").length()
                + " skilllist=" + r.optJSONArray("skilllist").length());
    }

    /**
     * 渡劫：把客户端请求里的原装备换成 equip 表的 legend_id 对应那件传说装备。
     *
     * 关键点（都来自 0x6a69d0 createItemByJson 的汇编）：
     *   1. 先看 "A2"，拿它去 YJDataManager::queryTypeById 查类型；
     *      查不到（返回 0）就直接 return null —— 之前几版把 A2 填成
     *      newmaxglobalitemid(904231)，那不是 equip 表里的行，必然失败。
     *   2. A2 的类型等于 0xc(12=装备) 时再读 "A5" 当实例 id，传给 createItemByID。
     *   3. 建好之后 item->vtable[0x20](json) 反序列化属性，返回 0 就销毁并 return null。
     * 而应答处理函数 @0x52eb3c 无论建没建成都会把原装备从背包删掉，
     * 所以只要这里给的 target 不合法，玩家的装备就是「消失」。
     *
     * 兜底：取不到 legend_id、或请求里没有 target 时回 isthrough:0，
     * 客户端在失败分支不动背包，装备一定保得住。
     */
    private static void fillCrossDisaster(JSONObject req, JSONObject r) throws Exception {
        JSONObject reqTarget = req.optJSONObject("target");
        int dataid = reqTarget == null ? 0 : reqTarget.optInt("A2", reqTarget.optInt("dataid", 0));
        int legend = legendOf(dataid);
        long gid = req.optLong("newmaxglobalitemid", 0L);

        if (reqTarget == null || legend <= 0 || gid <= 0) {
            r.put("isthrough", 0);
            r.put("probability", 0);
            logLine("CROSSDISASTER refused dataid=" + dataid + " legend=" + legend + " gid=" + gid);
            return;
        }

        JSONObject t = new JSONObject();
        // A2 必须是传说装备在 equip 表里的行 id
        t.put("A2", legend);
        t.put("dataid", legend);          // 部分 createItemByJson 调用点走 dataid（宠物分支）
        t.put("A4", 1);                   // 数量
        t.put("A5", gid);                 // 新的实例 id
        t.put("A6", 0);
        t.put("B7", 0);
        // 新生成的传说装备：阶级归零，品质按 equip 表的 grade 走。
        // 注意：品质是 CF，不是 AF —— AF 是「暴击」，上一版把它写成 grade，
        // 渡劫后暴击就被改成品阶数字了（用户反馈「渡劫之后属性不对」）。
        t.put("AV", 0);
        t.put("CF", equipGrade(legend));
        // 原装备的随机属性原样带过去。
        // YJEquipItem::copyAttribute @0x6a2fd8 对每一条属性做
        //     setAttribute(传说, 名字, 原值 * ratio)
        // ratio 在 渡劫度<=7 时是 1.0，越往上每级再加一点（0x52e940 里
        // (degree-7)*K + 0.5 那段），也就是「按原装备属性继承 / 放大」。
        // 所以把这些属性槽原样回填，就是客户端本来会算出来的结果。
        String[] carry = {"A7", "A8", "A9", "AA", "AB", "AN", "AO", "AQ", "AT", "AW",
                "AF", "CH", "CI", "CJ", "CK"};
        for (int i = 0; i < carry.length; i++) {
            if (reqTarget.has(carry[i])) {
                t.put(carry[i], reqTarget.get(carry[i]));
            }
        }
        JSONArray bg = reqTarget.optJSONArray("BG");
        t.put("BG", bg != null ? bg : emptySlots());

        r.put("isthrough", 1);
        r.put("probability", 100);
        r.put("target", t);
        logLine("CROSSDISASTER ok dataid=" + dataid + " -> legend " + legend
                + " gid=" + gid + " target=" + t);
    }

    private static void fillCrossDisasterInfo(JSONObject r) throws Exception {
        // 客户端按 probbaseequip + probincreaseequip（装备）+ probbasestone +
        // probincreasestone（渡劫石）算面板上的成功率。全给 0 时面板显示 0%，
        // 看起来就像「渡劫坏了」。这里直接给满，和 fillCrossDisaster 里
        // 永远 isthrough=1 保持一致。
        r.put("probability", 100);
        r.put("probbaseequip", 100);
        r.put("probincreaseequip", 0);
        r.put("probbasestone", 0);
        r.put("probincreasestone", 0);
        trace("crossdisasterinfo 100%");
    }

    private static JSONArray emptySlots() {
        JSONArray bg = new JSONArray();
        for (int i = 0; i < 4; i++) {
            JSONObject g = new JSONObject();
            try {
                g.put("BH", -1);
                g.put("BI", 0);
                g.put("BJ", 0);
            } catch (Exception ignored) {
            }
            bg.put(g);
        }
        return bg;
    }

    /** equip 表里这一行的 legend_id（渡劫后的传说装备 id），没有就 0。 */
    private static int legendOf(int dataid) {
        try {
            JSONObject tab = table("equip");
            if (tab == null) {
                return 0;
            }
            JSONObject row = tab.optJSONObject(String.valueOf(dataid));
            return row == null ? 0 : row.optInt("legend_id", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int equipGrade(int dataid) {
        try {
            JSONObject tab = table("equip");
            if (tab == null) {
                return 0;
            }
            JSONObject row = tab.optJSONObject(String.valueOf(dataid));
            return row == null ? 0 : row.optInt("grade", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------- 魂器品质装备改成八阶
    //
    // 「魂器」= equip 表里 cross_disaster_degree == 7 的那 46 件（介绍写「器中之魂，
    // 充满未知变数，可在炼丹炉中进阶」），也就是能渡劫成传说的那批。
    // 装备物品 JSON 里的 AV 就是阶数（1310 虬龙棍 7 阶时 AV=7，渡劫门槛也是 7）。
    // 这里把背包里所有魂器的 AV 统一写成 8。

    private static final int SOUL_DEGREE = 8;

    private static boolean isSoulEquip(int dataid) {
        try {
            JSONObject tab = table("equip");
            if (tab == null) {
                return false;
            }
            JSONObject row = tab.optJSONObject(String.valueOf(dataid));
            return row != null && row.optInt("cross_disaster_degree", -1) == 7;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把背包里的魂器装备阶数写成八阶。幂等：已经是 8 的不动。
     * 上传和下发两个方向都调用，客户端下次登录读到的就是八阶。
     */
    private static void normalizeSoulDegrees(JSONObject bag) {
        if (bag == null) {
            return;
        }
        JSONArray items = bag.optJSONArray("item");
        if (items == null) {
            return;
        }
        int n = 0;
        int seen = 0;
        int firstCd = -999;
        int firstId = 0;
        StringBuilder saw = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            try {
                JSONObject e = items.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                int dataid = e.optInt("A2", e.optInt("dataid", 0));
                if (dataid <= 0) {
                    continue;
                }
                JSONObject tab = table("equip");
                JSONObject row = tab == null ? null : tab.optJSONObject(String.valueOf(dataid));
                if (row == null) {
                    continue;
                }
                seen++;
                saw.append(dataid).append(":cd").append(row.optInt("cross_disaster_degree", -999))
                        .append(":av").append(e.optInt("AV", -1)).append(' ');
                if (firstId == 0) {
                    firstId = dataid;
                    firstCd = row.optInt("cross_disaster_degree", -999);
                }
                if (row.optInt("cross_disaster_degree", -1) != 7) {
                    continue;
                }
                if (e.optInt("AV", -1) != SOUL_DEGREE) {
                    e.put("AV", SOUL_DEGREE);
                    n++;
                }
            } catch (Throwable ignored) {
            }
        }
        logLine("SOUL items=" + items.length() + " equipRows=" + seen + " hits=" + n);
    }

    /**
     * 用户从神秘商店买到的四把「诛仙」法宝（利/亡/屠/锋）会让客户端闪退：
     * 它们是 magic.sdb type=12 的法宝，不能按普通 itemJson 发到背包。
     * 这里在存档上传/下发两个方向都硬删，并把它们从商店货架排除。
     */
    private static final int[] BROKEN_SWORD_IDS = {500013, 500014, 500015, 500016};

    private static boolean isBrokenSword(int id) {
        for (int i = 0; i < BROKEN_SWORD_IDS.length; i++) {
            if (BROKEN_SWORD_IDS[i] == id) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------- 四把仙剑法宝
    //
    // 利·诛仙剑 500016 / 亡·戮仙剑 500015 / 锋·绝仙剑 500014 / 屠·陷仙剑 500013
    // 都是 magic.sdb 里 type=12 的**法宝**，必须按法宝的物品形状发（AV=阶数、
    // AB=成长率×100、AQ=五行位掩码、BG=孔位数组），按普通 itemJson 发会闪退。
    //
    // 形状是照存档里那把能用的法宝 500018(实例 509335) 抄的：
    //   {"A2":500018,"A5":509335,"A6":1,"A7":391,"A8":87,"A9":159,"AA":161,
    //    "AB":400,"AN":2,"AQ":15,"AV":10,"AW":500,"BG":[4孔],"CE":27,
    //    "CV":100,"D2":0,"D3":1,"D4":0,"D5":0}
    // 五行位序是「金/木/水/火/土」（libgame 0x1c025a8），所以
    //   金水火 = 1|4|8 = 13。
    private static final int[] SWORD_IDS = {500016, 500015, 500014, 500013};
    private static final int SWORD_GID_BASE = 509338;  // 邮件里这四件的实例 id
    // 法灵精魄（item 表 type=11 道具，用于诛仙剑阵合成/升级）
    private static final int ITEM_FALING = 503340;
    private static final int ITEM_FALING_COUNT = 5000;
    private static final int SWORD_DEGREE = 10;      // 10 阶
    private static final int SWORD_SLOTS = 5;        // 5 孔
    private static final int SWORD_GROWTH = 400;     // 成长率 4.0（×100）
    // 五行金：
    //   · 显示走 AN/AO（`交接文档 §4.2`：(AN=2,AO=0)=金），
    //   · AQ 是五行位掩码，金=1（位序 金/木/水/火/土）。
    // 以前 AQ=13（金|水|火）在融合/继承时会被当成三属性。
    private static final int SWORD_ELEMENT = 1;      // 金
    private static final int SWORD_AN = 2;           // 底色金
    private static final int SWORD_AO = 0;           // 组合序号 0 = 单金

    /** 法宝物品 JSON：属性取 magic 表的 *_min，其余槽位照存档样本。 */
    private static JSONObject swordJson(int id, int gid) {
        JSONObject o = new JSONObject();
        JSONObject row = null;
        try {
            if (table("magic") != null) {
                row = table("magic").optJSONObject(String.valueOf(id));
            }
        } catch (Throwable ignored) {
        }
        try {
            o.put("A2", id);
            o.put("A4", 1);
            o.put("A5", gid);
            o.put("A6", 1);
            o.put("A7", row != null ? row.optInt("hp_min", 200) : 200);
            o.put("A8", row != null ? row.optInt("mp_min", 60) : 60);
            o.put("A9", row != null ? row.optInt("str_min", 150) : 150);
            o.put("AA", row != null ? row.optInt("def_min", 70) : 70);
            o.put("AB", SWORD_GROWTH);
            o.put("AN", SWORD_AN);
            o.put("AO", SWORD_AO);
            o.put("AQ", SWORD_ELEMENT);
            o.put("AT", 0);
            o.put("AV", SWORD_DEGREE);
            o.put("AW", 500);
            JSONArray bg = new JSONArray();
            for (int i = 0; i < SWORD_SLOTS; i++) {
                JSONObject g = new JSONObject();
                g.put("BH", 0);          // 5 个孔全部算已开、空孔（-1 = 未开孔）
                g.put("BI", 0);
                g.put("BJ", 0);
                g.put("CB", 0);
                bg.put(g);
            }
            o.put("BG", bg);
            o.put("B8", 0);
            o.put("CD", 0);
            o.put("CE", 27);
            o.put("CG", 0);
            o.put("CV", 100);
            o.put("D2", 0);
            o.put("D3", 1);
            o.put("D4", 0);
            o.put("D5", 0);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 把背包里已有的四把仙剑就地改成「10 阶 / 5 孔 / 五行金」。
     *
     *  用户已经在收过邮件的角色上拿到了这四把，单纯改掉落参数不会动老物品，
     *  所以每次下发存档前统一刷一遍（幂等）。
     */
    private static void normalizeSwords(JSONObject sd) {
        try {
            JSONObject bag = sd == null ? null : sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items == null) {
                return;
            }
            int fixed = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                int id = o.optInt("A2", 0);
                boolean isSword = false;
                for (int k = 0; k < SWORD_IDS.length; k++) {
                    if (SWORD_IDS[k] == id) {
                        isSword = true;
                        break;
                    }
                }
                if (!isSword) {
                    continue;
                }
                o.put("AB", SWORD_GROWTH);
                o.put("AN", SWORD_AN);
                o.put("AO", SWORD_AO);
                o.put("AQ", SWORD_ELEMENT);
                o.put("AV", SWORD_DEGREE);
                // 保孔里已镶嵌的宝石：老 BG 里 BH>0 的是宝石，原样搬过去；
                // 空孔/未开孔(-1) 统一成 0（已开空孔），补足 5 个。
                JSONArray oldBg = o.optJSONArray("BG");
                JSONArray bg = new JSONArray();
                for (int k = 0; k < SWORD_SLOTS; k++) {
                    JSONObject g = new JSONObject();
                    JSONObject src = oldBg == null ? null : oldBg.optJSONObject(k);
                    int gem = src == null ? 0 : src.optInt("BH", 0);
                    g.put("BH", gem > 0 ? gem : 0);
                    g.put("BI", src == null ? 0 : src.optInt("BI", 0));
                    g.put("BJ", src == null ? 0 : src.optInt("BJ", 0));
                    g.put("CB", src == null ? 0 : src.optInt("CB", 0));
                    bg.put(g);
                }
                o.put("BG", bg);
                fixed++;
            }
            if (fixed > 0) {
                trace("normalizeSwords: " + fixed + " 把 -> 10阶/5孔/金");
            }
        } catch (Throwable t) {
            trace("normalizeSwords failed " + t);
        }
    }

    /** 诛仙剑阵（500026）：继承嵌入的每把仙剑 50% 的生命/魔法/攻击/防御。
     *
     *  正常服是客户端在「嵌入」时算好写进剑阵的 A7~AA；离线版这一步没发生，
     *  剑阵一直只是 magic 表的基础值（550/254/342/200）。这里在每次下发前
     *  重新算一遍（幂等：base + 50%×Σ剑），不会越算越多。
     */
    private static void applySwordArrayInherit(JSONObject bag) {
        try {
            JSONArray items = bag.optJSONArray("item");
            if (items == null) {
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject arr = items.optJSONObject(i);
                if (arr == null || arr.optInt("A2", 0) != 500026) {
                    continue;
                }
                JSONArray bw = arr.optJSONArray("BW");
                if (bw == null) {
                    continue;
                }
                long hp = 0, mp = 0, str = 0, def = 0;
                int n = 0;
                for (int k = 0; k < bw.length(); k++) {
                    JSONObject s = bw.optJSONObject(k);
                    JSONObject sw = s == null ? null : s.optJSONObject("C1");
                    if (sw == null) {
                        continue;
                    }
                    hp += sw.optLong("A7", 0);
                    mp += sw.optLong("A8", 0);
                    str += sw.optLong("A9", 0);
                    def += sw.optLong("AA", 0);
                    n++;
                }
                JSONObject row = null;
                if (table("magic") != null) {
                    row = table("magic").optJSONObject("500026");
                }
                long bh = row != null ? row.optInt("hp_min", 500) : 500;
                long bm = row != null ? row.optInt("mp_min", 180) : 180;
                long bs = row != null ? row.optInt("str_min", 270) : 270;
                long bd = row != null ? row.optInt("def_min", 120) : 120;
                arr.put("A7", bh + hp / 2);
                arr.put("A8", bm + mp / 2);
                arr.put("A9", bs + str / 2);
                arr.put("AA", bd + def / 2);
                trace("剑阵继承: 剑" + n + "把 -> A7..AA=" + arr.optLong("A7", 0) + "/"
                        + arr.optLong("A8", 0) + "/" + arr.optLong("A9", 0) + "/" + arr.optLong("AA", 0));
                continue;
            }
        } catch (Throwable t) {
            trace("applySwordArrayInherit failed " + t);
        }
    }

    /** 登录次数落盘（一次启动 +1，只数「选角进服」那一阶段）。 */
    private static java.io.File loginCountFile() {
        return new java.io.File(dir(), "logincount");
    }

    private static synchronized int loginCount() {
        try {
            java.io.File f = loginCountFile();
            if (!f.exists()) {
                return 0;
            }
            byte[] b = readAll(new java.io.FileInputStream(f));
            return Integer.parseInt(new String(b, "UTF-8").trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static synchronized void bumpLoginCount() {
        try {
            int n = loginCount() + 1;
            java.io.FileOutputStream fo = new java.io.FileOutputStream(loginCountFile());
            fo.write(String.valueOf(n).getBytes("UTF-8"));
            fo.close();
            trace("LOGIN count=" + n);
        } catch (Throwable t) {
            trace("bumpLoginCount failed " + t);
        }
    }

    /** 「第二次登录奖励已发」标记。必须落成文件：
     *  放在 bag 里的自定义键会被客户端上传存档时丢掉，导致每次登录重发。
     *
     *  **按角色分别记**：以前是一个全局 `swordgiven`，只有第一个登录的角色能拿到
     *  四把仙剑，换角色就没有了。现在每个 rid 一个标记文件，每个角色各发一次。 */
    private static java.io.File swordGivenFile() {
        int rid = sCurRid > 0 ? sCurRid : 1;
        return new java.io.File(dir(), "swordgiven_" + rid);
    }

    private static boolean swordGiven() {
        return swordGivenFile().exists();
    }

    private static void setSwordGiven() {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(swordGivenFile());
            fo.write(1);
            fo.close();
        } catch (Throwable t) {
            trace("setSwordGiven failed " + t);
        }
    }

    /**
     * 第二次登录发放：四把仙剑 + 5000 法灵精魄。
     * 只发一次（bag.givensword 落盘），玩家丢了卖了不会自动补。
     */
    private static void grantSwordsOnce(JSONObject sd) {
        try {
            JSONObject bag = sd.optJSONObject("bag");
            if (bag == null) {
                return;
            }
            if (swordGiven()) {
                return;
            }
            JSONArray items = bag.optJSONArray("item");
            if (items == null) {
                items = new JSONArray();
                bag.put("item", items);
            }
            int gid = bag.optInt("maxglobalitemid", 509337);
            int added = 0;
            for (int i = 0; i < SWORD_IDS.length; i++) {
                int sid = SWORD_IDS[i];
                boolean have = false;
                for (int k = 0; k < items.length(); k++) {
                    JSONObject o = items.optJSONObject(k);
                    if (o != null && o.optInt("A2", 0) == sid) {
                        have = true;
                        break;
                    }
                }
                if (have) {
                    continue;
                }
                gid++;
                items.put(swordJson(sid, gid));
                added++;
            }
            // 法灵精魄 503340 × 5000（诛仙剑阵合成/升级用）
            // 幂等：已经够数就不再补，避免因为标记文件丢失而重复发放。
            int haveFaling = 0;
            for (int k = 0; k < items.length(); k++) {
                JSONObject o = items.optJSONObject(k);
                if (o != null && o.optInt("A2", 0) == ITEM_FALING) {
                    haveFaling += o.optInt("A4", 0);
                }
            }
            if (haveFaling < ITEM_FALING_COUNT) {
                items.put(itemJson(ITEM_FALING, ITEM_FALING_COUNT - haveFaling));
                trace("法灵精魄 shortfall " + (ITEM_FALING_COUNT - haveFaling));
            }
            bag.put("maxglobalitemid", gid);
            sd.put("bag", bag);
            setSwordGiven();
            trace("swords granted: " + added + " + 法灵精魄×" + ITEM_FALING_COUNT + " maxgid=" + gid);
        } catch (Throwable t) {
            trace("grantSwordsOnce failed " + t);
        }
    }

    private static void stripBrokenSwords(JSONObject sd) {
        if (sd == null) {
            return;
        }
        int removed = 0;
        try {
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items != null) {
                for (int i = items.length() - 1; i >= 0; i--) {
                    JSONObject e = items.optJSONObject(i);
                    if (e == null) {
                        continue;
                    }
                    int id = e.optInt("A2", e.optInt("dataid", 0));
                    boolean brokenMagic = id >= 500000 && id <= 500030
                            && e.optInt("A5", 0) == id
                            && e.optInt("A9", -1) == 8
                            && !e.has("AN");
                    if (brokenMagic) {
                        items.remove(i);
                        removed++;
                    }
                }
            }
            JSONArray eq = sd.optJSONArray("equips");
            if (eq != null) {
                for (int i = eq.length() - 1; i >= 0; i--) {
                    JSONObject e = eq.optJSONObject(i);
                    if (e == null) {
                        continue;
                    }
                    int id = e.optInt("dataid", e.optInt("A5", e.optInt("A2", 0)));
                    if (isBrokenSword(id) || (id >= 500000 && id <= 500030)) {
                        eq.remove(i);
                        removed++;
                    }
                }
            }
            // 法宝槽：artifactinfo.dataid 如果还是这四把剑之一，客户端会在
            // 渲染角色法宝时去背包找实例，找不到就 SIGSEGV。重置成合法法宝。
            JSONObject rd = sd.optJSONObject("roledata");
            JSONObject role = rd == null ? null : rd.optJSONObject("role");
            if (role != null) {
                JSONObject art = role.optJSONObject("artifactinfo");
                if (art != null && isBrokenSword(art.optInt("dataid", 0))) {
                    art.put("dataid", 509100);
                    art.put("level", 1);
                    art.put("state", 0);
                    art.put("exphp", 0);
                    art.put("expmp", 0);
                    art.put("expdef", 0);
                    art.put("expstr", 0);
                    art.put("expspecial", 0);
                    role.put("isartifact", false);
                    removed++;
                    trace("stripBrokenSwords: artifact slot reset");
                }
                int[] fields = {role.optInt("weaponid", 0), role.optInt("nweaponid", 0),
                        role.optInt("suitid", 0), role.optInt("rideid", 0)};
                String[] names = {"weaponid", "nweaponid", "suitid", "rideid"};
                for (int i = 0; i < fields.length; i++) {
                    if (isBrokenSword(fields[i])) {
                        role.put(names[i], 0);
                        removed++;
                    }
                }
            }
            JSONArray pray = sd.optJSONArray("prayequips");
            if (pray != null) {
                for (int i = pray.length() - 1; i >= 0; i--) {
                    JSONObject e = pray.optJSONObject(i);
                    if (e == null) {
                        continue;
                    }
                    int id = e.optInt("dataid", e.optInt("A2", e.optInt("id", 0)));
                    if (isBrokenSword(id)) {
                        pray.remove(i);
                        removed++;
                    }
                }
            }
            // 真灵现在按用户要求放进 pet.petinfo，这里不再删除 9005。
        } catch (Throwable t) {
            trace("stripBrokenSwords failed " + t);
        }
        if (removed > 0) {
            trace("stripBrokenSwords removed " + removed);
        }
    }

    /**
     * 一次性清空背包（用户要求）：存在 /sdcard/yj/clear_bag 且没有
     * /sdcard/yj/clear_bag_done 时，把 bag.item 清空、卸下全部装备、
     * 去掉法宝槽，然后写 done。上传/下发两个方向都调用，避免客户端
     * 把旧背包又传回来。
     */
    private static void clearBagOnce(JSONObject sd) {
        if (sd == null) {
            return;
        }
        java.io.File flag = new java.io.File("/sdcard/yj/clear_bag");
        if (!flag.exists()) {
            return;
        }
        try {
            JSONObject bag = sd.optJSONObject("bag");
            if (bag != null) {
                bag.put("item", new JSONArray());
            }
            sd.put("equips", new JSONArray());
            sd.remove("removedequips");
            sd.put("saled_equips_list", new JSONArray());
            sd.put("prayequips", new JSONArray());
            JSONObject rd = sd.optJSONObject("roledata");
            JSONObject role = rd == null ? null : rd.optJSONObject("role");
            if (role != null) {
                role.remove("artifactinfo");
                role.put("isartifact", false);
            }
            trace("CLEARBAG done: bag.item/equips/artifactinfo cleared");
        } catch (Throwable t) {
            trace("CLEARBAG failed " + t);
        }
    }

    private static boolean isLogin(String a) {
        // NOTE: roledata2 must NOT be listed here. It is the save download
        // (YJSaveLoadManager::requestSaveDataFromServer sends {"rid","service"}
        // under that action); its reply has to be the save, not a login payload.
        return "login".equals(a) || "pplogin".equals(a) || "loginpolling".equals(a)
                || "bindguest".equals(a) || "inituser".equals(a) || "initvillage".equals(a);
    }

    // ------------------------------------------------------------- shops
    //
    // The wire shapes are forced by the client (file VA):
    //
    //   YJStoreManager::_handleItemListRequestSuccess @0x421130   (商城)
    //       "point" is read FIRST and the handler throws YJError
    //       "REQUEST_ITEM_LIST" when it is absent; then
    //       "item" / "stone" / "petitem" / "fashion" must be ARRAYS of store
    //       items (each parsed by createStoreItemFromJson @0x41f580), and
    //       "kinds" is an OBJECT of sub-tab names (skipped when absent).
    //
    //   YJStoreManager::_handleMystItemListRequestSuccess @0x41f7c8 (神秘店铺)
    //       "goods" is required and must be an ARRAY; "point" is optional.
    //
    //   createStoreItemFromJson reads: id (-> YJStoreItem::create), price,
    //       orignprice, currentprice, category, num, kind, attr (a preview item
    //       object, optional - YJStoreItem::init(id) already loads the table row).
    //
    //   YJStoreManager::_handlePurchaseRequestSuccess @0x41fc78 takes the bought
    //       thing from "goods": an OBJECT is createItemByJson'ed and
    //       addItemToBag'ed, an ARRAY is iterated the same way. createItemByJson
    //       @0x4f6290 only requires "A2" (type lookup) and "A5" (item id).

    private static final java.util.Map<String, JSONObject> sTables =
            new java.util.HashMap<String, JSONObject>();
    private static JSONArray sShopItem, sShopStone, sShopPetItem, sMystGoods;

    private static boolean isShopList(String a) {
        // 4.1.1 renamed the mystery-shop action to "mysteryshop2" (3.0.2 used the
        // bare "mysteryshop"), so match on the prefix: an exact match sends it to
        // the generic reply, and YJStoreManager::_handleMystItemListRequestSuccess
        // then throws REQUEST_ITEM_LIST because "goods" is missing -> empty shop.
        //
        // 但 "*buy" 是购买请求（mysteryshopbuy / shopbuy），必须走 fillBuy，
        // 否则它会被当成"列表请求"回一份 goods 数组，客户端拿不到买到的道具。
        if (a.endsWith("buy") || a.contains("buy")) {
            return false;
        }
        return "shop".equals(a) || "storelist".equals(a) || "itemlist".equals(a)
                || a.startsWith("mysteryshop") || a.startsWith("refreshmysteryshop");
    }

    /** Pet list dumped by libmod after the welfare handout, or null. */
    private static JSONArray readDumpedPets() {
        try {
            java.io.File f = new java.io.File(dir(), "pets.json");
            if (!f.exists() || f.length() < 3) {
                return null;
            }
            String txt = new String(readAll(f), "UTF-8");
            JSONArray a = new JSONArray(txt);
            return a.length() > 0 ? a : null;
        } catch (Throwable t) {
            trace("readDumpedPets failed " + t);
            return null;
        }
    }


    /** Decrypt one assets/database/*.sdb table: base64(DES-ECB/PKCS5), key "43((zmIp". */
    /** 关卡 id 是否存在于 11.4 的 sublevel / level 表。 */
    private static boolean levelExists(int id) {
        if (id <= 0) {
            return false;
        }
        String key = String.valueOf(id);
        JSONObject sub = table("sublevel");
        if (sub != null && sub.has(key)) {
            return true;
        }
        JSONObject lv = table("level");
        return lv != null && lv.has(key);
    }

    private static synchronized JSONObject table(String name) {
        if (sTables.containsKey(name)) {
            return sTables.get(name);
        }
        JSONObject out = null;
        try {
            byte[] raw = readAll(sCtx.getAssets().open("database/" + name + ".sdb"));
            // The .sdb files are base64 wrapped at ~64 columns, so every line
            // break has to go before decoding: android.util.Base64 does not skip
            // them and the block alignment shifts -> BadPaddingException.
            String b64 = new String(raw, "UTF-8").replaceAll("[\\s\\u0000]+", "");
            int pad = (4 - b64.length() % 4) % 4;
            if (pad > 0) {
                StringBuilder sb = new StringBuilder(b64);
                for (int i = 0; i < pad; i++) {
                    sb.append('=');
                }
                b64 = sb.toString();
            }
            byte[] plain = Des.decrypt(b64.getBytes("US-ASCII"));
            out = new JSONObject(new String(plain, "UTF-8"));
            trace("table " + name + " rows=" + out.length());
        } catch (Throwable t) {
            trace("table " + name + " failed " + t);
        }
        sTables.put(name, out);
        return out;
    }

    /**
     * Append every row of the given tables as a store entry.
     *
     * spirit_cost is 0 on nearly every row (the retail server did its own
     * pricing), so a flat affordable price is used instead; the account is
     * granted GRANT_SPIRIT at login.
     */
    private static void appendStoreItems(JSONArray out, int price, String... tables) {
        for (int ti = 0; ti < tables.length; ti++) {
            JSONObject tab = table(tables[ti]);
            if (tab == null) {
                continue;
            }
            java.util.List<String> keys = new java.util.ArrayList<String>();
            Iterator<String> it = tab.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            java.util.Collections.sort(keys);
            for (int i = 0; i < keys.size(); i++) {
                JSONObject rec = tab.optJSONObject(keys.get(i));
                if (rec == null) {
                    continue;
                }
                int id = rec.optInt("id", 0);
                if (id <= 0) {
                    continue;
                }
                int p = rec.optInt("spirit_cost", 0);
                if (p <= 0) {
                    p = price;
                }
                try {
                    JSONObject e = new JSONObject();
                    e.put("id", id);
                    e.put("num", 1);
                    e.put("price", p);
                    e.put("orignprice", p);
                    e.put("currentprice", p);
                    e.put("category", 0);
                    e.put("kind", 0);
                    out.put(e);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 一只宠物的完整存档对象。
     *
     * 键名来自 `YJPetObject::serializedJsonValue`（0x6f40e4）：
     *   dataid level petexp basehp basemp basestr basedef
     *   hpimprove mpimprove strimprove defimprove
     *   comprehension spiritpower bonelevel lifetime tamestate iselite
     *   skill triones petequips ferity
     * 数值取 pet.sdb 对应行的 *_min（= 刚抓到的宝宝档），
     * iselite=0 就是「宝宝品质」（1 才是变异/精英）。
     */
    private static JSONObject petJson(int id) {
        JSONObject o = new JSONObject();
        JSONObject rec = null;
        try {
            JSONObject tab = table("pet");
            if (tab != null) {
                rec = tab.optJSONObject(String.valueOf(id));
            }
        } catch (Throwable ignored) {
        }
        // 「属性」取宝宝档的 *_min；「资质」(*_improve) 才是面板上的
        // 「生命资质 800/800」那一栏。龙仔(226) 的资质上限已经在 pet.sdb 里抬到
        // 2000/2000/2000/600（patch_sdb.py），这一只的资质就直接给到上限，
        // 属性仍旧按 *_min，不跟着抬。
        // 青龙邪君(253) 的资质上限也是 patch_sdb.py 单独抬到 2000/1600/1600/620 的，
        // 所以同样按 *_max 取资质（属性仍取 *_min）。
        boolean maxed = (id == PET_LONGZAI || id == PET_YAOSHENG || id == PET_XIEJUN);
        String asuf = maxed ? "_max" : "_min";
        int hp = rec != null ? rec.optInt("hp_min", 100) : 100;
        int mp = rec != null ? rec.optInt("mp_min", 100) : 100;
        int str = rec != null ? rec.optInt("str_min", 10) : 10;
        int def = rec != null ? rec.optInt("def_min", 2) : 2;
        try {
            o.put("dataid", id);
            o.put("level", 1);
            o.put("petexp", 0);
            o.put("basehp", hp);
            o.put("basemp", mp);
            o.put("basestr", str);
            o.put("basedef", def);
            o.put("hpimprove", rec != null ? rec.optInt("hp_improve" + asuf, 0) : 0);
            o.put("mpimprove", rec != null ? rec.optInt("mp_improve" + asuf, 0) : 0);
            o.put("strimprove", rec != null ? rec.optInt("str_improve" + asuf, 0) : 0);
            o.put("defimprove", rec != null ? rec.optInt("def_improve" + asuf, 0) : 0);
            o.put("comprehension", rec != null ? rec.optInt("comprehension_max", 1) : 1);
            o.put("spiritpower", rec != null ? rec.optInt("spirit_power_max", 1) : 1);
            o.put("bonelevel", rec != null ? rec.optInt("bone_level_max", 1) : 1);
            o.put("lifetime", 0);
            o.put("tamestate", 1);
            o.put("iselite", 0);
            o.put("skill", petSkillsFor(id));
            o.put("triones", 0);
            o.put("petequips", new JSONArray());
            o.put("ferity", 0);
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * 宠物技能：全部「通用」技能 + 自己的专属技能。
     *
     * <p>pet_skill.sdb 的 56 条分两段：
     * <ul>
     *   <li>290~295、200500~200505 —— 通用（生命加成/嗜血狂暴/天生蛮力…），12 条</li>
     *   <li>210000~244040 —— 每只宠物 5 条专属，id/1000 就是宠物 id
     *       （226000~226040 是龙仔，238000~238040 是哮天犬…）</li>
     * </ul>
     * 所以 id >= 210000 的只发给对应的那只，其余全发。每只 12+5 = 17 个技能。
     */
    /**
     * 技能是按**链首**分组的，不是按当前形态：
     * <ul>
     *   <li>226xxx —— 整条青龙链（226 龙仔 / 227 绿英龙 / 228 将军 / 229 妖圣）</li>
     *   <li>238xxx —— 整条哮天犬链（238 哮天犬 / 239 吞天犬 / 240~243）</li>
     * </ul>
     * 之前直接拿 petId 去比 `sid/1000`，229/239 一条都匹配不到 →
     * 只剩 12 条通用被动、没有普通攻击 → 宠物站着发呆。这里映射回链首。
     */
    private static int skillOwnerFor(int petId) {
        // pet.sdb 里每只宠物都有 base_id = 它那条链的**链首**
        // （226 青龙 / 238 哮天 / 6700 玲珑 / 6715 青鸾 …），比写死区间可靠。
        try {
            JSONObject tab = table("pet");
            if (tab != null) {
                JSONObject rec = tab.optJSONObject(String.valueOf(petId));
                if (rec != null) {
                    int base = rec.optInt("base_id", 0);
                    if (base > 0) {
                        return base;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (petId >= 226 && petId <= 229) {
            return 226;
        }
        if (petId >= 238 && petId <= 243) {
            return 238;
        }
        return petId;
    }

    private static JSONArray petSkillsFor(int petId) {
        JSONArray out = new JSONArray();
        try {
            JSONObject tab = table("pet_skill");
            if (tab == null) {
                return out;
            }
            java.util.TreeSet<Integer> ids = new java.util.TreeSet<Integer>();
            Iterator<String> it = tab.keys();
            while (it.hasNext()) {
                JSONObject rec = tab.optJSONObject(it.next());
                if (rec == null) {
                    continue;
                }
                int sid = rec.optInt("id", 0);
                if (sid <= 0) {
                    continue;
                }
                int owner = sid / 1000;
                // 通用技能只有 290~295、200500~200509，都 < 210000。
                // 210000 以上（六位的 226xxx、七位的 6700000）全是**某只宠物的专属**。
                // 以前写成 `sid < 250000`，七位的 6700000 系列漏在外面 → 被当通用发给所有宠物，
                // 结果 253 青龙邪君拿到了玲珑圣君的技能。
                boolean perPet = (sid >= 210000);
                if (perPet && owner != skillOwnerFor(petId)) {
                    continue;
                }
                ids.add(Integer.valueOf(sid));
            }
            for (Integer sid : ids) {
                out.put(sid.intValue());
            }
        } catch (Throwable t) {
            trace("petSkills failed " + t);
        }
        return out;
    }

    private static synchronized JSONArray shopItems() {
        if (sShopItem == null) {
            JSONArray a = new JSONArray();
            // Everything equippable/usable that the client actually loads
            // (assets/database/sdb.list registers item, equip, magic, karma,
            // stone, aura_ball, pet_equip, pet_pill - see v411/sdb_dump.py):
            //   item   133  消耗品/材料
            //   equip  194  装备
            //   magic   13  法宝
            //   karma   48  因果
            appendStoreItems(a, 100, "item");
            sShopItem = a;
        }
        return sShopItem;
    }

    private static synchronized JSONArray shopStones() {
        if (sShopStone == null) {
            JSONArray a = new JSONArray();
            // 启灵珠（aura_ball.sdb，type 24）按客户端自己的存档形状 {A2,A4} 卖。
            // 注意不能用 itemJson 的 A5/A6/A9/BG 形状：type=24 的解析器会把
            // A9 当非法字段，买完/读档直接 GLThread SIGSEGV。
            appendStoreItems(a, 800, "aura_ball");
            sShopStone = a;
        }
        return sShopStone;
    }

    private static synchronized JSONArray shopPetItems() {
        if (sShopPetItem == null) {
            JSONArray a = new JSONArray();
            appendStoreItems(a, 100, "pet_pill");
            // NOTE: pets themselves must NOT be listed here. YJStoreItem::create(id)
            // resolves the id through the item data tables, and a pet row (type 17)
            // does not resolve there -> the list cell dereferences a null item at
            // +0xc. Verified: listing pet ids crashed the client inside CCArray
            // with "fault addr 0xc". Pets are handed out through the save instead.
            sShopPetItem = a;
        }
        return sShopPetItem;
    }

    /** 神秘店铺 gets the "rare" half: every equipment (incl. 法宝/因果) plus every stone. */
    /**
     * Mystery-shop entries. Identical to appendStoreItems except that `price` is an
     * item OBJECT instead of a number:
     *
     * <pre>
     * createStoreItemFromJson (0x57c91c):
     *     if price is an object:
     *         pi = createItemByJson(price)
     *         storeItem->setPriceItem(pi)          // vtable+0xd0, writes +0xd4
     *         currentPrice = orignPrice = pi->count // A4
     *     else:  currentPrice/orignPrice = the numbers, priceItem stays NULL
     *
     * YJMysteriousStoreBoardLayer::onTouchByCellElement (0x7feae8):
     *     if (storeItem->getPriceItem() == 0) -> "商品信息错误!"   // nothing is sent
     * </pre>
     *
     * The 商城 layer never looks at priceItem, which is why the numeric form kept
     * working there and only the mystery shop failed.
     */
    private static void appendMystStoreItems(JSONArray out, int price, String... tables) {
        appendMystStoreItems(out, price, false, tables);
    }

    /**
     * @param fixed true = 忽略表里的 spirit_cost，全部按 price 卖。
     *              神秘商店「所有道具/装备 1000 灵魂」用这个。
     */
    private static void appendMystStoreItems(JSONArray out, int price, boolean fixed, String... tables) {
        for (int ti = 0; ti < tables.length; ti++) {
            JSONObject tab = table(tables[ti]);
            if (tab == null) {
                continue;
            }
            java.util.List<String> keys = new java.util.ArrayList<String>();
            Iterator<String> it = tab.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            java.util.Collections.sort(keys);
            for (int i = 0; i < keys.size(); i++) {
                JSONObject rec = tab.optJSONObject(keys.get(i));
                if (rec == null) {
                    continue;
                }
                int id = rec.optInt("id", 0);
                if (id <= 0 || isBrokenSword(id)) {
                    continue;
                }
                int p = fixed ? price : rec.optInt("spirit_cost", 0);
                if (p <= 0) {
                    p = price;
                }
                try {
                    JSONObject e = new JSONObject();
                    e.put("id", id);
                    e.put("num", 1);
                    e.put("price", itemJson(SPIRIT_ITEM_ID, p));
                    e.put("goods_unique_mark", String.valueOf(id));
                    e.put("orignprice", p);
                    e.put("currentprice", p);
                    e.put("category", 0);
                    e.put("kind", 0);
                    out.put(e);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static synchronized JSONArray mystGoods() {
        if (sMystGoods == null) {
            JSONArray a = new JSONArray();
            // 用户要求：神秘商店上架所有道具和装备，统一 1000 灵魂。
            // 顺序：宠物丹药/制作书在前，然后 item / equip / noblest_equip /
            // magic / stone / pet_pill / pet_equip。价格固定，忽略表里的
            // spirit_cost（否则有些装备会卖几万）。
            int P = MYST_PRICE;
            appendMystIds(a, P, 503036, 504004, 504001, 504000);
            appendMystStoreItems(a, P, true,
                    "item", "equip", "noblest_equip", "stone",
                    "pet_pill", "pet_equip", "forge");
            // 去重（item 和 stone 可能有同 id），保留第一次出现的。
            JSONArray out = new JSONArray();
            java.util.HashSet<Integer> seen = new java.util.HashSet<Integer>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                int id = e.optInt("id", 0);
                if (id <= 0 || !seen.add(Integer.valueOf(id))) {
                    continue;
                }
                out.put(e);
            }
            sMystGoods = out;
            trace("mystGoods all: " + out.length() + " @ " + P + " soul");
        }
        return sMystGoods;
    }

    /** 指定 id 直接进神秘商店（item 表里的东西，比如宠物丹药）。 */
    private static void appendMystIds(JSONArray out, int price, int... ids) {
        JSONObject tab = table("item");
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            if (tab == null || tab.optJSONObject(String.valueOf(id)) == null) {
                trace("myst item " + id + " not in item table");
                continue;
            }
            int p = price;
            try {
                JSONObject e = new JSONObject();
                e.put("id", id);
                // 必须有 num —— 神秘商店的格子（YJMysteriousStoreBoardLayer）会读它，
                // 少了会解空指针直接闪退（这一条就是上一版闪退的原因）。
                e.put("num", 1);
                e.put("price", itemJson(SPIRIT_ITEM_ID, p));
                e.put("goods_unique_mark", String.valueOf(id));
                e.put("orignprice", p);
                e.put("currentprice", p);
                e.put("category", 0);
                e.put("kind", 0);
                out.put(e);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 邮箱内容。协议契约（`fn_strings.py` 从 native 里读出来的）：
     *
     * <pre>
     * YJGiftManager::requestMailList       -> action "mail"（带 rid）
     * YJGiftManager::_handleMailListGotten -> 读 "mails"（数组）
     * YJMailObj::isValidData               -> 必须有 "id"
     * YJMailObj::deserializeFromJsonValue  -> 读 "id" / "message" / "is_user" / "attachment"
     * YJGiftManager::requestReciveMailAttachments -> 请求带 id/type/is_user，
     *                                        应答读 "reward"（数组，逐条 createItemByJson）
     * </pre>
     */
    private static JSONArray mailList() {
        JSONArray a = new JSONArray();
        try {
            boolean claimed = mailClaimed();
            JSONObject m = new JSONObject();
            // 字段照 16.9 官服邮件 fixture 对齐：id/type 是字符串，mtype=2，
            // 还要 state=0 和未来的 expired —— 少一个客户端就当没有这封邮件。
            long now = System.currentTimeMillis() / 1000L;
            m.put("id", "1");
            // ---- 8.0.0 的键名（从 native 的字符串引用扫出来的，见 README）
            // YJMailObj 读的是 id / title / desc / from / dateline / mtype /
            // expired / is_user / type，附件在 "reward"（**单数**）。
            // "message" 和 "attachment" 在整个二进制里没有任何代码引用，
            // 发它们等于没发 —— 客户端会保留一个默认对象，界面就是
            // 「1970年1月1日 / 附件：无 / 来自：」那一行空邮件。
            m.put("title", "【系统】升品升星材料 · 金禄石 · 至尊碎片 · 90000 攻击凌火剑");
            m.put("desc", NOTICE + "\n\n本邮件附件："
                    + "升品/升星材料（元灵精魄 + 绿/蓝/紫/青/红/橙玄晶）各 " + MAIL_MATERIAL_COUNT + " 个"
                    + " + 金禄石 " + MAIL_JINLUSHI_COUNT + " 个"
                    + " + 至尊武器/衣服/饰品碎片 各 " + MAIL_FRAGMENT_COUNT + " 个"
                    + " + 至尊饰品 2 件（千尊佛缘镜 / 万古圣临盏，星级 " + MAIL_STAR + "）"
                    + " + 凌火剑 1 把（攻击 90000）。\n"
                    + "点「领取」即可进背包（列表最多显示前 4 个图标，实际全部发放）。"
                    + (claimed ? "\n\n（本邮件已领取）" : ""));
            m.put("from", "系统");
            m.put("dateline", now);
            m.put("expired", now + 7 * 86400L);
            m.put("state", 0);
            m.put("mtype", 2);
            m.put("type", "17");
            m.put("is_user", 1);
            m.put("reward", claimed ? new JSONArray() : mailAttachments());
            a.put(m);
            // 第二封：纯文本系统邮件（不带附件），让「系统邮件」页也有内容。
            JSONObject m2 = new JSONObject();
            m2.put("id", "2");
            m2.put("title", "【系统】离线单机版说明");
            m2.put("desc", NOTICE + "\n\n本服为本地离线服务，邮件/真灵/神秘商店均由本地应答。");
            m2.put("from", "系统");
            m2.put("dateline", now);
            m2.put("expired", now + 30 * 86400L);
            m2.put("state", 0);
            m2.put("mtype", 1);
            m2.put("type", "17");
            m2.put("is_user", 1);
            m2.put("reward", new JSONArray());
            a.put(m2);

            // 第四封：真灵补发，保证邮件里一定能看到。
            {
                JSONObject m4 = new JSONObject();
                m4.put("id", "4");
                m4.put("title", "【真灵】冰霜末世（补发）");
                m4.put("desc", "真灵「冰霜末世」补发邮件。\n"
                        + "基础：生命 20000 / 攻击 6000 / 魔法 10000 / 防御 1000\n"
                        + "资质上限：生命 4000 / 攻击 3500 / 防御 1600 / 魔法 3000");
                m4.put("from", "系统");
                m4.put("dateline", now);
                m4.put("expired", now + 30 * 86400L);
                m4.put("state", 0);
                m4.put("mtype", 2);
                m4.put("type", "17");
                m4.put("is_user", 1);
                JSONArray tsReward2 = new JSONArray();
                tsReward2.put(itemJson(503344, 1));
                m4.put("reward", tsReward2);
                a.put(m4);
            }
            // 第三封：冰霜末世（实例 petid=4399，dataid=9005）。
            if (!tsMailClaimed()) {
                JSONObject m3 = new JSONObject();
                m3.put("id", "3");
                m3.put("title", "【真灵】冰霜末世");
                m3.put("desc", "系统通过邮件发放真灵「冰霜末世」。\n"
                        + "基础：生命 20000 / 攻击 6000 / 魔法 10000 / 防御 1000\n"
                        + "资质上限：生命 4000 / 攻击 3500 / 防御 1600 / 魔法 3000");
                m3.put("from", "系统");
                m3.put("dateline", now);
                m3.put("expired", now + 30 * 86400L);
                m3.put("state", 0);
                m3.put("mtype", 2);
                m3.put("type", "17");
                m3.put("is_user", 1);
                JSONArray tsReward = new JSONArray();
                tsReward.put(itemJson(503344, 1));
                m3.put("reward", tsReward);
                a.put(m3);
            }

            // 真灵邮件暂停：11.4 客户端把 9005 当宠物下发会卡死，
            // 等原生真灵 UI/入口打通后再加回来。
        } catch (Exception ignored) {
        }
        return a;
    }

    /**
     * 邮件附件：4 种物品各 999 个。
     *
     * <p>只保留 `item` 表里的 id，全部对着 8.0.0 的 assets/database 核过：
     * <pre>
     * 503012 item 龙女的眼泪    503000 item 神恩符
     * 503001 item 幸运符        503079 item 精良仙尘
     * </pre>
     * 以前还放了 502129 / 502137 两颗石头 —— 实测界面上只出 4 个图标，
     * 而 `stone` 表的 id 过 createItemByJson 建不出物品，就不再放了。
     */
    /** 至尊碎片（`item.sdb`，可堆叠，type 11）。 */
    private static final int[] MAIL_FRAGMENT_IDS = {503208, 503209, 503210};
    private static final int MAIL_FRAGMENT_COUNT = 9999;

    /** 金禄石（item.sdb 503176，type 11 可堆叠）。 */
    private static final int ITEM_JINLUSHI = 503176;
    private static final int MAIL_JINLUSHI_COUNT = 9000;

    /**
     * 至尊装备升品/升星的材料（`item.sdb`，type 11）：
     * <pre>
     * 503199 元灵精魄   503200 绿玄晶   503201 蓝玄晶
     * 503202 紫玄晶     503203 青玄晶   503204 红玄晶   503205 橙玄晶
     * </pre>
     * 客户端 handleaction 请求里的 material 就是这些 id（升级面板那个 0/0 就是没有材料）。
     */
    private static final int[] MAIL_MATERIAL_IDS = {
            503199, 503200, 503201, 503202, 503203, 503204, 503205,
    };
    private static final int MAIL_MATERIAL_COUNT = 9999;

    /** 至尊饰品两件（`noblest_equip.sdb` 里的 type_info="饰品"）。 */
    private static final int[] MAIL_NOBLEST_IDS = {500223, 500224};

    /**
     * 至尊装备（`assets/database/noblest_equip.sdb`，grade 8，"至尊品质，绝世无双"）。
     * 现在只发两件饰品（用户要求）：
     * <pre>
     * 500223 千尊佛缘镜 饰品      500224 万古圣临盏 饰品
     * </pre>
     * 其余 12 件武器/防具的 id 留着备查：
     * 1316 斗战天罡刀 1513 斗战天罡甲 2316 旃檀金禅杖 2513 旃檀金禅袍
     * 3316 净坛护魔斧 3514 净坛护魔铠 4323 罗汉幻影弓 4513 罗汉幻影甲
     * 5312 血狱阴魂镰 5511 血狱阴魂甲 6316 赤霄龙鳞剑 6513 赤霄龙鳞铠
     */

    /** 星级：装备 JSON 里 `A9` 是星级（`AV` 是阶数，4.1.1 验证过）。 */
    private static final int MAIL_STAR = 5;

    /**
     * 装备条目。
     *
     * <p>⚠️ 装备**不能**复用 `itemJson()` 那个精简形状：升星/升品时客户端要读宝石槽
     * （`BG` 里的 `CB`）、洗练（`C4`）、`BP/BQ/BR/BS`、`CO/CP` 这些字段去算消耗，
     * 缺了就弹「数据已损坏」。这里按存档里客户端自己序列化出来的装备补齐。
     *
     * <p>`AV`（阶数）保持 0：至尊装备在 `noblest_equip.sdb` 里没有阶数字段，
     * 硬给 5 阶同样会判非法。
     */
    private static JSONObject equipJson(int id, int star) {
        JSONObject it = new JSONObject();
        try {
            it.put("A2", id);
            it.put("A4", 1);
            it.put("A5", id);
            it.put("A6", 0);
            // 属性照表填：生命/魔法/攻击/防御 = noblest_equip(或 equip) 的
            // hp/mp/str/def_min；星级写 CC（以前错把星级写进 A9=攻击）。
            JSONObject row = table("noblest_equip") == null ? null
                    : table("noblest_equip").optJSONObject(String.valueOf(id));
            if (row == null) {
                row = table("equip") == null ? null
                        : table("equip").optJSONObject(String.valueOf(id));
            }
            int hp = row != null ? row.optInt("hp_min", 0) : 0;
            int mp = row != null ? row.optInt("mp_min", 0) : 0;
            int str = row != null ? row.optInt("str_min", 0) : 0;
            int def = row != null ? row.optInt("def_min", 0) : 0;
            if (hp > 0) {
                it.put("A7", hp);
            }
            if (mp > 0) {
                it.put("A8", mp);
            }
            if (str > 0) {
                it.put("A9", str);
            }
            if (def > 0) {
                it.put("AA", def);
            }
            it.put("CC", star);
            if (row != null) {
                double crit = row.optDouble("critical", 0);
                if (crit > 0) {
                    it.put("AF", (int) Math.round(crit * 100));
                }
                double magdef = row.optDouble("magDef", 0);
                if (magdef > 0) {
                    it.put("AC", (int) Math.round(magdef * 100));
                }
            }
            // 传说装备直接带仙器标记，客户端才会开仙器培养面板。
            if (isLegendEquip(id)) {
                it.put("fairy_grade", 0);
                it.put("fairy_value", 0);
            }
            it.put("AQ", 0);
            it.put("AT", 0);
            it.put("AV", 0);
            it.put("AW", 0.0);
            it.put("B7", 0);
            JSONArray bg = new JSONArray();
            for (int i = 0; i < 4; i++) {
                JSONObject g = new JSONObject();
                g.put("BH", -1);
                g.put("BI", 0);
                g.put("BJ", 0);
                g.put("CB", 0);
                bg.put(g);
            }
            it.put("BG", bg);
            it.put("BP", 0);
            it.put("BQ", 0);
            it.put("BR", 0);
            it.put("BS", 0);
            JSONArray c4 = new JSONArray();
            for (int i = 0; i < 3; i++) {
                JSONObject c = new JSONObject();
                c.put("C5", -1);
                c4.put(c);
            }
            it.put("C4", c4);
            it.put("CA", 0);
            it.put("CC", 0);
            it.put("CD", 0);
            it.put("CE", 0);
            it.put("CF", 0);
            it.put("CG", 0);
            it.put("CL", 0);        // 聚炼到期时间戳（0 = 从未聚炼）
            it.put("CM", 1);        // 1 = 聚炼未生效
            it.put("CN", 0);
            it.put("CO", 1);
            it.put("CP", -9999999);
            it.put("CU", 0);
            // CV 是「传承等级上限」，官服恒 100（缺了它客户端可能当成 0 → 锻升直接判上限）
            it.put("CV", 100);
            it.put("CW", 0);
        } catch (Exception ignored) {
        }
        return it;
    }

    private static JSONArray mailAttachments() {
        JSONArray att = new JSONArray();
        // 至尊碎片各 9999（可堆叠，走物品形状）
        for (int i = 0; i < MAIL_FRAGMENT_IDS.length; i++) {
            att.put(itemJson(MAIL_FRAGMENT_IDS[i], MAIL_FRAGMENT_COUNT));
        }
        // 至尊饰品两件（装备形状，A9=星级）
        for (int i = 0; i < MAIL_NOBLEST_IDS.length; i++) {
            att.put(equipJson(MAIL_NOBLEST_IDS[i], MAIL_STAR));
        }
        // 凌火剑(6301)：equip 表已被 patch_sdb.py 改成攻击 90000。
        // A9 给 0（未强化），攻击就是表里的 90000。
        att.put(equipJson(6301, 0));
        // 金禄石 9000 个（可堆叠）
        att.put(itemJson(ITEM_JINLUSHI, MAIL_JINLUSHI_COUNT));
        // 升品/升星材料：元灵精魄 + 六色玄晶，各 9999
        for (int i = 0; i < MAIL_MATERIAL_IDS.length; i++) {
            att.put(itemJson(MAIL_MATERIAL_IDS[i], MAIL_MATERIAL_COUNT));
        }
        // 四把仙剑不再走邮件：改由「第二次登录」直接进背包
        // （见 grantSwordsOnce / loginCount），避免两处重复发放。
        return att;
    }

    /**
     * 领取邮件附件。
     *
     * <p>契约（native，v8）：
     * <ul>
     *   <li>邮件对象本身把附件放在 <b>"reward"</b>（单数）里，逐条 createItemByJson；</li>
     *   <li>领取应答的处理函数是 <code>FUN_03b63194</code> @0x03b63194，
     *       它引用的是 <b>"rewards"</b>（复数）。</li>
     * </ul>
     * 两个都发：读哪个都能拿到东西（单机包里多发一份也无所谓）。
     *
     * <p>只发一次（`mail.claimed` 落盘），顺便把青龙妖圣写进存档 ——
     * 宠物不是物品，没法走奖励数组。
     */
    private static synchronized JSONArray claimMail() {
        JSONArray reward = new JSONArray();
        try {
            if (mailClaimed()) {
                trace("MAIL claim ignored: already claimed");
                return reward;
            }
            JSONArray att = mailAttachments();
            for (int i = 0; i < att.length(); i++) {
                reward.put(att.get(i));
            }
            setMailClaimed();
            grantPetToSave(PET_YAOSHENG);
            trace("MAIL claim granted " + reward.length() + " stacks + pet " + PET_YAOSHENG);
        } catch (Throwable t) {
            trace("MAIL claim failed " + t);
        }
        return reward;
    }

    /**
     * 领取应答：`reward` 和 `rewards` 都写一遍。
     *
     * 邮件对象自己的附件键是 `reward`（单数），而领取应答的处理函数
     * `FUN_03b63194` @0x03b63194 引用的是 `rewards`（复数）。两边都发，
     * 客户端读哪个都能拿到 —— 单机包里多给一份也无所谓。
     */
    /**
     * 邮件 id=3「冰霜末世」的领取：把 9005 作为宠物写进 pet.petinfo，
     * 不再使用 true_spirit_bag。附件仍给一个聚元玄晶（503344）。
     */
    private static void grantTrueSpiritMail(JSONObject r) {
        try {
            JSONObject store = readSaveObj();
            if (store == null) {
                store = new JSONObject();
                store.put("point", 0);
            }
            JSONObject bag = trueSpiritBag();
            store.put("true_spirit_bag", bag);
            writeSave(store.toString());
            setTsMailClaimed();
            JSONArray items = new JSONArray();
            items.put(itemJson(503344, 1));
            r.put("reward", items);
            r.put("rewards", items);
            r.put("true_spirit_bag", bag);
            r.put("truespiritinfo", bag.optJSONArray("truespiritinfo"));
            r.put("state", 0);
            r.put("msg", "");
            trace("MAIL true spirit 9005 granted (petid=4399)");
        } catch (Throwable t) {
            trace("MAIL true spirit grant failed " + t);
        }
    }

    private static void putMailReward(JSONObject r) throws Exception {
        JSONArray items = claimMail();
        r.put("reward", items);
        r.put("rewards", items);
    }

    private static File mailClaimedFlag() {
        return new File(dir(), "mail.claimed");
    }
    private static boolean mailClaimed() {
        return mailClaimedFlag().exists();
    }

    private static void setMailClaimed() {
        try {
            FileOutputStream fo = new FileOutputStream(mailClaimedFlag());
            fo.write(1);
            fo.close();
        } catch (Throwable t) {
            trace("setMailClaimed failed " + t);
        }
    }

    private static File tsMailFlag() {
        return new File(dir(), "ts_mail2.claimed");
    }

    private static boolean tsMailClaimed() {
        return tsMailFlag().exists();
    }

    private static void setTsMailClaimed() {
        try {
            FileOutputStream fo = new FileOutputStream(tsMailFlag());
            fo.write(1);
            fo.close();
        } catch (Throwable t) {
            trace("setTsMailClaimed failed " + t);
        }
    }

    /** 青龙妖圣只送一次：`files/offline/pet.given` 落盘后不再补发。 */
    private static File petGivenFlag() {
        return new File(dir(), "pet.given");
    }

    private static boolean petGiftGiven() {
        return petGivenFlag().exists();
    }

    private static void setPetGiftGiven() {
        try {
            FileOutputStream fo = new FileOutputStream(petGivenFlag());
            fo.write(1);
            fo.close();
        } catch (Throwable t) {
            trace("setPetGiftGiven failed " + t);
        }
    }

    /**
     * 签到。协议契约（`fn_strings.py` 从 native 里读出来的）：
     *
     * <pre>
     * YJGiftManager::_fetchSignInListFromServer  -> action "rolesigninfo"（带 rid）
     * YJGiftManager::_handleSignInListRequestSuccess @0x53a8d8：
     *     必须同时有 rewardlist / signcount / month（缺一个就整段跳过）
     *     rewardlist 是「数组的数组」，每一项是当天的奖励物品；
     *     native 只取**第一项**做预览（内层逐条 createPreviewItemByJson）
     *     "rewards"（数组）：如果出现，会逐条 createItemByJson + addItemToBag
     *                        + showGameTip —— 也就是「领到了」的提示
     * YJGiftManager::_acquireSignInFromServer    -> 带 rid + type
     * YJGiftManager::_handleAcquireSignInRequestSuccess 是**空函数**，
     *     所以领奖后客户端会自己再拉一次 rolesigninfo，
     *     想要「领到东西」的提示就得在那一次的应答里给 "rewards"。
     * </pre>
     *
     * 宠物不走这里（奖励只认物品），而是「签到过一次之后」随 savedata 下发，
     * 见 buildSavedataBlock() 里的 petsUnlocked() 判断。
     */
    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static int thisMonth() {
        return Integer.parseInt(new java.text.SimpleDateFormat("MM", java.util.Locale.US)
                .format(new java.util.Date()));
    }

    private static synchronized void markPetsClaimed() {
        try {
            JSONObject st = readSignIn();
            st.put("pets", 1);
            st.put("taskclaimed", 1);
            writeSignIn(st);
        } catch (Throwable t) {
            logLine("markPetsClaimed failed " + t);
        }
    }

    /**
     * 日常任务：客户端没有本地任务表（assets/database 里没有 task/mission 表），
     * 任务完全由服务端下发。这里给一个「登录一次」的任务，进度直接给满 = 可领取。
     * 标题走 `encodetitle`（base64 的 UTF-8）。
     */
    /**
     * v8 的任务面板（悬赏/限时）无法用 4.1.1 那套自造任务喂：
     * 只要 dailytask/timelimit/boss 里有**任何**一条（哪怕字段齐全、
     * tid 就是 mission.sdb 里真实存在的 6000045），客户端解析完就会
     * SIGSEGV（native，无 tombstone）。空数组则正常打开面板。
     * 所以这一版先给空列表；要做任务得先把 v8 的任务面板协议拆出来。
     */
    /**
     * v8 任务板协议（逆向自 v8 内存镜像 + Ghidra）：
     *
     *   YJMissionManager::_handleMissionListGotten 读 5 个键：
     *     dailytask / timelimit / boss / storytask / holiday_task
     *   dailytask -> FUN_03b9e808 读 "time" + "group_list"
     *   group_list 里每个元素 (YJDailyMissionGroup::deserializeFromJsonValue)：
     *     group_id / group_name / state / task_list
     *   task_list 里每个元素 (YJMissionItem::deserializeFromJsonValue @0x4010638)：
     *     tid / state / targetprogress / target / endtime /
     *     encodetitle(base64) / isserverstarttime / isserverendtime
     *
     * 之前直接往 dailytask 塞扁平任务条目，客户端当成 group 解析 -> 空指针闪退。
     */
    private static JSONObject missionItem(int tid, String title, int progress) {
        JSONObject it = new JSONObject();
        try {
            it.put("tid", tid);
            it.put("state", 0);
            it.put("targetprogress", progress);
            it.put("target", 1);
            it.put("endtime", 0);
            it.put("encodetitle", android.util.Base64.encodeToString(
                    title.getBytes("UTF-8"), android.util.Base64.NO_WRAP));
            it.put("isserverstarttime", 1);
            it.put("isserverendtime", 0);
        } catch (Throwable t) {
            trace("missionItem failed " + t);
        }
        return it;
    }

    /** One mission board block: {"time":..,"group_list":[{"group_id":..}...]}. */
    private static JSONObject taskBlock(String groupName, JSONArray items) {
        JSONObject out = new JSONObject();
        try {
            JSONObject g = new JSONObject();
            g.put("group_id", 1);
            g.put("group_name", groupName);
            g.put("state", 0);
            g.put("task_list", items);
            JSONArray groups = new JSONArray();
            groups.put(g);
            out.put("time", System.currentTimeMillis() / 1000L);
            out.put("group_list", groups);
        } catch (Throwable t) {
            trace("taskBlock failed " + t);
        }
        return out;
    }

    /** 主线任务：mission.sdb 里的 6000000「独闯天宫道」（打千里眼 304 + 顺风耳 305）。 */
    private static JSONObject mainTaskBlock() {
        String title = "独闯天宫道";
        int tid = 6000000;
        JSONObject tab = table("mission");
        if (tab != null) {
            JSONObject row = tab.optJSONObject(String.valueOf(tid));
            if (row != null) {
                title = row.optString("name", title);
            }
        }
        JSONArray items = new JSONArray();
        items.put(missionItem(tid, title, 0));
        return taskBlock("主线任务", items);
    }

    /**
     * v8 的主线任务走 `storytask`（扁平 YJMissionItem 数组），
     * 任务面板右下的「任务内容 / 任务进度 / 任务奖励」就是它渲染的；
     * 塞进 dailytask 只会显示成任务板列表里的一条空壳。
     */
    private static JSONArray mainTaskList() {
        JSONArray out = new JSONArray();
        try {
            String title = "独闯天宫道";
            int tid = 6000000;
            JSONObject tab = table("mission");
            if (tab != null) {
                JSONObject row = tab.optJSONObject(String.valueOf(tid));
                if (row != null) {
                    title = row.optString("name", title);
                }
            }
            out.put(missionItem(tid, title, 0));
        } catch (Throwable t) {
            trace("mainTaskList failed " + t);
        }
        return out;
    }

    private static JSONArray dailyMissions() {
        JSONArray a = new JSONArray();
        if (true) {
            return a;
        }
        try {
            JSONObject m = new JSONObject();
            m.put("tid", 1);
            m.put("target", 1);
            m.put("targetprogress", 1);
            m.put("endtime", System.currentTimeMillis() / 1000L + 86400L);
            m.put("isserverstarttime", 1);
            m.put("isserverendtime", 0);
            m.put("encodetitle", android.util.Base64.encodeToString(
                    "登录一次游戏（领取全部宠物）".getBytes("UTF-8"),
                    android.util.Base64.NO_WRAP));
            a.put(m);
        } catch (Throwable t) {
            trace("dailyMissions failed " + t);
        }
        return a;
    }

    private static synchronized JSONObject readSignIn() {
        try {
            File f = new File(dir(), "signin.json");
            if (!f.exists()) {
                return new JSONObject();
            }
            return new JSONObject(new String(readAll(f), "UTF-8"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static synchronized void writeSignIn(JSONObject o) {
        try {
            FileOutputStream out = new FileOutputStream(new File(dir(), "signin.json"));
            out.write(o.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            logLine("SIGNIN WRITE FAILED " + t);
        }
    }

    /**
     * 宠物礼包：改成**任务领取**才补发（日常任务「登录一次游戏」）。
     * 领取动作由 task* 分支调 markPetsClaimed() 写 taskclaimed=1。
     *
     * ⚠️ 没领取时**不会删除存档里已有的宠物**（见 buildSavedataBlock），
     * 只是不再补新的 —— 否则一旦任务面板点不动，玩家手上的宠物会被清空。
     */
    private static boolean petsUnlocked() {
        // 现在宠物直接随存档下发（青龙妖圣已写进 GIFT_PETS），不再要求先签到，
        // 否则第一次进游戏时 petinfo 会被这条门禁吞掉。
        return true;
    }

    private static void fillSignIn(JSONObject r) throws Exception {
        // 打开「签到」就算签到：面板本身没有可点的领取按钮（实测点奖励格子不发包），
        // 所以把「签到一次」放在拉列这里做，效果就是：打开面板 -> 本月已累计签到 +1
        // -> 宠物礼包解锁。
        claimSignIn();
        JSONObject st = readSignIn();
        r.put("month", thisMonth());
        r.put("signcount", st.optInt("count", 0));
        JSONArray days = new JSONArray();
        JSONArray todayItems = new JSONArray();
        todayItems.put(itemJson(SPIRIT_ITEM_ID, 100000));
        days.put(todayItems);
        r.put("rewardlist", days);
        trace("SIGNIN list count=" + st.optInt("count", 0)
                + " date=" + st.optString("date", "-")
                + " pets=" + st.optInt("pets", 0));
    }

    /** 领取今日签到：记日期、天数，并解锁宠物礼包。 */
    private static synchronized void claimSignIn() {
        JSONObject st = readSignIn();
        String t = today();
        if (!t.equals(st.optString("date", ""))) {
            try {
                st.put("count", st.optInt("count", 0) + 1);
                st.put("date", t);
                st.put("pets", 1);
            } catch (Exception ignored) {
            }
            writeSignIn(st);
            trace("SIGNIN claimed count=" + st.optInt("count", 0) + " -> pets unlocked");
        } else {
            trace("SIGNIN already claimed today");
        }
    }

    private static void fillShop(String action, JSONObject r) throws Exception {
        r.put("point", GRANT_SPIRIT);
        if (action.startsWith("mystery") || action.startsWith("refreshmystery")) {
            r.put("goods", mystGoods());
            // YJStoreManager::_handleMystItemListRequestSuccess reads
            // nextrefreshtime (long) -> setNextRefreshMystTime(); without it the
            // window shows a garbage countdown ("-498231:-29893886:-23").
            r.put("nextrefreshtime", System.currentTimeMillis() / 1000L + 3600L);
            trace("SHOP " + action + " goods=" + mystGoods().length());
            return;
        }
        r.put("item", shopItems());
        r.put("stone", shopStones());
        r.put("petitem", shopPetItems());
        r.put("fashion", new JSONArray());
        // Not an object -> the client keeps a single flat list instead of tabs.
        r.put("kinds", new JSONObject());
        trace("SHOP " + action + " item=" + shopItems().length()
                + " stone=" + shopStones().length() + " petitem=" + shopPetItems().length());
    }

    /** Hand the worn-gear id list to libmod, which re-equips them on the GL thread. */
    private static void armEquips(JSONObject sd) {
        try {
            JSONArray eq = sd.optJSONArray("equips");
            if (eq == null || eq.length() == 0) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < eq.length(); i++) {
                JSONObject e = eq.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                int id = e.optInt("dataid", e.optInt("A5", 0));
                if (id <= 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
            if (sb.length() == 0) {
                return;
            }
            int n = Mod.setEquips(sb.toString());
            trace("armEquips " + sb + " -> " + n);
        } catch (Throwable t) {
            trace("armEquips failed " + t);
        }
    }

    /** Item object for createItemByJson: A5 is the item id, A2 the type lookup id. */
    private static JSONObject itemJson(int id) {
        return itemJson(id, 1);
    }

    /**
     * Same, with an explicit stack count (A4). Used for the mystery-shop price
     * item: createStoreItemFromJson derives currentPrice/orignPrice from that
     * item's count, so A4 carries the price there.
     */
    private static JSONObject itemJson(int id, int count) {
        JSONObject it = new JSONObject();
        try {
            it.put("A2", id);
            it.put("A4", count > 0 ? count : 1);
            it.put("A5", id);
            it.put("A6", 0);
            it.put("A9", 8);
            it.put("AQ", 0);
            it.put("AT", 0);
            it.put("AV", 0);
            it.put("AW", 0.0);
            it.put("B7", 0);
            JSONArray bg = new JSONArray();
            for (int i = 0; i < 4; i++) {
                JSONObject g = new JSONObject();
                g.put("BH", -1);
                g.put("BI", 0);
                g.put("BJ", 0);
                bg.put(g);
            }
            it.put("BG", bg);
        } catch (Exception ignored) {
        }
        return it;
    }

    /**
     * 启灵珠（aura_ball.sdb，type 24）的物品形状：客户端自己的存档里就是
     *   {"A2":502195,"A4":1}
     * 只有 A2/A4。用普通 itemJson 的 A5/A6/A9/BG 形状会让 type=24 的解析器
     * 读档时崩（A9=8 在启灵珠里是非法字段），表现就是「商店买启灵珠 → 存档炸 → 进不去」。
     */
    private static JSONObject stackableJson(int id, int count) {
        JSONObject it = new JSONObject();
        try {
            it.put("A2", id);
            it.put("A4", count > 0 ? count : 1);
        } catch (Throwable ignored) {
        }
        return it;
    }

    /** 是否启灵珠：优先查 aura_ball 表，表读不到时退回 id 区间。 */
    private static boolean isAuraBall(int id) {
        if (id >= 502140 && id <= 502195) {
            return true;
        }
        try {
            JSONObject tab = table("aura_ball");
            return tab != null && tab.has(String.valueOf(id));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 启灵珠「突破形态」→ 本体 id。
     *
     * 502181~502195 这些行的 resource_id 指回 502160~502192 本体，
     * 客户端把它们当 Lv1 不可升级的珠子（升级直接提示已达上限）。
     * 旧商店把它们也上架了，玩家买到后就是这个现象；这里统一换回本体。
     */
    private static int auraBaseId(int id) {
        if (id < 502181 || id > 502195) {
            return 0;
        }
        try {
            JSONObject tab = table("aura_ball");
            if (tab == null) {
                return 0;
            }
            JSONObject rec = tab.optJSONObject(String.valueOf(id));
            return rec == null ? 0 : rec.optInt("resource_id", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 启灵珠（aura_ball.sdb，type 24）只保留 {A2,A4}。
     *
     * 客户端自己上传的启灵珠就是这个形状（实测存档里有
     * {"A2":502195,"A4":2}），说明 type=24 是可以进背包的。
     * 之前崩是因为商店用 itemJson 发了 A5/A6/A9/BG，A9=8 被 type=24 解析器
     * 当成非法字段；这里把旧档里的多余键全部剥掉，保留数量。
     */
    private static void sanitizeAuraBalls(JSONObject sd) {
        if (sd == null) {
            return;
        }
        try {
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items == null) {
                return;
            }
            int fixed = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                int id = it.optInt("A2", 0);
                if (!isAuraBall(id)) {
                    continue;
                }
                int cnt = it.optInt("A4", 0);
                if (cnt <= 0) {
                    cnt = 1;
                }
                java.util.ArrayList<String> drop = new java.util.ArrayList<String>();
                java.util.Iterator<String> keys = it.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (!"A2".equals(k) && !"A4".equals(k)) {
                        drop.add(k);
                    }
                }
                for (int d = 0; d < drop.size(); d++) {
                    it.remove(drop.get(d));
                }
                it.put("A4", cnt);
                if (!drop.isEmpty()) {
                    fixed++;
                    trace("aura sanitize: " + id + " drop " + drop);
                }
            }
            if (fixed > 0) {
                trace("aura sanitize: fixed " + fixed + " items");
            }
        } catch (Throwable t) {
            trace("sanitizeAuraBalls failed " + t);
        }
    }

    /**
     * 不可堆叠道具的 A4 只能是 1。
     *
     * 旧商店把 item 表里所有东西都按「买一给 99」发，连 `stackable: 0` 的
     * 唯一道具（510072 神秘齿轮等）也写成 A4=99。客户端读档时按 99 个非堆叠
     * 物品处理 → GLThread 空指针闪退。这里在下发/落盘前把这类 A4 改成 1。
     */
    private static void sanitizeItemStacks(JSONObject sd) {
        if (sd == null) {
            return;
        }
        try {
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items == null) {
                return;
            }
            JSONObject tab = table("item");
            if (tab == null) {
                return;
            }
            int fixed = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                int id = it.optInt("A2", 0);
                JSONObject row = tab.optJSONObject(String.valueOf(id));
                if (row == null || row.optInt("stackable", 1) != 0) {
                    continue;
                }
                if (it.optInt("A4", 1) != 1) {
                    trace("stack sanitize: " + id + " A4 " + it.optInt("A4", 0) + " -> 1");
                    it.put("A4", 1);
                    fixed++;
                }
            }
            if (fixed > 0) {
                trace("stack sanitize: fixed " + fixed + " non-stackable items");
            }
        } catch (Throwable t) {
            trace("sanitizeItemStacks failed " + t);
        }
    }

    /**
     * 至尊装备飞升材料：飞升石 503207 / 天元精魄 503227 / 仙阳炎 503370。
     * 登录场景的 savedata 和 roledata2 都补一份并落盘，保证飞升面板的
     * 「消耗 x/y」左边不是 0（客户端本地校验会因此禁用飞升按钮）。
     */
    private static void ensureSoarMaterials(JSONObject sd) {
        if (sd == null) {
            return;
        }
        try {
            JSONObject bag = sd.optJSONObject("bag");
            if (bag == null) {
                bag = new JSONObject();
                sd.put("bag", bag);
            }
            JSONArray items = bag.optJSONArray("item");
            if (items == null) {
                items = new JSONArray();
                bag.put("item", items);
            }
            int[] ids = {503207, 503227, 503370};
            for (int i = 0; i < ids.length; i++) {
                int id = ids[i];
                JSONObject have = null;
                for (int k = 0; k < items.length(); k++) {
                    JSONObject it = items.optJSONObject(k);
                    if (it != null && it.optInt("A2", 0) == id) {
                        have = it;
                        break;
                    }
                }
                if (have == null) {
                    items.put(itemJson(id, 9999));
                } else if (have.optInt("A4", 0) < 9999) {
                    have.put("A4", 9999);
                }
            }
        } catch (Throwable t) {
            trace("ensureSoarMaterials failed " + t);
        }
    }

    /**
     * 神秘商店购买：服务端直接把物品写进存档（5.5 的 mod 就是这么做的），
     * 回包按它的契约给 goodsId/itemamount，客户端收到后本地入包。
     */
    private static void addPurchasedToSave(JSONObject bought, int id, int n, boolean isEquip) {
        try {
            JSONObject store = readSaveObj();
            JSONObject bag = store.optJSONObject("bag");
            if (bag == null) {
                bag = new JSONObject();
                store.put("bag", bag);
            }
            JSONArray items = bag.optJSONArray("item");
            if (items == null) {
                items = new JSONArray();
                bag.put("item", items);
            }
            boolean merged = false;
            if (!isEquip) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.optJSONObject(i);
                    if (o != null && o.optInt("A2", 0) == id) {
                        o.put("A4", o.optInt("A4", 0) + n);
                        merged = true;
                        break;
                    }
                }
            }
            if (!merged) {
                JSONObject it = new JSONObject(bought.toString());
                if (isEquip) {
                    int gid = Math.max(4400, bag.optInt("maxglobalitemid", 4400)) + 1;
                    it.put("A5", gid);
                    bag.put("maxglobalitemid", gid);
                }
                items.put(it);
            }
            writeSave(store.toString());
            trace("MYSTBUY save: " + id + " x" + n);
        } catch (Throwable t) {
            trace("addPurchasedToSave failed " + t);
        }
    }

    // --------------------------------------------------------- 背包扩充
    //
    // 11.4 的 buybagprice / buybag 与 5.5 离线版同构（v55/out55 LocalServer）：
    //   buybagprice -> {point: 当前点券, price: {A2:503037, A4:价格, B7:0}}
    //   buybag      -> 扣点券，回新的 point + 同一个 price
    // 价格表在 assets/database/bag_size_price.sdb 的 price_list[index]。
    // 之前 buybagprice 落进通用 fillBuy，回了 goods/goodsId（随机制作书），
    // 客户端读不到 price，所以弹「发生不可预知的错误」。
    private static final int BAG_PRICE_ITEM = 503037; // 点券

    private static int[] sBagPrices;

    /** price_list 缓存；表缺失时返回空。 */
    private static int[] bagPriceList() {
        if (sBagPrices != null) {
            return sBagPrices;
        }
        try {
            JSONObject tab = table("bag_size_price");
            JSONArray list = null;
            if (tab != null) {
                Iterator<String> it = tab.keys();
                while (it.hasNext()) {
                    JSONObject row = tab.optJSONObject(it.next());
                    if (row != null && row.optJSONArray("price_list") != null) {
                        list = row.optJSONArray("price_list");
                        break;
                    }
                }
            }
            if (list == null) {
                sBagPrices = new int[0];
            } else {
                sBagPrices = new int[list.length()];
                for (int i = 0; i < list.length(); i++) {
                    sBagPrices[i] = list.optInt(i, 0);
                }
            }
            trace("bag_size_price rows=" + sBagPrices.length);
        } catch (Throwable t) {
            trace("bag_size_price failed " + t);
            sBagPrices = new int[0];
        }
        return sBagPrices;
    }

    private static int bagPriceAt(int index) {
        int[] list = bagPriceList();
        if (list.length == 0) {
            return 0;
        }
        int i = Math.max(0, Math.min(list.length - 1, index));
        return list[i];
    }

    private static JSONObject bagPriceItem(int price) {
        JSONObject p = new JSONObject();
        try {
            p.put("A2", BAG_PRICE_ITEM);
            p.put("A4", price);
            p.put("B7", 0);
        } catch (Throwable ignored) {
        }
        return p;
    }

    private static int bagPointOf(JSONObject store) {
        if (store == null) {
            return 0;
        }
        int p = store.optInt("point", -1);
        if (p < 0) {
            JSONObject bag = store.optJSONObject("bag");
            p = bag == null ? 0 : bag.optInt("point", 0);
        }
        return p;
    }

    private static int bagSizeOf(JSONObject store) {
        JSONObject bag = store == null ? null : store.optJSONObject("bag");
        return bag == null ? 0 : bag.optInt("bagsize", 0);
    }

    // --------------------------------------------------------- 仙器培养
    //
    // 11.4：REQUEST_FAIRY_EQUIP_UP_QUALITY / UPGRADE / REFINE / ENCHANT /
    //       ENLIVENER_* / FORGE / SMELT
    // 16.9：fairydeviceascensionquality / ascensiongrade / enchant / composite /
    //       baptize / enlivenerhome / fuse / shadow
    //
    // 客户端的养成大多在本地做，服务端只要：
    //   1) 把 target 物品回显（target/item/update）；
    //   2) 把 fairy_grade / fairy_value / AV / AC 写回存档；
    //   3) 不返回 base，避免存盘观察者把改动盖回去。
    // 具体数值由客户端按 sdb 表算；这里只保证“服务端不挡路”。
    private static void fillFairyEquip(String action, JSONObject req, JSONObject r) throws Exception {
        JSONObject target = req.optJSONObject("target");
        if (target == null) {
            target = req.optJSONObject("item");
        }
        if (target == null) {
            target = req.optJSONObject("equip");
        }
        long gid = target == null ? req.optLong("globalitemid", 0L)
                : target.optLong("A5", req.optLong("globalitemid", 0L));
        if (target != null) {
            int grade = target.optInt("fairy_grade", 0);
            if (action.contains("UP_QUALITY") || action.contains("UPGRADE")
                    || action.contains("ascensionquality") || action.contains("ascensiongrade")) {
                grade = Math.min(3, grade + 1);
                target.put("fairy_grade", grade);
            }
            if (!target.has("fairy_value")) {
                target.put("fairy_value", 0);
            }
            try {
                JSONObject store = readSaveObj();
                JSONArray items = store.optJSONObject("bag") == null ? null
                        : store.optJSONObject("bag").optJSONArray("item");
                if (items != null && gid > 0) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it == null || it.optLong("A5", 0L) != gid) {
                            continue;
                        }
                        String[] keys = {"fairy_grade", "fairy_value", "AV", "AC",
                                "A7", "A8", "A9", "AA"};
                        for (int k = 0; k < keys.length; k++) {
                            if (target.has(keys[k])) {
                                it.put(keys[k], target.opt(keys[k]));
                            }
                        }
                        break;
                    }
                }
                writeSave(store.toString());
            } catch (Throwable t) {
                trace("fillFairyEquip save failed " + t);
            }
            r.put("target", target);
            r.put("item", target);
            JSONArray upd = new JSONArray();
            upd.put(target);
            r.put("update", upd);
        }
        r.put("result", 1);
        r.put("is_success", 1);
        r.put("success", 1);
        r.put("add", new JSONArray());
        r.put("cost", new JSONArray());
        logLine("FAIRY " + action + " gid=" + gid + " target=" + (target != null));
    }

    private static void fillBagPrice(JSONObject req, JSONObject r) throws Exception {
        int index = req.optInt("index", 0);
        int price = bagPriceAt(index);
        JSONObject store = readSaveObj();
        r.put("point", bagPointOf(store));
        r.put("price", bagPriceItem(price));
        r.put("index", index);
        r.put("bagsize", bagSizeOf(store));
        trace("BAGPRICE index=" + index + " price=" + price + " point=" + bagPointOf(store));
    }

    private static void fillBuyBag(JSONObject req, JSONObject r) throws Exception {
        int index = req.optInt("index", 0);
        int price = bagPriceAt(index);
        synchronized (PRAY_LOCK) {
            JSONObject store = readSaveObj();
            int point = bagPointOf(store);
            if (price > 0 && point >= price) {
                point -= price;
                store.put("point", point);
            }
            JSONObject bag = store.optJSONObject("bag");
            if (bag == null) {
                bag = new JSONObject();
                store.put("bag", bag);
            }
            int cur = bag.optInt("bagsize", 0);
            int size = Math.max(cur, index + 1);
            bag.put("bagsize", size);
            writeSave(store.toString());
            r.put("point", point);
            r.put("price", bagPriceItem(price));
            r.put("bagsize", size);
            r.put("index", index);
            trace("BUYBAG index=" + index + " price=" + price + " point=" + point + " bagsize=" + size);
        }
    }

    private static void fillBuy(JSONObject req, JSONObject r) throws Exception {
        // 把整条购买请求打进协议日志：神秘商店的字段名还没确认过，先留证据。
        trace("BUY req=" + req.toString());
        int id = req.optInt("goodsid", 0);
        if (id <= 0) {
            id = req.optInt("itemid", 0);
        }
        if (id <= 0) {
            id = req.optInt("goods_id", 0);
        }
        if (id <= 0) {
            id = req.optInt("stoneid", 0);
        }
        if (id <= 0) {
            id = req.optInt("id", 0);
        }
        // 神秘商店（mysteryshopbuy2）不传 id，只传 goods_unique_mark + buy_count。
        // 货架是我们生成的，所以 mark 里放的就是商品 id 字符串。
        if (id <= 0) {
            String mark = req.optString("goods_unique_mark", "");
            if (mark != null) {
                try {
                    id = Integer.parseInt(mark.replaceAll("[^0-9]", ""));
                } catch (Throwable ignored) {
                }
            }
        }
        r.put("point", GRANT_SPIRIT);
        if (id <= 0) {
            // 老客户端缓存的货架条目没有 goods_unique_mark，购买请求里那一栏是空串。
            // 这时退回"货架第一件"，保证点击有结果（不再是 0/0）。
            try {
                JSONArray mg = mystGoods();
                if (mg != null && mg.length() > 0) {
                    JSONObject first = mg.optJSONObject(0);
                    if (first != null) {
                        id = first.optInt("id", 0);
                        trace("BUY id fallback -> " + id);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (id <= 0) {
            return;
        }
        // 客户端自己带 num（商城批量购买最多 10 个）。我们要回同样数量的堆叠数，
        // 否则即使它请求 10 个，A4 还是 1 -> 到账只有 1 个。
        int num = req.optInt("num", 0);
        if (num <= 0) {
            num = req.optInt("buy_count", 1);
        }
        int n = Math.max(Math.max(num, 1), bulkBuyCount(id));
        // 神秘商店（请求里带 goods_unique_mark）不搞"买一给99"：它按 buy_count
        // 结算，给多了客户端算不出"购买N个"。
        boolean mystBuy = req.has("goods_unique_mark");
        if (mystBuy) {
            n = Math.max(num, 1);
        }
        // 装备必须用装备形状（A7/A8/A9/AA/CC 按表填）。用 itemJson 会把
        // A9 写成 8（itemJson 里 A9=8 是给道具的），买到的装备攻击就变成 8。
        boolean isEquip = inTable("noblest_equip", id) || inTable("equip", id);
        if (isEquip) {
            n = 1;
        }
        // 启灵珠是 type=24 的堆叠道具，只能用 {A2,A4} 形状；普通 itemJson 的
        // A9/BG 会让客户端读档崩（商店买珠 → 存档炸）。
        JSONObject bought;
        if (isEquip) {
            bought = equipJson(id, 0);
        } else if (isAuraBall(id)) {
            bought = stackableJson(id, n);
        } else {
            bought = itemJson(id, n);
        }
        // 官方 4.1.1 的 buygoods 应答（v411/proto2.log 实测）只有：
        //   code / servicetime / point / goods(单个物品对象) / goodsId / base
        // 之前多塞的 goods_single / goods_list / goodsList(99 个副本) 会让
        // 客户端重复加物品/越界 → 购买瞬间闪退；而且 goods 曾经是货架数组，
        // 客户端解析不出物品 → 东西只在服务端存档里，要重进才到背包。
        if (mystBuy) {
            // 神秘商店和普通商城不是同一个处理器：5.5 的 mod 是服务端直接入包，
            // 回 goodsId + itemamount。这里照做，并附一份 savedata 让客户端
            // 能立刻刷新背包（不用重进）。
            addPurchasedToSave(bought, id, n, isEquip);
            r.put("goodsId", id);
            r.put("itemamount", n);
            r.put("savedata", readSaveObj());
        } else {
            // 官方 4.1.1 buygoods：goods 是单个物品对象 + goodsId。
            r.put("goods", bought);
            r.put("goodsId", id);
        }
        trace("BUY id=" + id + " num=" + num + " -> x" + n + " myst=" + mystBuy);
    }

    /**
     * 「买一次给 99 个」只对**可堆叠**的道具生效。
     *
     * 之前只看表名（item/pet_pill/stone），可 item 表里还有大量
     * `stackable: 0` 的唯一道具（如 510072 神秘齿轮）。给这种道具回 A4=99，
     * 客户端按 99 个非堆叠物品创建 → 购买瞬间闪退。
     * 现在读表里的 stackable 字段：1 才 99，否则 1。
     */
    private static int bulkBuyCount(int id) {
        String[] tabs = {"item", "pet_pill", "stone"};
        for (int i = 0; i < tabs.length; i++) {
            JSONObject t = null;
            try {
                t = table(tabs[i]);
            } catch (Throwable ignored) {
            }
            JSONObject row = t == null ? null : t.optJSONObject(String.valueOf(id));
            if (row != null) {
                return row.optInt("stackable", 1) == 1 ? 99 : 1;
            }
        }
        return 1;
    }

    private static boolean inTable(String name, int id) {
        try {
            JSONObject t = table(name);
            return t != null && t.optJSONObject(String.valueOf(id)) != null;
        } catch (Throwable t) {
            return false;
        }
    }



    private static JSONArray services() {
        JSONObject p = readProfile();
        return servicesFor(p != null ? p.optInt("rid", 1) : 1);
    }

    /**
     * rolelist is NOT an array: YJGlobalManager::setRoleListJson iterates the
     * member NAMES of an object, parses each key with atoi() as the *service id*
     * and only then reads the array of roles for that server:
     *
     *   if (value.isObject())
     *     for (name : value.getMemberNames()) {
     *       el = value[name];
     *       if (el.isArray()) {
     *         server = getServerInfoByServerId(atoi(name));
     *         if (server) { server->clearRoleList();
     *                       for (r : el) server->addRole(YJSimpleRoleInfo(r)); }
     *       }
     *     }
     *
     * An array here is silently ignored, which is why the role slot stayed empty.
     * Each role needs: rid / username / rolelv / roleid / wingid / weaponid / suitid
     * (YJSimpleRoleInfo::deserializeFromJsonValue; isValidData requires "rid").
     */
    /**
     * 照**官服应答**的形状生成一个角色条目。
     *
     * `/sdcard/yj/login.json` 里那份官服 login 应答的 rolelist 条目是这样的：
     *   {"rid":"1001","rolelv":"1","roleid":"1","username":"玩家","dateline":"...",
     *    "service":1,"wingid":"0", ... }
     * 注意**数值全是字符串**（只有 service 是 int）。客户端
     * YJSimpleRoleInfo::deserializeFromJsonValue 是按字符串取值的，
     * 我们自己拼 int 的条目会让它在多角色场景里拿到空指针
     * （实测 GL 线程 SIGSEGV，fault addr 0x10）。
     * 所以这里直接克隆模板条目，只把 rid/名字/等级/职业/服号换掉。
     */
    private static JSONObject simpleRole(JSONObject r) {
        JSONObject o = null;
        try {
            String tpl = readOverride("login");
            if (tpl != null) {
                JSONObject t = new JSONObject(tpl);
                JSONObject rl = t.optJSONObject("rolelist");
                if (rl != null) {
                    Iterator<String> it = rl.keys();
                    while (it.hasNext() && o == null) {
                        JSONArray arr = rl.optJSONArray(it.next());
                        if (arr != null && arr.length() > 0) {
                            JSONObject first = arr.optJSONObject(0);
                            if (first != null) {
                                o = new JSONObject(first.toString());
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (o == null) {
            o = new JSONObject();
        }
        try {
            int lv = r.optInt("rolelv", 1);
            int svc = Math.max(1, r.optInt("service", 1));
            o.put("rid", String.valueOf(r.optInt("rid", 1)));
            o.put("username", r.optString("username", "玩家"));
            o.put("rolelv", String.valueOf(lv));
            o.put("rolelevel", String.valueOf(lv));
            o.put("roleid", String.valueOf(r.optInt("roleid", 1)));
            o.put("service", svc);
            o.put("exp", String.valueOf(r.optInt("roleexp", 0)));
        } catch (Throwable ignored) {
        }
        return o;
    }

    /**
     * rolelist：**每个服挂一个角色**。
     *
     * 这个客户端的角色选择界面是按「服」来的：一个 serverId 下面只显示一个
     * 莲花位。以前所有角色都塞在 service=1 里，客户端只认第一个，新建的角色
     * 就永远看不见（还会在旧代码里被「裁剪到一个角色」删掉）。
     * 现在按 roles.json 里每个角色自己的 service 分组，玩家点右下角「服务器」
     * 就能在单机服1/2/3… 之间切换角色。
     */
    /**
     * 只发**当前这个服**的角色（一个服一个角色）。
     *
     * 实测：rolelist 里只要出现第 2 个角色，登录场景就在 GL 线程里 SIGSEGV
     * （客户端 11.4 的角色选择场景按「一服一角色」写死了）。所以每次登录只给
     * 当前 service 的那一个角色；玩家要换角色，点右下角「服务器」切服，
     * 下一次 login 请求的 service 变了，自然就拿到另一个角色。
     */
    /**
     * 登录时只下发**当前激活的那一个角色**，固定挂在 serverId=1 上。
     *
     * 实测（11.4 客户端）：rolelist 里出现第 2 个角色、或者出现 serverId≠1 的键，
     * 登录场景都会在 GL 线程里 SIGSEGV。所以对外永远是「一服一角色」的形状，
     * 多个存档靠「切换角色」按钮改 lastrid.txt，再重启进程生效。
     */
    private static JSONObject roleListActive() {
        JSONObject byServer = new JSONObject();
        try {
            JSONArray roles = readRoles();
            int want = lastRid();
            JSONObject pick = null;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                if (r.optInt("rid", 0) == want) {
                    pick = r;
                    break;
                }
            }
            if (pick == null && roles.length() > 0) {
                pick = roles.optJSONObject(0);
            }
            JSONArray arr = new JSONArray();
            if (pick != null) {
                JSONObject sr = simpleRole(pick);
                sr.put("service", 1);
                sr.put("serverid", 1);
                arr.put(sr);
            }
            byServer.put("1", arr);
        } catch (Exception t) {
            trace("roleListActive failed " + t);
        }
        return byServer;
    }

    private static JSONObject roleListForService(int svc) {
        JSONObject byServer = new JSONObject();
        try {
            JSONArray roles = readRoles();
            JSONArray arr = new JSONArray();
            JSONArray all = new JSONArray();
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                all.put(simpleRole(r));
                if (Math.max(1, r.optInt("service", 1)) == svc) {
                    arr.put(simpleRole(r));
                }
            }
            if (arr.length() == 0) {
                // 这个服还没角色：给第一个角色，别让莲花位空着（空位客户端也会崩）
                if (all.length() > 0) {
                    arr.put(all.optJSONObject(0));
                }
            }
            byServer.put(String.valueOf(Math.max(1, svc)), arr);
        } catch (Exception t) {
            trace("roleListForService failed " + t);
        }
        return byServer;
    }

    private static JSONObject roleListAll() {
        JSONObject byServer = new JSONObject();
        try {
            JSONArray roles = readRoles();
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                String key = String.valueOf(Math.max(1, r.optInt("service", 1)));
                JSONArray arr = byServer.optJSONArray(key);
                if (arr == null) {
                    arr = new JSONArray();
                    byServer.put(key, arr);
                }
                arr.put(simpleRole(r));
            }
        } catch (Exception t) {
            trace("roleListAll failed " + t);
        }
        return byServer;
    }

    private static JSONObject roleListFor(String username, int roleid, int rid) {
        JSONObject byServer = new JSONObject();
        try {
            int lv = 1;
            JSONObject p = readProfile();
            if (p != null) {
                lv = p.optInt("rolelv", 1);
            }
            JSONArray arr = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("rid", rid);
            o.put("username", username);
            o.put("rolelv", lv);
            o.put("rolelevel", lv);
            o.put("roleid", roleid);
            o.put("level", 1);
            o.put("exp", 0);
            o.put("vip", 0);
            o.put("wingid", 0);
            o.put("weaponid", 0);
            o.put("suitid", 0);
            arr.put(o);
            byServer.put("1", arr);   // key = service id
        } catch (Exception ignored) {
        }
        return byServer;
    }

    /** roles.json 里某个 rid 的真实等级（不在表里返回 0）。 */
    private static int levelOfRole(JSONArray roles, int rid) {
        if (roles == null) {
            return 0;
        }
        for (int i = 0; i < roles.length(); i++) {
            JSONObject r = roles.optJSONObject(i);
            if (r != null && r.optInt("rid", 0) == rid) {
                return r.optInt("rolelv", 0);
            }
        }
        return 0;
    }

    /**
     * 把 /sdcard/yj/login.json / login_enterserver.json 当成「形状模板」。
     *
     * 客户端对这份应答的形状极其敏感（少一个键就原生崩），所以模板不能删。
     * 但模板里的**数值**是新建角色的 1 级数据：
     *
     *     "rolelist": {"1":[{"rid":"1001","rolelv":"1", ...}]}
     *     "savedata": {"roledata":{"role":{"rolelv":"1","attribute":{"hp":"80", ...}}}}
     *
     * 以前这里原样发出去，于是：
     *   ① 登录界面永远显示 Lv 1；
     *   ② 客户端拿 rolelist / savedata 里的等级去同步角色，
     *      每登录一次就把存档压回 1 级 —— 这就是「每次登录等级变 1」。
     *
     * 官服（8.0 / 16.9）的 login 应答里 rolelist 和 savedata 都是数据库里的
     * 真实数据，这里照做：形状照抄模板，数值换成 roles.json + 当前存档。
     */
    private static String personalizeLogin(String json, JSONObject req) throws Exception {
        JSONObject o = new JSONObject(json);
        // 模板里的 base/pkservice 是旧的 8078（11.4 那份 login.json），13.x 会拿它去连
        // 游戏大厅 TCP，连不上就卡在「检查游戏更新」。这里统一改成当前端口。
        o.put("base", baseBlock());
        o.put("pkservice", baseBlock().optJSONObject("pkservice"));
        ensurePresetRoles();
        JSONArray roles = readRoles();
        JSONObject prof = readProfile();
        int rid = prof != null ? prof.optInt("rid", 0) : 0;
        if (rid <= 0) {
            rid = lastRid();
        }
        String uname = prof != null ? prof.optString("username", "玩家") : "玩家";

        // 当前角色：客户端请求里带 service（它记住的上次选的服），
        // 官服就是「一个服一个角色」，所以先按服找，找不到再用 lastrid。
        int reqSvc = req == null ? 0 : req.optInt("service", 0);
        if (reqSvc <= 0) {
            reqSvc = 1;
        }
        int activeRid = lastRid();
        JSONObject chosen = null;
        // 官服是「一个服里多个角色」，所以先按上次登录的 rid 找，
        // 找不到再用客户端选中的服里的第一个角色。
        for (int i = 0; i < roles.length(); i++) {
            JSONObject r = roles.optJSONObject(i);
            if (r != null && r.optInt("rid", 0) == activeRid) {
                chosen = r;
                break;
            }
        }
        if (chosen == null) {
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r != null && Math.max(1, r.optInt("service", 1)) == reqSvc) {
                    chosen = r;
                    break;
                }
            }
        }
        if (chosen == null) {
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r != null && r.optInt("rid", 0) == rid) {
                    chosen = r;
                    break;
                }
            }
        }
        if (chosen == null && roles.length() > 0) {
            chosen = roles.optJSONObject(0);
        }
        if (chosen != null) {
            rid = chosen.optInt("rid", rid);
            uname = chosen.optString("username", uname);
            reqSvc = Math.max(1, chosen.optInt("service", 1));
            sCurRid = rid;
            try {
                java.io.FileOutputStream lo = new java.io.FileOutputStream(
                        new java.io.File(dir(), "lastrid.txt"));
                lo.write(String.valueOf(rid).getBytes("UTF-8"));
                lo.close();
            } catch (Throwable ignored) {
            }
        }

        // 1) rolelist：**按服务分组、每个服挂它自己的角色**（官服 / 16.9 的形状）。
        //    玩家点右下角「服务器」切到别的服，就会重新发一次 login（带新的 service），
        //    服务端再把这个服的角色发回去 —— 这就是官服换角色的方式。
        if (roles != null && roles.length() > 0) {
            o.put("rolelist", roleListAll());
        } else {
            JSONObject rl = o.optJSONObject("rolelist");
            if (rl != null && rl.length() > 0) {
                Iterator<String> kit = rl.keys();
                while (kit.hasNext()) {
                    JSONArray arr = rl.optJSONArray(kit.next());
                    if (arr == null) {
                        continue;
                    }
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject role = arr.optJSONObject(i);
                        if (role == null) {
                            continue;
                        }
                        int lv = levelOfRole(roles, role.optInt("rid", 0));
                        if (lv > 0) {
                            if (role.opt("rolelv") instanceof String) {
                                role.put("rolelv", String.valueOf(lv));
                                role.put("rolelevel", String.valueOf(lv));
                            } else {
                                role.put("rolelv", lv);
                                role.put("rolelevel", lv);
                            }
                        }
                    }
                }
            }
        }

        // 2) savedata：**直接用存档**，不要和 16.8 的模板合并。
        //    模板（/sdcard/yj/login_enterserver.json）是 16.8.2 导出的，
        //    它的 level 是数组、pet 是空数组、还有 fate_god_raise_degree_cost /
        //    true_spirit_bag 这些 11.4 没有的块；只要有一块漏进应答，客户端
        //    切角色时就弹「存档数据已损坏!」。只有完全没有存档时才用模板。
        JSONObject store = readSaveObj();
        if (store.length() > 0) {
            // 13.x 读 11.4 的档会认为 appversion 太旧，卡在「检查游戏更新」；
            // 服务端下发前统一改成客户端自己的 dataversion。
            if (isV13(req)) {
                JSONObject rd = store.optJSONObject("roledata");
                if (rd != null) {
                    String ver = req.optString("dataversion", "13.0.3");
                    rd.put("appversion", ver);
                    rd.put("saveversion", ver);
                }
            }
            // 登录应答也会带整份 savedata；坏形状的启灵珠在这里不洗掉的话，
            // 客户端在角色选择界面就会崩，根本走不到 roledata2。
            sanitizeAuraBalls(store);
            sanitizeItemStacks(store);
            ensureSoarMaterials(store);
            // 真灵关闭时把残留的真灵块洗掉（fix259 打开过、之后必须能干净回退）
            stripTrueSpirit(store);
            // 落盘：保证下次登录、切角色时飞升材料仍在（客户端不一定会立刻上传）
            writeSave(store.toString());
            o.put("savedata", store);
        }

        // 2.5) 传承槽配置（slot_stunt_*）：至尊装备的「属性传承比例」靠它算。
        putSlotStuntConfig(o);
        // 官服 login 应答里同样带 extremeCnf（至尊费用/比例规则表），
        // 少发一份客户端在「角色页直接进游戏」那条路上会拿不到。
        o.put("extremeCnf", extremeCnf());

        // 3) 当前角色 id / 上次登录记录（模板里都是 "1001"，多角色或换档后就错了）。
        if (rid > 0) {
            o.put("rid", String.valueOf(rid));
            o.put("lastrid", String.valueOf(rid));
            o.put("cur_role_id", String.valueOf(rid));
        }
        if (reqSvc > 0) {
            o.put("service", reqSvc);
        }
        JSONObject last = o.optJSONObject("lastdata");
        if (last != null) {
            last.put("service", reqSvc > 0 ? reqSvc : 1);
            last.put("rid", String.valueOf(rid));
            last.put("username", uname);
        }
        o.put("username", uname);
        if (isV13(req)) {
            JSONObject osd = o.optJSONObject("savedata");
            if (osd != null) {
                JSONObject ord = osd.optJSONObject("roledata");
                if (ord != null) {
                    String ver = req.optString("dataversion", "13.0.3");
                    ord.put("appversion", ver);
                    ord.put("saveversion", ver);
                }
            }
        }
        o.put("code", 1000);
        return o.toString();
    }

    private static void fillLogin(JSONObject req, JSONObject r) throws Exception {
        // 13.x 的 login 应答和 11.4 不是一个形状：13.x 用 create_role_count(int)、
        // lastdata(array)、session/bindmsg/role_warehouse/role_delete；
        // 11.4 那套 object 形状会让 13.x 在 GLThread 里把 int 当字符串 strlen → SIGSEGV。
        if (isV13(req)) {
            fillLoginV13(req, r);
            return;
        }
        // YJSaveLoadManager::analyzeAndApplyDataToGame runs when the login data
        // arrives (the "存档数据已损坏！" banner shows on the character-select
        // screen, before the town). A crash probe showed it looks up exactly one
        // key - "base" - five times: missing -> FUN_0042af20(), string ->
        // YJError("存档数据已损坏！"), object -> base.host/base.port go to
        // YJSocketConnectToGameLobby::setLobbyAddress.
        ensurePresetRoles();
        r.put("base", baseBlock());
        r.put("services", services());
        if (wantNewRole()) {
            // 「新建角色」模式：给客户端一个空角色表，它就会弹创建角色界面。
            trace("login: new-role mode, returning empty rolelist");
            r.put("rolelist", new JSONObject());
            r.put("lastdata", new JSONObject());
            r.put("username", "玩家");
            JSONObject crcN = new JSONObject();
            for (int i = 1; i <= 5; i++) {
                crcN.put(String.valueOf(i), 1);
            }
            r.put("create_role_count", crcN);
            r.put("uid", 1);
            r.put("userid", 1);
            r.put("serviceid", 1);
            r.put("bind", 1);
            // 不铺存档：铺了客户端会以为角色还在，就不弹创建角色界面了。
            return;
        }
        JSONObject p = readProfile();
        if (p == null && AUTO_ROLE) {
            // Optional: pre-create a Wukong so the login lotus is never empty.
            // Off by default - an empty rolelist makes v8 show its normal
            // create-role screen, which is where the player picks 悟空/唐僧/八戒/沙僧.
            p = new JSONObject();
            p.put("username", "玩家");
            p.put("rid", 1);
            p.put("roleid", 1);
            p.put("rolelv", 1);
            p.put("roleexp", 0);
            writeProfile(p);
            trace("profile: default character created");
        }
        if (p != null) {
            // Existing local character: report it so the client shows the role
            // instead of going through create-role again.
            r.put("rolelist", roleListAll());
            JSONObject last = new JSONObject();
            last.put("service", 1);
            last.put("rid", p.optInt("rid", 1));
            last.put("username", p.optString("username", "玩家"));
            r.put("lastdata", last);
            r.put("lastrid", p.optInt("rid", 1));
            r.put("username", p.optString("username", "玩家"));
        } else {
            // Fresh local account: no roles and no previous-login record.
            // (rolelist non-empty + empty lastdata makes the client dereference
            //  a null JsonCpp value; rolelist empty + non-empty lastdata shows
            //  the "登录记录数据错误" banner.)
            r.put("rolelist", new JSONObject());
            r.put("lastdata", new JSONObject());
            r.put("username", "玩家");
        }
        // v8 login handler also wants this, keyed by service id, or the login
        // fails with 创建角色数量数据获取失败 and the client re-sends login.
        // 这条以前写死 1 是能正常显示角色的；改成 6 会让登录界面变空，
        // 说明它更像「已创建数量」而不是「剩余坑位」。先保持 1。
        JSONObject crc = new JSONObject();
        for (int i = 1; i <= 5; i++) {
            crc.put(String.valueOf(i), 1);
        }
        r.put("create_role_count", crc);
        r.put("uid", 1);
        r.put("userid", 1);
        r.put("serviceid", 1);
        r.put("bind", 1);
        mergeSave(r);
    }

    /** 13.x 客户端（version/dataversion 以 13 开头）。 */
    private static boolean isV13(JSONObject req) {
        if (req == null) {
            return false;
        }
        String v = req.optString("version", "");
        String dv = req.optString("dataversion", "");
        return v.startsWith("s13") || v.startsWith("13.") || dv.startsWith("13.");
    }

    /**
     * 13.x 的 login 应答形状（对照 16.9/13.0.3）：
     *   create_role_count 是 int（不是 11.4 的按服对象）
     *   lastdata / role_warehouse / role_delete 是数组
     *   session / bindmsg 必须存在，少一个 13.x 会在 GLThread 里 strlen 空指针。
     * 没有角色时返回空 rolelist + 空 lastdata，让 13.x 走它自己的建角界面。
     */
    private static void fillLoginV13(JSONObject req, JSONObject r) throws Exception {
        r.put("base", baseBlock());
        JSONArray svcs = new JSONArray();
        for (int svc = 1; svc <= 5; svc++) {
            JSONObject s = new JSONObject();
            s.put("service", svc);
            s.put("name", svc == 1 ? "单机服" : ("单机服" + svc));
            JSONArray rids = new JSONArray();
            rids.put(1);
            s.put("roleids", rids);
            JSONArray pay = new JSONArray();
            pay.put(1);
            s.put("pay_unlock_roles", pay);
            s.put("unique_id", svc);
            s.put("isnew", 0);
            s.put("createtime", 0);
            s.put("starttime", 0);
            s.put("endtime", 0);
            s.put("state", 1);
            s.put("new", 0);
            svcs.put(s);
        }
        r.put("services", svcs);
        r.put("rolelist", new JSONObject());
        r.put("role_warehouse", new JSONArray());
        r.put("role_delete", new JSONArray());
        r.put("lastdata", new JSONArray());
        r.put("bindmsg", "");
        r.put("session", "local-session");
        r.put("create_role_count", 8);
        r.put("username", "玩家");
        r.put("servicename", "单机服");
        r.put("uid", 1);
        r.put("userid", 1);
        r.put("serviceid", 1);
        r.put("bind", 1);
        JSONObject pk = new JSONObject();
        pk.put("host", "127.0.0.1");
        pk.put("port", PORT);
        r.put("pkservice", pk);
    }

    /**
     * Assign the incoming `savedata` object into the stored save, key by key.
     *
     * The client sends two different shapes and both must survive:
     *   - periodic "savedata" upload: {roledata:{...}}          (no bag/level)
     *   - "lvvictory" after a level:  {bag:{...}, level:{...}, roledata:{...}}
     * Replacing the stored blob wholesale let the periodic upload erase the
     * level records and bag that lvvictory had just written, which is exactly why
     * cleared levels never came back.
     */
    private static synchronized void storeSaveUpload(JSONObject incoming) {
        try {
            JSONObject store = readSaveObj();
            JSONObject before = new JSONObject(store.toString());
            Iterator<String> it = incoming.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object nv = incoming.get(k);
                Object ov = store.opt(k);
                if (nv instanceof JSONObject && ov instanceof JSONObject) {
                    store.put(k, mergeObject((JSONObject) ov, (JSONObject) nv));
                } else {
                    store.put(k, nv);
                }
            }
            keepProgress(before, store);
            // 魂器装备一律写成八阶，再落盘，这样下次登录读到的就是八阶
            normalizeSoulDegrees(store.optJSONObject("bag"));
            clearBagOnce(store);
            // putTrueSpiritInPetBag(store); // 9005 塞宠物背包会让角色选择预览崩溃，停用
            removeTrueSpiritFromPetBag(store);
            sanitizeFateGod(store);
            stripBrokenSwords(store);
            // 上传的启灵珠也洗一遍，避免坏形状又落盘（否则下次登录继续炸）。
            if (!sV13) {
                sanitizeAuraBalls(store);
                sanitizeItemStacks(store);
                // 客户端上传的 bag.item 会把 saved/unsaved 冲掉；用服务端权威的
                // yj_pray 再写回去，并重建 savedata.prayequips。
                praySyncStore(store);
                // 10 阶传说装备的峰值属性（幂等，原始四维记在 yj_peak 里）。
                applyPeakPray(store);
                // 传说装备补 fairy_grade，解锁仙器面板。
                markFairyEquip(store);
                // 主动技能 / 本命神技能按当前职业校正（16.9 的 role_creation 同款规则）。
                reconcileRoleSkills(store);
                // 客户端上传的至尊装备经常把 CV 冲成 0，落盘前按 CE 补回。
                normalizeNoblestInherit(store);
                traceNoblestItems(store, "upload");
            }
            stripTrueSpirit(store);
            writeSave(store.toString());
        } catch (Throwable t) {
            trace("storeSaveUpload failed " + t);
        }
    }

    /**
     * Never let an upload roll back progress.
     *
     * The client's periodic savedata tick uploads the state it knew at load time
     * (roleexp 0 / no psl), which used to overwrite the exp that lvvictory had
     * just granted.  Keep the higher level/exp and the union of cleared sublevels.
     */
    private static void keepProgress(JSONObject before, JSONObject now) {
        try {
            JSONObject or = before.optJSONObject("roledata");
            JSONObject nr = now.optJSONObject("roledata");
            if (or != null) {
                JSONObject orole = or.optJSONObject("role");
                if (orole != null) {
                    if (nr == null) {
                        nr = new JSONObject();
                        now.put("roledata", nr);
                    }
                    JSONObject nrole = nr.optJSONObject("role");
                    if (nrole == null) {
                        nrole = new JSONObject();
                        nr.put("role", nrole);
                    }
                    int olv = orole.optInt("rolelv", 1);
                    int nlv = nrole.optInt("rolelv", 1);
                    int oexp = orole.optInt("roleexp", 0);
                    int nexp = nrole.optInt("roleexp", 0);
                    if (olv > nlv) {
                        nrole.put("rolelv", olv);
                        nrole.put("roleexp", oexp);
                    } else if (olv == nlv && oexp > nexp) {
                        nrole.put("roleexp", oexp);
                    }
                    int finLv = nrole.optInt("rolelv", 1);
                    int finExp = nrole.optInt("roleexp", 0);
                    // 角色一览表里的等级/经验也跟着涨（登录界面显示用）。
                    JSONObject cur = readProfile();
                    touchRole(sCurRid > 0 ? sCurRid : 1,
                            cur != null ? cur.optInt("roleid", 1) : 1,
                            cur != null ? cur.optString("username", "玩家") : "玩家",
                            finLv, finExp);
                    if (cur != null && finLv > cur.optInt("rolelv", 1)) {
                        cur.put("rolelv", finLv);
                        cur.put("roleexp", finExp);
                        writeProfile(cur);
                    }
                }
            }
            // 邮件发放的宠物：客户端上传的 savedata 没有它时不能被覆盖掉。
            try {
                JSONArray bpet = bpetOf(before);
                JSONArray npet = bpetOf(now);
                if (bpet != null && npet != null) {
                    java.util.HashSet<Integer> have = new java.util.HashSet<Integer>();
                    for (int i = 0; i < npet.length(); i++) {
                        JSONObject o = npet.optJSONObject(i);
                        if (o != null) {
                            have.add(o.optInt("dataid", 0));
                        }
                    }
                    for (int i = 0; i < bpet.length(); i++) {
                        JSONObject o = bpet.optJSONObject(i);
                        if (o != null && !have.contains(o.optInt("dataid", 0))) {
                            npet.put(o);
                        }
                    }
                }
            } catch (Throwable t) {
                trace("keep pets failed " + t);
            }
            JSONArray opsl = pslOf(before);
            JSONArray npsl = pslOf(now);
            if (opsl != null && npsl != null && opsl.length() > 0) {
                for (int i = 0; i < opsl.length(); i++) {
                    JSONObject o = opsl.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    int id = o.optInt("id", 0);
                    boolean has = false;
                    for (int j = 0; j < npsl.length(); j++) {
                        JSONObject n = npsl.optJSONObject(j);
                        if (n != null && n.optInt("id", 0) == id) {
                            has = true;
                            if (o.optInt("s", 0) > n.optInt("s", 0)) {
                                n.put("s", o.optInt("s", 0));
                            }
                            break;
                        }
                    }
                    if (!has) {
                        npsl.put(o);
                    }
                }
            }
        } catch (Throwable t) {
            trace("keepProgress failed " + t);
        }
    }

    private static JSONArray bpetOf(JSONObject store) {
        try {
            JSONObject pet = store.optJSONObject("pet");
            if (pet == null) {
                pet = new JSONObject();
                store.put("pet", pet);
            }
            Object o = pet.opt("petinfo");
            if (o instanceof JSONArray) {
                return (JSONArray) o;
            }
            JSONArray a = new JSONArray();
            pet.put("petinfo", a);
            return a;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONArray pslOf(JSONObject store) {
        try {
            JSONObject lv = store.optJSONObject("level");
            if (lv == null) {
                return null;
            }
            Object o = lv.opt("psl");
            if (o instanceof JSONArray) {
                return (JSONArray) o;
            }
            JSONArray a = new JSONArray();
            lv.put("psl", a);
            return a;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Merge one level deep, but never let a member disappear just because the
     * other side did not mention it.
     *
     * The periodic "savedata" upload sends a bag block WITHOUT "item", so a plain
     * put() erased the inventory on every autosave - the inventory looked fine
     * until the next save tick, then came back empty after a restart.
     */
    private static JSONObject mergeObject(JSONObject old, JSONObject neu) throws Exception {
        JSONObject out = new JSONObject();
        Iterator<String> it = old.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.put(k, old.get(k));
        }
        it = neu.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = neu.get(k);
            if (v instanceof JSONObject && out.opt(k) instanceof JSONObject) {
                out.put(k, mergeObject((JSONObject) out.get(k), (JSONObject) v));
            } else if (v instanceof JSONArray && "item".equals(k)) {
                out.put(k, v);
            } else {
                out.put(k, v);
            }
        }
        return out;
    }

    /**
     * Two different requests land here and they are NOT interchangeable:
     *
     *   "roledata2"  = download. YJSaveLoadManager::requestSaveDataFromServer
     *                  (single caller: YJGlobalManager::startGameWithRID) and the
     *                  reply is fed to analyzeAndApplyDataToGame, which reads
     *                  value["savedata"] and value["pkservice"].
     *   "savedata"   = periodic / checkpoint upload. Carries value["savedata"].
     *
     * Deciding by the presence of "savedata" in the request cannot work: the
     * upload sends {"rid":...,"savedata":{...}} and so does nothing on the
     * download side, so the old heuristic replied to uploads with a download body.
     */
    // ------------------------------------------------- 至尊装备升品 / 升星（handleaction）

    /** handleaction 的 type：4 = 升品（品阶 +1），5 = 升星（星级 +1）。 */
    private static final int HA_UPGRADE_QUALITY = 4;
    private static final int HA_UPGRADE_STAR = 5;

    /**
     * 至尊装备升品 / 升星（请求 tag = REQUEST_SAVE_DATA_WITH_HANDLE）。
     *
     * <p>请求里带 `target`（完整装备 JSON）、`material`（本次材料）、
     * `logs`（按 type 分组的操作记录，每条 {@code {M:材料, T:目标}}），
     * type = 4 升品 / 5 升星。
     *
     * <p><b>应答形状是从 native 逆向出来的</b>（8.0.0：
     * `FUN_03bd0868 @0x03bd0868`，入口第一句就是 `has(json,"info")`）：
     * <pre>
     *   { "code": 1000,
     *     "info": {
     *       "result"  : int,      // 0/1
     *       "msg"     : string,   // 聊天框系统提示
     *       "titletip": string,   // 屏幕中央飘字
     *       "update"  : [item],   // 服务器改过的物品：按 A5 找到后 updateFromJson
     *       "add"     : [item],   // 新获得的物品（createItemByJson 后入包）
     *       "cost"    : [item]    // 被扣掉的物品
     *     } }
     * </pre>
     * 顶层缺 "info" 就走错误分支，弹「存档数据已损坏!」—— 以前这里回的是
     * savedata 形状的存盘应答，所以照样弹错。
     */
    private static void handleAction(JSONObject req, JSONObject r) {
        try {
            int type = req.optInt("type", 0);
            JSONObject target = req.optJSONObject("target");
            JSONObject logs = req.optJSONObject("logs");
            // 一次请求只升一级：客户端每点一次就发一次请求。logs 里条数会累积
            // （成功前不清账），按条数加会一次跳好几级，所以固定 +1。
            int steps = 1;
            if (logs != null) {
                JSONArray arr = logs.optJSONArray(String.valueOf(type));
                if (arr != null) {
                    trace("HANDLEACTION logs(" + type + ")=" + arr.length());
                }
            }
            trace("HANDLEACTION type=" + type + " steps=" + steps
                    + " target=" + (target == null ? "null" : target.toString()));

            JSONObject store = readSaveObj();
            JSONObject bag = store.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            JSONObject item = findItem(items, target);

            JSONArray update = new JSONArray();
            JSONArray cost = new JSONArray();
            String okMsg = "升品成功";
            // fix256：传承族（锻升 / 聚炼 / 传承挂接）要按官方形状回 info + 顶层附加键。
            // 官方 noblest_inherit 的每个操作都带 operation / uid / before / after 等，
            // 挂接（inlay）还要求 update_cg / target_item / add_goods / remove_goods。
            int ok = 1;
            String opName = null;
            JSONObject extra = new JSONObject();
            if (item != null && target != null) {
                // 客户端每次都会把整件装备（含传承装备对象 CQ、CD 等）带上来。
                // 之前只改 CF/CC 几个字段，CQ 从来没落盘 -> 服务器回给客户端的
                // 装备里没有传承装备，比例就永远是 0。
                java.util.Iterator<String> tk = target.keys();
                while (tk.hasNext()) {
                    String k = tk.next();
                    if ("A5".equals(k) || "A2".equals(k)) {
                        continue;
                    }
                    item.put(k, target.get(k));
                }
            }
            if (item != null) {
                // 品阶记在 CL、星级记在 CC（CC 客户端会显示成图标下的金星）。
                // 属性 A7(生命)/A8(魔法)/A9(攻击)/AA(防御) 客户端不会自己重算，
                // 但 info.update 里给什么就写什么（实测有效），所以服务端按
                // noblest_equip + noblest_enhance 的官方公式算好后一起下发：
                //
                //     属性 = 基础值 + a × 品阶^d + b × 品阶
                //
                // 反推依据：客户端自己的「升品至」预览 —— 6513 在品阶 28/29 时
                // 显示 29120 / 31060，公式算出 29126 / 31064（差 4~6，取整误差）；
                // 1513 品阶 1 预览 2090，公式算出 2094。d 取表里的 *_factor_d(=2)。
                // 星级没有公开公式，按每颗 ×1.05 近似叠乘。
                // 字段名按 16.9 的 GM（equipment.py）核过：
                //   至尊装备：BE = 显示品质(1..11)、CC = 星级(0..12)、CF = 品级(0..100)
                // 之前写的是 CL —— 客户端读的是 CF，所以点了升品没反应。
                // CL 一并同步，防止老版本界面读它。
                int quality = item.optInt("CF", 0);
                int star = item.optInt("CC", 0);
                if (type == HA_UPGRADE_QUALITY) {
                    quality += steps;
                    item.put("CF", quality);
                    // ⚠️ 不再把品阶镜像写进 CL：CL 是聚炼的到期时间戳
                    // （官方 noblest_inherit 用 CL/CM 记聚炼状态），写品阶会把聚炼
                    // 状态冲掉，读档时也会把时间戳当成品阶。
                } else if (type == HA_UPGRADE_STAR) {
                    // 至尊装备星级上限 5。
                    star = Math.min(5, star + steps);
                    item.put("CC", star);
                    // 升星加百分比属性，数值照 noblest_equip 表里该装备自己的基础
                    // 百分比再加一份（critical/damage_rate/unti_damage_rate… 这些
                    // 在 noblest_enhance 里系数全是 0，说明加成取的是表的基础值）。
                    JSONObject erow = table("noblest_equip") == null ? null
                            : table("noblest_equip").optJSONObject(String.valueOf(item.optInt("A2", 0)));
                    if (erow != null) {
                        String[][] pctMap = {
                                {"critical", "AF"}, {"damage_rate", "CJ"},
                                {"unti_damage_rate", "CK"}, {"unti_critical", "CI"},
                                {"hit", "CH"}, {"dodge", "AE"},
                                {"magDef", "AC"}, {"magoff", "CY"},
                        };
                        for (int pi = 0; pi < pctMap.length; pi++) {
                            double base = erow.optDouble(pctMap[pi][0], 0);
                            if (base <= 0) {
                                continue;
                            }
                            // 每次升星都在原值上累加：1~3 星 ×1 份基础值，4~5 星 ×2 份。
                            int mult = star <= 3 ? 1 : 2;
                            int add = (int) Math.round(base * 100 * mult / 2.0);
                            String key = pctMap[pi][1];
                            int nv = item.optInt(key, 0) + add;
                            // 防止重复点升星把暴击/暴伤无限叠加；5 星正常上限 45。
                            item.put(key, Math.min(nv, 45));
                        }
                    }
                    okMsg = "升星成功";
                } else if (type == HA_CULTIVATE) {
                    // 锻升（官方 noblest_inherit.cultivate 逐步还原）：
                    //   CE + 1，上限 CV（官服恒 100）；消耗熔炼丹 503206 + 仙气 503035。
                    //   仙气**不是背包物品**，官方扣 bag.auracount 计数器（npc.charge）；
                    //   CE ≥ 100 之后改扣元灵精魄 503199（extremeCnf 规则 9）。
                    // 原实现把 CV 当比例写（CV = CE），等于把「上限」冲成当前等级，
                    // 官方语义里 CV 永远是 100 → 这里改回官方语义。
                    int cu = Math.max(item.optInt("CE", 0),
                            Math.max(item.optInt("CU", 0), item.optInt("CW", 0)));
                    int limit = item.optInt("CV", 0);
                    if (limit <= 0 || limit > 100) {
                        limit = 100;
                    }
                    if (cu >= limit) {
                        ok = 0;
                        okMsg = "该装备已锻升到上限！";
                        trace("HANDLEACTION cultivate reject: CE=" + cu + " >= CV=" + limit);
                    } else {
                        int[] fc = fusionCost(cu);
                        int need206 = fc[1];
                        int need2 = fc[3];
                        boolean useAura = fc[2] == ITEM_XIANQI;
                        boolean have206 = bagHasStack(items, ITEM_RONG_LIANDAN, need206);
                        boolean have2 = useAura ? auraCount(store) >= need2
                                : bagHasStack(items, ITEM_YUANLING, need2);
                        if (!have206 || !have2) {
                            ok = 0;
                            okMsg = "材料不足：熔炼丹×" + need206 + "、"
                                    + (useAura ? "仙气×" : "元灵精魄×") + need2;
                            trace("HANDLEACTION cultivate reject: materials "
                                    + need206 + "/" + need2 + " have=" + have206 + "/" + have2);
                        } else {
                            takeStack(items, ITEM_RONG_LIANDAN, need206, cost);
                            if (useAura) {
                                addAura(store, -need2);
                                cost.put(costEntry(ITEM_XIANQI, need2));
                            } else {
                                takeStack(items, ITEM_YUANLING, need2, cost);
                            }
                            cu++;
                            item.put("CE", cu);
                            item.put("CU", cu);
                            item.put("CW", cu);
                            item.put("CV", 100);
                            // 属性：先按官方成长表重算基础值，再叠「传承装备 × 等级%」，
                            // 保证每次锻升幂等（不会重复累加）。
                            applyNoblestStats(item, item.optInt("CF", 0), item.optInt("CC", 0));
                            applyInheritBonus(item, cu);
                            okMsg = "锻升成功";
                            opName = "noblest_inheritance_level";
                            extra.put("field", "CE");
                            extra.put("before", cu - 1);
                            extra.put("after", cu);
                        }
                    }
                } else if (type == HA_CONCENTRATE) {
                    // 聚炼（官方 noblest_inherit type=9）：传承等级 < 100 才能用；
                    //   CL = 到期时间戳（now + 10800 = 180 分钟）、CM = 0 表示生效中，
                    //   消耗聚炼丹 503212 ×1。「临时把传承等级提到 100 级」是客户端
                    //   按 CL/CM 自己算的显示，服务端只要把这两个字段落库。
                    //   原实现只写 CV=100，没有 CL/CM → 界面既不倒计时、也不生效。
                    long now = System.currentTimeMillis() / 1000L;
                    int cu = item.optInt("CE", 0);
                    long expiry = item.optLong("CL", 0);
                    int inactive = item.optInt("CM", 1);
                    if (cu >= 100) {
                        ok = 0;
                        okMsg = "该装备传承等级达到聚炼丹效果上限，无法使用";
                    } else if (inactive == 0 && expiry > now) {
                        ok = 0;
                        okMsg = "当前聚炼尚未结束";
                    } else if (!bagHasStack(items, ITEM_JULIANDAN, 1)) {
                        ok = 0;
                        okMsg = "聚炼丹不足";
                    } else {
                        takeStack(items, ITEM_JULIANDAN, 1, cost);
                        item.put("CL", now + 10800L);
                        item.put("CM", 0);
                        item.put("CV", 100);
                        okMsg = "聚炼成功";
                        opName = "noblest_concentrate";
                        extra.put("expires", now + 10800L);
                        extra.put("duration", 10800);
                    }
                } else if (type == HA_INLAY || type == HA_INLAY_ALT) {
                    // 传承挂接（官方 noblest_inherit type=27）：把「传承装备」挂进
                    // 「继承装备」的传承槽。官方落库只做三件事：
                    //   target.CD = 源装备实例 id（A5）；源装备.CG = target.A5；CQ 由客户端重建。
                    // 11.4 这边一直没实现 → 槽位永远空 → 面板「属性传承比例」恒 0.0%。
                    int srcUid = inheritSourceUid(req, target);
                    if (srcUid <= 0) {
                        ok = 0;
                        okMsg = "请选择传承装备";
                        trace("HANDLEACTION inlay reject: no source uid, req keys=" + req.length());
                    } else {
                        JSONObject srcItem = findByA5(items, srcUid);
                        if (srcItem == null) {
                            ok = 0;
                            okMsg = "继承装备不存在！";
                            trace("HANDLEACTION inlay reject: source A5=" + srcUid + " not in bag");
                        } else if (item.optInt("A5", 0) == srcUid) {
                            ok = 0;
                            okMsg = "不能把自己放进传承槽";
                        } else {
                            int self = item.optInt("A5", 0);
                            item.put("CD", srcUid);
                            item.remove("CQ");
                            // 防御：官方是客户端自己重建 CQ，11.4 的客户端不一定会重建，
                            // 这里直接给一份源装备快照，保证面板立刻有「传递值」可比。
                            JSONObject cq = new JSONObject(srcItem.toString());
                            cq.put("CG", self);
                            item.put("CQ", cq);
                            srcItem.put("CG", self);
                            JSONObject cgMap = new JSONObject();
                            cgMap.put(String.valueOf(self), srcUid);
                            extra.put("update_cg", cgMap);
                            extra.put("target_item", item);
                            extra.put("add_goods", new JSONArray());
                            extra.put("remove_goods", new JSONArray());
                            extra.put("source_uid", srcUid);
                            okMsg = "传承成功";
                            opName = "noblest_inlay";
                            trace("HANDLEACTION inlay target=" + self + " <- source=" + srcUid);
                        }
                    }
                } else if (type == 7 || type == 8 || type == 10) {
                    // 飞升 / 二次飞升：noblest_equip 的 soar_id 指向下一形态
                    // （1316→1318→1319、1513→1515→1516），A5 实例 id 不变。
                    JSONObject erow = table("noblest_equip") == null ? null
                            : table("noblest_equip").optJSONObject(String.valueOf(item.optInt("A2", 0)));
                    int soarId = erow == null ? 0 : erow.optInt("soar_id", 0);
                    if (soarId > 0 && soarId != item.optInt("A2", 0)) {
                        item.put("A2", soarId);
                        okMsg = erow.optInt("soar_level", 0) == 0 ? "飞升成功" : "二次飞升成功";
                    } else {
                        okMsg = "已达最高飞升";
                    }
                }
                // 升品/升星/飞升按表重算四维；锻升分支自己已经算过
                // （applyNoblestStats + applyInheritBonus），这里再算一遍会把
                // 刚叠上去的「传承加成」抹掉，所以跳过。
                if (type != HA_CULTIVATE) {
                    applyNoblestStats(item, quality, star);
                }
                update.put(item);
                // 扣材料：material[].A4 是这次要消耗的数量。
                // ⚠️ 传承族（6/9/27/54）自己按官方费用扣过了，这里必须跳过：
                // 挂接时客户端把「传承装备」也塞进 material（A4=1），这段老逻辑会用
                // findStack 命中那件装备、把它的 A4 减到 0 —— 装备会被当成用完消失。
                boolean inheritOp = type == HA_CULTIVATE || type == HA_CONCENTRATE
                        || type == HA_INLAY || type == HA_INLAY_ALT;
                JSONArray mats = inheritOp ? null : req.optJSONArray("material");
                if (mats != null && items != null) {
                    for (int i = 0; i < mats.length(); i++) {
                        JSONObject m = mats.optJSONObject(i);
                        if (m == null) {
                            continue;
                        }
                        int need = m.optInt("A4", 0) * steps;
                        if (need <= 0) {
                            continue;
                        }
                        JSONObject have = findStack(items, m.optInt("A2", 0));
                        if (have != null) {
                            have.put("A4", Math.max(0, have.optInt("A4", 0) - need));
                            cost.put(have);
                        }
                    }
                }
                writeSave(store.toString());
                trace("HANDLEACTION applied A5=" + item.optInt("A5", 0)
                        + " CF=" + item.optInt("CF", 0) + " CL=" + item.optInt("CL", 0) + " CC=" + item.optInt("CC", 0));
            } else {
                trace("HANDLEACTION target not found, A5="
                        + (target == null ? 0 : target.optInt("A5", 0)));
            }

            JSONObject info = new JSONObject();
            info.put("result", ok);
            info.put("update", update);
            info.put("msg", okMsg);
            info.put("titletip", okMsg);
            if (cost.length() > 0) {
                info.put("cost", cost);
            }
            r.put("info", info);
            // 传承族的官方顶层附加键：挂接必须带 update_cg（否则客户端不认这次镶嵌，
            // 槽位会被清回去），锻升/聚炼带 operation + uid + before/after/expires。
            if (opName != null) {
                r.put("operation", opName);
            }
            if (item != null) {
                r.put("uid", String.valueOf(item.optInt("A5", 0)));
                // fix257 诊断用：把这一次操作后装备的传承字段打进 protocol.log，
                // 「比例还是 0」时看一眼就知道 CD/CQ 到底有没有落库。
                trace("HANDLEACTION done type=" + type + " A5=" + item.optInt("A5", 0)
                        + " CE=" + item.optInt("CE", 0) + " CV=" + item.optInt("CV", 0)
                        + " CF=" + item.optInt("CF", 0) + " CC=" + item.optInt("CC", 0)
                        + " CD=" + item.optInt("CD", 0) + " CQ=" + (item.optJSONObject("CQ") != null)
                        + " CL=" + item.optLong("CL", 0) + " CM=" + item.optInt("CM", 1)
                        + " result=" + ok);
            }
            Iterator<String> ek = extra.keys();
            while (ek.hasNext()) {
                String k = ek.next();
                r.put(k, extra.get(k));
            }
            // ⚠️ 这里**不能**再放 itemlimit / base：
            // 这两个键是「存盘回包」家族的标记，带上之后客户端会走
            // YJSaveLoadManager 的存盘观察者，把 info.update 刚应用好的物品改动
            // 又盖回去（实测面板一直停在 +0）。retail 这条应答也只有 info。
        } catch (Throwable t) {
            trace("handleAction failed " + t);
            try {
                JSONObject info = new JSONObject();
                info.put("result", 0);
                info.put("msg", "操作失败");
                r.put("info", info);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------- 至尊传承（官方 noblest_inherit）

    /** handleaction 的传承族 type：6 锻升、9 聚炼、27 挂接（54 是 11.4 客户端 logs 里的老编号，兼容处理）。 */
    private static final int HA_CULTIVATE = 6;
    private static final int HA_CONCENTRATE = 9;
    private static final int HA_INLAY = 27;
    private static final int HA_INLAY_ALT = 54;

    /** 熔炼丹 / 聚炼丹 / 元灵精魄（`item.sdb`，可堆叠）。 */
    private static final int ITEM_RONG_LIANDAN = 503206;
    private static final int ITEM_JULIANDAN = 503212;
    private static final int ITEM_YUANLING = 503199;
    /** 仙气：**不是背包物品**，是 `bag.auracount` 计数器（官方 npc.charge 扣的就是它）。 */
    private static final int ITEM_XIANQI = 503035;

    /**
     * 官方 roledata2 **顶层** `extremeCnf`（至尊用的服务器规则表）。
     *
     * <p>这是 13.0 私服 2026-11-12 那条「至尊修复」补的东西，注释原文：
     * <i>「extremeCnf 是官方 roledata2 响应顶层下发的服务器规则表（fixtures dd671c79
     * 实证 PATH=/response/extremeCnf，action=roledata2）。缺了它 → 至尊升品/升星 UI
     * 费用显示 0、确认时客户端读空配置弹「存档已损坏」」</i>。
     *
     * <p>11.4 这边一直没发过 → 至尊面板（升品/升星/锻升/聚炼）费用全是 0，
     * 「属性传承比例」也恒为 0.0%。数据取自官服 roledata2 fixture，与 13.0 的
     * `work/1300/sdb/extreme_cnf.json` 完全一致。
     */
    private static final String EXTREME_CNF_JSON =
            "{\"1\":{\"503199\":[30,60,100,150,220,290,380,480,590,700,840,980,1130,1290,1470,"
            + "1650,1850,2060,2280,2500,2750,3000,3260,3530,3820,4110,4420,4740,5070,5400,5760,6120,"
            + "6490,6870,7270],\"-1\":[1,2,3,5,6,5,6,7,8,9,10,10,11,12,13,14,15,16,15,16,18,19,20,21,"
            + "23,24,20,21,22,23,24,26,27,28,29]},\"2\":{\"AF\":[7,14,22,31,40],\"CH\":[7,14,22,31,40],"
            + "\"CJ\":[7,14,22,31,40],\"AC\":[1,3,5,7,10],\"AE\":[4,8,13,19,25],\"CI\":[7,14,22,31,40],"
            + "\"CK\":[7,14,22,31,40],\"CKA\":[2,4,7,11,15],\"CJA\":[2,4,7,11,15],\"CHA\":[5,10,15,20,25],"
            + "\"CYA\":[2,4,7,11,15],\"ACA\":[1,3,5,7,10]},\"3\":{\"-1\":[100,220,440,770,1260]},"
            + "\"4\":{\"-1\":[1]},\"5\":{\"503206\":[0.05,2,6.5,-1],\"503035\":[9.5,2,45.5,451]},"
            + "\"6\":{\"-1\":[100000]},\"7\":{\"-1\":[1]},"
            + "\"8\":{\"503207\":[10,20],\"503227\":[100,200],\"503370\":[0,200]},"
            + "\"9\":{\"503206\":[0.5,2,6.5,-4550],\"503199\":[1,2,5,-8000]},"
            + "\"10\":{\"503208\":[30],\"503209\":[30],\"503210\":[30],\"503370\":[10]}}";

    /** 构造官方 `extremeCnf`（每次返回新对象，避免调用方改坏常量）。 */
    private static JSONObject extremeCnf() {
        try {
            return new JSONObject(EXTREME_CNF_JSON);
        } catch (Throwable t) {
            trace("extremeCnf parse failed " + t);
            return new JSONObject();
        }
    }

    /** 至尊族 action 名（13.0 官方分发器用的那套；11.4 也可能换名字）。 */
    private static boolean isNoblestAction(String action) {
        if (action == null) {
            return false;
        }
        String a = action.toLowerCase();
        return a.equals("noblest") || a.startsWith("noblest")
                || a.contains("extremestunt") || a.contains("extremebaptize")
                || a.equals("inlay") || a.equals("inheritequip");
    }

    /**
     * fix257 诊断：把背包里所有至尊装备的传承字段汇总成一行打进 protocol.log。
     * 「属性传承比例 0」的时候，这一行能直接看出 CD/CQ/CE/CV 到底有没有落库。
     */
    private static void traceNoblestItems(JSONObject sd, String tag) {
        try {
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            JSONObject nb = table("noblest_equip");
            if (items == null || nb == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < items.length() && n < 8; i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || nb.optJSONObject(String.valueOf(it.optInt("A2", 0))) == null) {
                    continue;
                }
                n++;
                sb.append(" [A5=").append(it.optInt("A5", 0))
                        .append(" A2=").append(it.optInt("A2", 0))
                        .append(" CE=").append(it.optInt("CE", 0))
                        .append(" CV=").append(it.optInt("CV", 0))
                        .append(" CD=").append(it.optInt("CD", 0))
                        .append(" CG=").append(it.optInt("CG", 0))
                        .append(" CQ=").append(it.optJSONObject("CQ") != null ? 1 : 0)
                        .append(" CF=").append(it.optInt("CF", 0))
                        .append(" CC=").append(it.optInt("CC", 0))
                        .append(" CL=").append(it.optLong("CL", 0))
                        .append(" CM=").append(it.optInt("CM", 1))
                        .append("]");
            }
            trace("NOBLEST-" + tag + " count=" + n + sb);
        } catch (Throwable t) {
            trace("traceNoblestItems failed " + t);
        }
    }

    /**
     * 锻升消耗（官方 `extreme_cnf` 规则 5 / 9）：
     * <pre>
     *   CE &lt; 100 → 规则 5：503206 = {0.05, 2, 6.5, -1}、503035 = {9.5, 2, 45.5, 451}
     *   CE ≥ 100 → 规则 9：503206 = {0.5, 2, 6.5, -4550}、503199 = {1, 2, 5, -8000}
     * </pre>
     * {@code amount = trunc(a × level^power + linear × level + offset)}，{@code level = CE + 1}；
     * 仙气按 10 取整。返回 {@code {id1, n1, id2, n2}}。
     */
    private static int[] fusionCost(int current) {
        double[] a206 = current < 100
                ? new double[]{0.05, 2, 6.5, -1} : new double[]{0.5, 2, 6.5, -4550};
        double[] a2nd = current < 100
                ? new double[]{9.5, 2, 45.5, 451} : new double[]{1, 2, 5, -8000};
        int second = current < 100 ? ITEM_XIANQI : ITEM_YUANLING;
        int n1 = fusionAmount(a206, current + 1);
        int n2 = fusionAmount(a2nd, current + 1);
        if (second == ITEM_XIANQI) {
            n2 = (n2 / 10) * 10;
        }
        return new int[]{ITEM_RONG_LIANDAN, Math.max(n1, 0), second, Math.max(n2, 0)};
    }

    /** float32 精度对齐官方的 `noblest_inherit._float32`。 */
    private static int fusionAmount(double[] p, int level) {
        float a = (float) p[0];
        float power = (float) p[1];
        float linear = (float) p[2];
        float offset = (float) p[3];
        float v = (float) Math.pow(level, power) * a + linear * (float) level + offset;
        return (int) v;
    }

    private static boolean bagHasStack(JSONArray items, int id, int need) {
        if (need <= 0) {
            return true;
        }
        JSONObject it = findStack(items, id);
        return it != null && it.optInt("A4", 0) >= need;
    }

    private static void takeStack(JSONArray items, int id, int need, JSONArray cost) throws Exception {
        if (need <= 0 || items == null) {
            return;
        }
        JSONObject it = findStack(items, id);
        if (it == null) {
            return;
        }
        it.put("A4", Math.max(0, it.optInt("A4", 0) - need));
        cost.put(costEntry(id, need));
    }

    private static JSONObject costEntry(int id, int n) throws Exception {
        JSONObject c = new JSONObject();
        c.put("A2", id);
        c.put("A4", n);
        return c;
    }

    private static int auraCount(JSONObject store) {
        JSONObject bag = store == null ? null : store.optJSONObject("bag");
        return bag == null ? 0 : Math.max(0, bag.optInt("auracount", 0));
    }

    private static void addAura(JSONObject store, int delta) throws Exception {
        JSONObject bag = store == null ? null : store.optJSONObject("bag");
        if (bag == null) {
            return;
        }
        bag.put("auracount", Math.max(0, bag.optInt("auracount", 0) + delta));
    }

    /** 背包里按实例 id（A5）找条目。 */
    private static JSONObject findByA5(JSONArray items, int uid) {
        if (items == null || uid <= 0) {
            return null;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.optInt("A5", 0) == uid) {
                return it;
            }
        }
        return null;
    }

    /**
     * 传承挂接的源装备实例 id。
     *
     * <p>官方请求带 `source`（对象里取 A5/uid，或直接是 id）；11.4 客户端如果先把
     * CD 算在 target 里，也认 `target.CD`。多个键都认，避免版本差异导致点了没反应。
     */
    private static int inheritSourceUid(JSONObject req, JSONObject target) {
        Object src = req.opt("source");
        int uid = 0;
        if (src instanceof JSONObject) {
            JSONObject so = (JSONObject) src;
            uid = so.optInt("A5", 0);
            if (uid <= 0) {
                uid = so.optInt("uid", 0);
            }
        } else if (src instanceof Number) {
            uid = ((Number) src).intValue();
        } else if (src instanceof String) {
            uid = intOf((String) src);
        }
        if (uid <= 0) {
            uid = req.optInt("source_uid", 0);
        }
        if (uid <= 0) {
            uid = req.optInt("inherit_uid", 0);
        }
        if (uid <= 0) {
            uid = req.optInt("inlay_uid", 0);
        }
        if (uid <= 0) {
            uid = req.optInt("src", 0);
        }
        if (uid <= 0 && target != null) {
            uid = target.optInt("CD", 0);
        }
        // 客户端把这次挂接记在 logs["54"] / logs["27"] 里（M = 传承装备）。
        // 11.4 的协议日志实证：type=6 的请求里带着
        //   "logs":{"54":[{"M":{"A2":1311,"A5":1311,...},"T":{"A2":1316,"A5":503347}}]}
        // —— 说明 11.4 的传承挂接用的是 54，且源装备就在 logs 的 M 里。
        if (uid <= 0) {
            JSONObject logs = req.optJSONObject("logs");
            if (logs != null) {
                JSONArray arr = logs.optJSONArray(String.valueOf(HA_INLAY_ALT));
                if (arr == null) {
                    arr = logs.optJSONArray(String.valueOf(HA_INLAY));
                }
                if (arr != null && arr.length() > 0) {
                    JSONObject e = arr.optJSONObject(arr.length() - 1);
                    JSONObject m = e == null ? null : e.optJSONObject("M");
                    if (m != null) {
                        uid = m.optInt("A5", 0);
                        if (uid <= 0) {
                            uid = m.optInt("A2", 0);
                        }
                    }
                }
            }
        }
        // 也认 material[] 里第一件带实例 id 的装备（挂接时 material 就是传承装备）。
        if (uid <= 0) {
            JSONArray mats = req.optJSONArray("material");
            if (mats != null) {
                for (int i = 0; i < mats.length(); i++) {
                    JSONObject m = mats.optJSONObject(i);
                    if (m != null && m.optInt("A5", 0) > 0) {
                        uid = m.optInt("A5", 0);
                        break;
                    }
                }
            }
        }
        return uid;
    }

    private static int intOf(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 锻升的可见加成：把「传承槽里那件装备的属性 × 传承等级%」叠进本装备。
     * 槽里没有传承装备时按本装备当前属性自基准（fix191 的可见效果，保留）。
     * 幂等：调用前 {@link #applyNoblestStats} 已把四维按表重算回基础值。
     */
    private static void applyInheritBonus(JSONObject item, int ce) throws Exception {
        if (ce <= 0) {
            return;
        }
        JSONObject src = item.optJSONObject("CQ");
        if (src == null) {
            src = new JSONObject();
            String[] base = {"A7", "A8", "A9", "AA", "AC"};
            for (int bi = 0; bi < base.length; bi++) {
                if (item.has(base[bi])) {
                    src.put(base[bi], item.get(base[bi]));
                }
            }
            item.put("CQ", src);
        }
        double rate = Math.min(ce, 100) / 100.0;
        String[] attrKeys = {"A7", "A9", "AA", "AF"};
        for (int ai = 0; ai < attrKeys.length; ai++) {
            int sv = src.optInt(attrKeys[ai], 0);
            if (sv <= 0) {
                continue;
            }
            item.put(attrKeys[ai],
                    item.optInt(attrKeys[ai], 0) + (int) Math.round(sv * rate));
        }
    }

    /** 星级近似系数（官方公式未知，按每颗 ×1.05 叠乘）。 */
    private static final double HA_STAR_MULT = 1.05;

    /**
     * 按官方成长表重算至尊装备的四维：
     * <pre>属性 = 基础值 + a × 品阶^d + b × 品阶</pre>
     * 基础值取 noblest_equip 的 *_min，系数取 noblest_enhance 的 *_factor_a/b/d。
     * 星级再叠一个近似倍率（1.05^星）。
     * 表里查不到（不是至尊装备）就什么都不做。
     */
    private static void applyNoblestStats(JSONObject item, int quality, int star) {
        try {
            int dataid = item.optInt("A2", 0);
            if (dataid <= 0) {
                return;
            }
            JSONObject eqTab = table("noblest_equip");
            JSONObject eq = eqTab == null ? null : eqTab.optJSONObject(String.valueOf(dataid));
            if (eq == null) {
                return;
            }
            // 公式表要查 noblest_enhance_data_id（1316 -> 103200）；
            // enhance_data_id 是另一张表（103500），noblest_enhance 里没有，
            // 查它就会 return -> 升品/升星都不加属性（这就是"升星不加属性"的原因）。
            JSONObject enTab = table("noblest_enhance");
            int enId = eq.optInt("noblest_enhance_data_id", 0);
            JSONObject en = enTab == null ? null : enTab.optJSONObject(String.valueOf(enId));
            if (en == null) {
                en = enTab == null ? null
                        : enTab.optJSONObject(String.valueOf(eq.optInt("enhance_data_id", 0)));
            }
            if (en == null) {
                return;
            }
            double starMult = Math.pow(HA_STAR_MULT, Math.max(0, star));
            String[] out = {"A7", "A8", "A9", "AA"};
            String[] base = {"hp_min", "mp_min", "str_min", "def_min"};
            String[] prefix = {"hp", "mp", "str", "def"};
            for (int i = 0; i < out.length; i++) {
                double b0 = eq.optDouble(base[i], 0);
                if (b0 <= 0 && i == 2) {
                    continue;                       // 攻击为 0 的装备不用算
                }
                double fa = en.optDouble(prefix[i] + "_factor_a", 0);
                double fb = en.optDouble(prefix[i] + "_factor_b", 0);
                double fd = en.optDouble(prefix[i] + "_factor_d", 2);
                if (fd <= 0) {
                    fd = 2;
                }
                double val = b0 + fa * Math.pow(quality, fd) + fb * quality;
                val *= starMult;
                if (val > 0) {
                    item.put(out[i], (int) Math.round(val));
                }
            }
        } catch (Throwable t) {
            trace("applyNoblestStats failed " + t);
        }
    }

    /** 背包里按全局 id（A5）找装备；找不到再按 dataid 兜底。 */
    private static JSONObject findItem(JSONArray items, JSONObject target) {
        if (items == null || target == null) {
            return null;
        }
        int gid = target.optInt("A5", 0);
        int did = target.optInt("A2", 0);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && gid > 0 && it.optInt("A5", 0) == gid) {
                return it;
            }
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && did > 0 && it.optInt("A2", 0) == did && it.optInt("A4", 0) == 1) {
                return it;
            }
        }
        return null;
    }

    /** 可堆叠材料的背包条目（按 A2 找）。 */
    private static JSONObject findStack(JSONArray items, int dataId) {
        if (items == null || dataId <= 0) {
            return null;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.optInt("A2", 0) == dataId) {
                return it;
            }
        }
        return null;
    }

    private static void fillSave(JSONObject req, JSONObject r, String action) throws Exception {
        boolean download = action != null && action.startsWith("roledata");
        if (action != null && "savedata".equals(action)) {
            download = false;
        }

        if (!download) {
            // Checkpoint: keep whatever the client handed us. Merging (not
            // replacing) matters because the periodic upload only carries
            // roledata, and its bag has no "item" member at all.
            Object sd = req.opt("savedata");
            if (sd instanceof JSONObject) {
                storeSaveUpload((JSONObject) sd);
            }
            r.put("itemlimit", new JSONArray());
            return;
        }

        // Download. analyzeAndApplyDataToGame rejects the whole payload unless
        // every gate below passes, and the failure surfaces as the
        // "存档数据已损坏！" banner:
        //
        //   YJPlayer::isValidPoint(savedata)      -> isMember("point")
        //   YJPlayer::isValidData(player)         -> isMember("roleid")
        //   YJBagManager::isValidData(bag)        -> bagsize+maxglobalitemid+spiritcount
        //   YJLevelManager::isValidData(level)    -> isMember("psl") && psl.isArray()
        //   YJPetManager::isValidData(pet)        -> isArray()  (empty array FAILS)
        // An absent member is skipped (the call sites test `ne null` first), so a
        // blocked member must be omitted rather than sent empty.
        JSONObject sd = buildSavedataBlock();
        if (sd != null) {
            if (isV13(req)) {
                JSONObject rd = sd.optJSONObject("roledata");
                if (rd != null) {
                    String ver = req.optString("dataversion", "13.0.3");
                    rd.put("appversion", ver);
                    rd.put("saveversion", ver);
                }
            }
            if (new java.io.File("/sdcard/yj/dumpsave").exists()) {
                try {
                    java.io.FileOutputStream df = new java.io.FileOutputStream(
                            "/sdcard/yj/served_" + sCurRid + ".json");
                    df.write(sd.toString().getBytes("UTF-8"));
                    df.close();
                } catch (Throwable ignored) {
                }
            }
            r.put("savedata", sd);
            if (sd.has("point")) {
                r.put("point", sd.get("point"));
            }
            traceNoblestItems(sd, "serve");
            // Arm the worn-gear restore. Nothing in the client reads
            // savedata.equips back into the bag, so libmod re-wears these ids on
            // the render thread as soon as the restored items exist.
            if (!sV13) {
                armEquips(sd);
            }
        }
        // analyzeAndApplyDataToGame also pushes this to
        // YJSocketConnectToGameLobby::setLobbyAddress when present.
        JSONObject pk = new JSONObject();
        pk.put("host", "127.0.0.1");
        pk.put("port", PORT);
        r.put("pkservice", pk);
        // 至尊装备传承面板要的配置表（属性传承比例由它算，缺了就恒为 0%）
        putSlotStuntConfig(r);
        // ★ fix258：官方 roledata2 顶层 `extremeCnf`。至尊所有面板（升品/升星/
        //   锻升/聚炼/传承）的费用与比例都要读它；13.0 私服就是靠这一条修的「至尊」。
        r.put("extremeCnf", extremeCnf());
        r.put("itemlimit", new JSONArray());
    }

    /**
     * 13.x 的原生存档：只补 analyzeAndApplyDataToGame 的最低校验字段，
     * 不叠加任何 11.4 的仙剑/引导/问仙/宠物逻辑。
     */
    private static JSONObject vanillaSave(JSONObject store) throws Exception {
        JSONObject sd = new JSONObject(store.toString());
        if (!sd.has("point")) {
            sd.put("point", 0);
        }
        JSONObject bag = sd.optJSONObject("bag");
        if (bag == null) {
            bag = new JSONObject();
            sd.put("bag", bag);
        }
        if (!bag.has("bagsize")) {
            bag.put("bagsize", 72);
        }
        if (!bag.has("maxglobalitemid")) {
            bag.put("maxglobalitemid", 4399);
        }
        if (!bag.has("spiritcount")) {
            bag.put("spiritcount", 0);
        }
        if (!(bag.opt("item") instanceof JSONArray)) {
            bag.put("item", new JSONArray());
        }
        JSONObject lvl = sd.optJSONObject("level");
        if (lvl == null) {
            lvl = new JSONObject();
            sd.put("level", lvl);
        }
        if (!(lvl.opt("psl") instanceof JSONArray)) {
            lvl.put("psl", new JSONArray());
        }
        Object pet = sd.opt("pet");
        if (pet instanceof JSONArray) {
            JSONObject pb = new JSONObject();
            pb.put("bagsize", 8);
            pb.put("petinfo", pet);
            pb.put("maxglobalpetid", "4399");
            sd.put("pet", pb);
        } else if (!(pet instanceof JSONObject)) {
            JSONObject pb = new JSONObject();
            pb.put("bagsize", 8);
            pb.put("petinfo", new JSONArray());
            pb.put("maxglobalpetid", "4399");
            sd.put("pet", pb);
        }
        return sd;
    }

    /**
     * Keep profile.json in step with the character state the client uploads, so
     * the role card shows the real level instead of a hardcoded one.
     * Upload shape: {"roledata":{"player":{...,"roleid":3},"role":{"rolelv":2,...}},
     *                "bag":{...}, "level":{...}, "pet":[...]}
     */
    private static synchronized void syncProfileFromSave(JSONObject sd) {
        try {
            JSONObject p = readProfile();
            if (p == null) {
                p = new JSONObject();
                p.put("rid", 1);
            }
            JSONObject rd = sd.optJSONObject("roledata");
            if (rd != null) {
                JSONObject role = rd.optJSONObject("role");
                if (role != null) {
                    p.put("rolelv", role.optInt("rolelv", p.optInt("rolelv", 1)));
                    p.put("roleexp", role.optInt("roleexp", p.optInt("roleexp", 0)));
                }
                JSONObject player = rd.optJSONObject("player");
                if (player != null && player.has("roleid")) {
                    p.put("roleid", player.optInt("roleid", p.optInt("roleid", 1)));
                }
            }
            writeProfile(p);
        } catch (Throwable t) {
            trace("syncProfile failed " + t);
        }
    }

    /**
     * `base` block expected by YJSaveLoadManager::analyzeAndApplyDataToGame.
     *
     * The function does nothing but look this key up (five times, all confirmed
     * with a crash probe): a missing value runs FUN_0042af20(), a string value
     * raises "存档数据已损坏！", and an object has its host/port handed to
     * YJSocketConnectToGameLobby::setLobbyAddress.
     */
    /**
     * Build the savedata block the client expects: identity fields plus the
     * cached state, exactly the shape YJSaveLoadManager parses from disk.
     * Returns null when there is nothing stored yet.
     */
    /**
     * Build the "savedata" object for the download reply, shaped so that every
     * validator in analyzeAndApplyDataToGame passes. Returns null when nothing is
     * stored yet (the client then just reports success without touching anything).
     */
    /** 关卡槽位数：level.sdb 有 70 关，多给一些无妨。 */
    private static final int MAX_LEVEL_SLOT = 100;

    /** 让地图上所有关卡都显示为已通关（三星）。 */
    private static void unlockAllLevels(JSONObject lvl) {
        try {
            JSONArray psl = lvl.optJSONArray("psl");
            if (psl == null) {
                psl = new JSONArray();
                lvl.put("psl", psl);
            }
            java.util.HashSet<Integer> have = new java.util.HashSet<Integer>();
            for (int i = 0; i < psl.length(); i++) {
                JSONObject o = psl.optJSONObject(i);
                if (o != null) {
                    have.add(o.optInt("id", 0));
                }
            }
            for (int id = 1; id <= MAX_LEVEL_SLOT; id++) {
                if (have.contains(id)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("s", 5);
                psl.put(o);
                have.add(id);
            }
            // sublevel.sdb 里的大关子关（700xxx 等）也要标成已通关。
            // 虚空之境(100048) 下面三个秘境 700145 修罗 / 700146 大圣 / 700148 妖皇
            // 的 requirelv 是 799999（哨兵值 = 要秘令），不进 psl 就永远锁着，
            // 面板上就是「暂无活动」。
            try {
                JSONObject stab = table("sublevel");
                if (stab != null) {
                    java.util.Iterator<String> sit = stab.keys();
                    while (sit.hasNext()) {
                        JSONArray rows = stab.optJSONArray(sit.next());
                        if (rows == null) {
                            continue;
                        }
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject r = rows.optJSONObject(i);
                            if (r == null) {
                                continue;
                            }
                            int sid = r.optInt("id", 0);
                            if (sid <= 0 || !have.add(sid)) {
                                continue;
                            }
                            JSONObject o = new JSONObject();
                            o.put("id", sid);
                            o.put("s", 5);
                            psl.put(o);
                        }
                    }
                }
            } catch (Throwable t) {
                trace("unlock sublevels failed " + t);
            }
            // mlru 存的是「最近去过的关卡 id」，全塞进去让地图解锁判定通过。
            JSONArray mlru = lvl.optJSONArray("mlru");
            if (mlru == null) {
                mlru = new JSONArray();
                lvl.put("mlru", mlru);
            }
            java.util.HashSet<Integer> mhave = new java.util.HashSet<Integer>();
            for (int i = 0; i < mlru.length(); i++) {
                mhave.add(mlru.optInt(i, 0));
            }
            JSONObject ltab = table("level");
            if (ltab != null) {
                java.util.Iterator<String> it = ltab.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    try {
                        int lid = Integer.parseInt(k);
                        if (!mhave.contains(lid)) {
                            mlru.put(lid);
                        }
                        // 主关卡（100001 / 100079 这种六位 id）也要进 psl，
                        // 否则客户端的「通关 XXX」判定永远不过：
                        // 极北 NPC 紫微仙子的接任务条件就是
                        // 「通关冥界邪魔（100079）且战力 ≥ 30 万」。
                        if (!have.contains(lid)) {
                            JSONObject lo = new JSONObject();
                            lo.put("id", lid);
                            lo.put("s", 5);
                            psl.put(lo);
                            have.add(lid);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            trace("unlockAllLevels failed " + t);
        }
    }

    /**
     * 以前这里有个「没领邮件就把 229 从 petinfo 里摘掉」的闸门，已删除。
     *
     * <p>原因：闸门只作用在服务端**发出去的** savedata 上，而客户端拿到这份
     * 缺宠物的存档后，下一次自动上传会把「没有 229 的 petinfo」写回
     * savedata.json —— 宠物就这么真的没了。而登录走 mergeSave()，根本没经过
     * 这个闸门，于是同一只宠物「登录时在、进游戏后消失」，两条路径自相矛盾。
     *
     * <p>现在的约定：青龙妖圣一旦进了存档就一直在，领取邮件只负责补发一次
     * （grantPetToSave 本身按 dataid 去重，重复领取不会多出一只）。
     */

    /** 邮件领取时调用：把宠物写进存档（技能由 petJson 全量给）。 */
    private static synchronized void grantPetToSave(int dataid) {
        try {
            JSONObject store = readSaveObj();
            if (store == null) {
                store = new JSONObject();
            }
            JSONObject petBag = store.optJSONObject("pet");
            if (petBag == null) {
                petBag = new JSONObject();
                store.put("pet", petBag);
            }
            JSONArray info = petBag.optJSONArray("petinfo");
            if (info == null) {
                info = new JSONArray();
                petBag.put("petinfo", info);
            }
            if (petBag.optInt("bagsize", 0) < 8) {
                petBag.put("bagsize", 8);
            }
            for (int i = 0; i < info.length(); i++) {
                JSONObject o = info.optJSONObject(i);
                if (o != null && o.optInt("dataid", 0) == dataid) {
                    trace("grantPetToSave: already have " + dataid);
                    return;
                }
            }
            info.put(petJson(dataid));
            writeSave(store.toString());
            trace("grantPetToSave: added " + dataid + " pets=" + info.length());
        } catch (Throwable t) {
            trace("grantPetToSave failed " + t);
        }
    }

    /** petinfo 里没有 dataid 就补一只（技能由 petJson 全量给）。 */
    private static void ensureGiftPet(JSONArray petinfo, int dataid) {
        try {
            if (petinfo == null) {
                return;
            }
            for (int i = 0; i < petinfo.length(); i++) {
                JSONObject o = petinfo.optJSONObject(i);
                if (o != null && o.optInt("dataid", 0) == dataid) {
                    return;
                }
            }
            petinfo.put(petJson(dataid));
            trace("gift pet added: " + dataid);
        } catch (Throwable t) {
            trace("ensureGiftPet failed " + t);
        }
    }

    /**
     * 把所有新手引导 id 标成"已完成"。
     *
     * 实测 16.9 真实存档里是 roledata.player.completeguide = ["900021","900000",...]，
     * 引导 id 都在 9000xx 区间。这里把 900000..900999 全部塞进去，
     * 客户端查表时每个引导都命中"已完成"，新手教学就不会再触发。
     */
    private static void fillCompleteGuide(JSONObject sd) {
        try {
            JSONObject rd = sd.optJSONObject("roledata");
            if (rd == null) {
                rd = new JSONObject();
                sd.put("roledata", rd);
            }
            JSONObject pl = rd.optJSONObject("player");
            if (pl == null) {
                pl = new JSONObject();
                rd.put("player", pl);
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int i = 900000; i <= 909999; i++) {
                arr.put(String.valueOf(i));
            }
            pl.put("completeguide", arr);
            trace("skipguide: completeguide filled (" + arr.length() + ")");
        } catch (Throwable t) {
            trace("skipguide failed " + t);
        }
    }

    /**
     * 沙盒满配：背包格子 + 角色等级。
     *
     * - bag.bagsize：客户端按这个值决定背包显示多少格，"扩充"按钮点确定后客户端
     *   自己也会查这个上限；直接给 120 就不需要再点扩充了。
     * - roledata.role.rolelv：主界面右上那一排系统按钮（福缘/本命神/灵物/真灵…）
     *   的显隐是客户端按 module_open_guide.sdb 的等级门槛判的，1 级只开放一部分。
     *   拉到 80 级把大部分系统放出来。
     */
    private static void boostSave(JSONObject sd) {
        try {
            JSONObject bag = sd.optJSONObject("bag");
            if (bag != null) {
                int cur = bag.optInt("bagsize", 0);
                if (cur < 120) {
                    bag.put("bagsize", 120);
                }
            }
            JSONObject rd = sd.optJSONObject("roledata");
            JSONObject role = rd == null ? null : rd.optJSONObject("role");
            if (role != null) {
                // **不要再改 rolelv**：login 下发的 rolelist 里是存档里的真实等级，
                // 这里偷偷把存档等级拉到 80 会让客户端发现「角色列表等级 != 存档等级」，
                // 直接弹「存档数据已损坏!」—— 多角色进不去的真正原因就是这个。
                // 主号本来就是 90 级，不需要拉；新角色必须保持 1 级。
                int lv = role.optInt("rolelv", 1);
                if (lv <= 0) {
                    role.put("rolelv", 1);
                }
            }
        } catch (Throwable t) {
            trace("boostSave failed " + t);
        }
    }

    /**
     * 真灵背包（兽栏）。16.9 真实应答里就是 {@code {bagsize, maxspiritid, list[]}}，
     * list 是兽的数组。这里塞一只「冰霜末世」(9005)。
     *
     * <p>兽不能放 pet.petinfo：那是普通宠物栏，塞进去客户端 GLThread 直接 SIGSEGV。
     */
    /** 本命神：发 1w 唤神石、保证拥有 509200，并处理「吞噬/升阶」请求。 */
    private static void fillFateGod(String action, JSONObject req, JSONObject r) {
        try {
            JSONObject store = readSaveObj();
            if (store == null) {
                store = new JSONObject();
                store.put("point", 0);
            }
            JSONObject rd = store.optJSONObject("roledata");
            if (rd == null) {
                rd = new JSONObject();
                store.put("roledata", rd);
            }
            JSONObject role = rd.optJSONObject("role");
            if (role == null) {
                role = new JSONObject();
                rd.put("role", role);
            }
            JSONObject fg = role.optJSONObject("fategod");
            if (fg == null) {
                fg = new JSONObject();
                role.put("fategod", fg);
            }
            if (fg.optInt("id", 0) <= 0) {
                fg.put("id", 509200);
                if (fg.optJSONArray("skills") == null) {
                    JSONArray sk = new JSONArray();
                    sk.put(1220).put(1120).put(1230);
                    fg.put("skills", sk);
                }
            }
            int num = req.optInt("num", req.optInt("count", req.optInt("amount", 1)));
            if (num <= 0) {
                num = 1;
            }
            int used = 0;
            boolean raiseDegree = action.contains("raisedegree")
                    || action.contains("raise_stage") || action.contains("raisestage")
                    || action.contains("degree") || action.contains("stage");
            boolean levelup = action.contains("levelup") || action.equals("upfategod")
                    || action.contains("raise") || action.contains("up")
                    || action.contains("swallow") || action.contains("devour")
                    || action.contains("eat");
            if (raiseDegree) {
                // 晋阶：消耗命火 503371（页面显示 11 个），degree+1，上限 89。
                // 16.9 的字段就是 fategod.degree：degree/10=阶，degree%10=阶内级；
                // 客户端按 fategod_raise_degree.sdb 的 grade 1..90 取属性加成。
                int need = req.optInt("num", req.optInt("count", 11));
                if (need <= 0) {
                    need = 11;
                }
                int have = countBagItem(store, 503371);
                if (have >= need) {
                    used = takeBagItem(store, 503371, need);
                    int cur = Math.min(89, Math.max(0, fg.optInt("degree", 0)) + 1);
                    fg.put("degree", cur);
                    trace("fategod 晋阶 -> degree=" + cur + " 消耗命火 " + used);
                } else {
                    // 材料不够：不扣材料、不改等级，回一句提示。
                    trace("fategod 晋阶材料不足 have=" + have + " need=" + need);
                    fg.put("degree", Math.min(89, Math.max(0, fg.optInt("degree", 0))));
                    r.put("state", 1);
                    r.put("msg", "命火不足，需要 " + need + " 个");
                }
            } else if (levelup) {
                // 5.5 handleFateGodLevelUp 同款公式：exp 反推等级，type=1 升 1 级，
                // 否则一次升 10 级（上限 90，exp 上限 8099）。
                used = takeBagItem(store, 503147, 1);
                int exp = fg.optInt("exp", 0);
                int lv = Math.min(90, Math.max(1, (int) Math.sqrt(Math.max(0, exp + 1))));
                if (lv < 90) {
                    int type = req.optInt("type", 0);
                    if (type == 1) {
                        exp = (lv + 1) * (lv + 1) - 1;
                    } else {
                        int l2 = Math.min(90, lv + 10);
                        exp = Math.min(8099, l2 * l2 - 1);
                    }
                    fg.put("exp", exp);
                }
            }
            fg.put("state", 0);
            fg.put("msg", "");
            writeSave(store.toString());
            r.put("fategod", fg);
            r.put("fate_god", fg);
            r.put("exp", fg.optInt("exp", 0));
            r.put("degree", fg.optInt("degree", 0));
            r.put("stage", fg.optInt("stage", 0));
            r.put("goods", new JSONArray());
            r.put("cost", used);
            r.put("state", 0);
            r.put("msg", "");
            trace("fategod " + action + " num=" + num + " used=" + used
                    + " exp=" + fg.optInt("exp", 0));
        } catch (Throwable t) {
            trace("fillFateGod failed " + t);
        }
    }

    private static int countBagItem(JSONObject sd, int id) {
        int total = 0;
        try {
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items == null) {
                return 0;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null && it.optInt("A2", 0) == id) {
                    total += Math.max(0, it.optInt("A4", 0));
                }
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

    private static int takeBagItem(JSONObject sd, int id, int want) {
        try {
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items == null || want <= 0) {
                return 0;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || it.optInt("A2", 0) != id) {
                    continue;
                }
                int have = it.optInt("A4", 0);
                int use = Math.min(have, want);
                if (use <= 0) {
                    return 0;
                }
                int left = have - use;
                if (left <= 0) {
                    items.remove(i);
                } else {
                    it.put("A4", left);
                }
                return use;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 本命神升级后 exp/degree 越界会让客户端进关卡时崩，登录/上传时统一夹紧。 */
    private static void sanitizeFateGod(JSONObject sd) {
        try {
            JSONObject rd = sd.optJSONObject("roledata");
            JSONObject role = rd == null ? null : rd.optJSONObject("role");
            if (role == null) {
                return;
            }
            JSONObject fg = role.optJSONObject("fategod");
            if (fg == null) {
                return;
            }
            if (fg.optInt("id", 0) <= 0) {
                fg.put("id", 509200);
            }
            int exp = fg.optInt("exp", 0);
            fg.put("exp", Math.max(0, Math.min(8099, exp)));
            // degree：degree/10=阶、degree%10=阶内级，属性按 fategod_raise_degree
            // 的 grade 1..90 取；只要夹在 0..89 就不会越界。
            fg.put("degree", Math.max(0, Math.min(89, fg.optInt("degree", 0))));
            JSONArray sk = fg.optJSONArray("skills");
            if (sk == null || sk.length() == 0) {
                sk = new JSONArray();
                sk.put(1220).put(1120).put(1230);
                fg.put("skills", sk);
            }
        } catch (Throwable t) {
            trace("sanitizeFateGod failed " + t);
        }
    }

    private static void grantFateGodItems(JSONObject sd) {
        try {
            JSONObject bag = sd.optJSONObject("bag");
            if (bag == null) {
                bag = new JSONObject();
                sd.put("bag", bag);
            }
            JSONArray items = bag.optJSONArray("item");
            if (items == null) {
                items = new JSONArray();
                bag.put("item", items);
            }
            int[] ids = {503147, 503371};
            int[] counts = {10000, 1000};
            for (int k = 0; k < ids.length; k++) {
                boolean found = false;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null && it.optInt("A2", 0) == ids[k]) {
                        it.put("A4", counts[k]);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    items.put(itemJson(ids[k], counts[k]));
                }
            }
            JSONObject rd = sd.optJSONObject("roledata");
            JSONObject role = rd == null ? null : rd.optJSONObject("role");
            if (role != null) {
                JSONObject fg = role.optJSONObject("fategod");
                if (fg == null) {
                    fg = new JSONObject();
                    role.put("fategod", fg);
                }
                if (fg.optInt("id", 0) <= 0) {
                    fg.put("id", 509200);
                    if (fg.optJSONArray("skills") == null) {
                        JSONArray sk = new JSONArray();
                        sk.put(1220).put(1120).put(1230);
                        fg.put("skills", sk);
                    }
                }
            }
        } catch (Throwable t) {
            trace("grantFateGodItems failed " + t);
        }
    }

    /** 真灵协议：open_beast_bag / beast_suminfo / beast_upgrade / beast_set_tamestate。 */
    private static void fillBeast(String action, JSONObject req, JSONObject r) {
        try {
            JSONObject bag = trueSpiritBag();
            JSONArray list = bag.optJSONArray("truespiritinfo");
            if (list == null) {
                list = bag.optJSONArray("list");
            }
            JSONObject b = list == null ? null : list.optJSONObject(0);
            if (b != null) {
                if ("beast_upgrade".equals(action)) {
                    int lv = b.optInt("level", 1);
                    if (lv < 100) {
                        b.put("level", lv + 1);
                        b.put("basehp", (int) (b.optInt("basehp", 20000) * 1.05));
                        b.put("basemp", (int) (b.optInt("basemp", 10000) * 1.05));
                        b.put("basestr", (int) (b.optInt("basestr", 6000) * 1.05));
                        b.put("basedef", (int) (b.optInt("basedef", 1000) * 1.05));
                    }
                } else if ("beast_set_tamestate".equals(action)) {
                    b.put("tamestate", req.optInt("tamestate", 0));
                }
            }
            try {
                java.io.FileOutputStream fo = new java.io.FileOutputStream(
                        "/sdcard/yj/true_spirit.json");
                fo.write(bag.toString().getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) {
            }
            r.put("true_spirit_bag", bag);
            r.put("truespiritinfo", list);
            r.put("truespiritlist", list);
            r.put("beastinfo", list);
            r.put("bagsize", bag.optInt("bagsize", 8));
            r.put("maxspiritid", bag.optInt("maxspiritid", 439999));
            r.put("state", 0);
            r.put("msg", "");
        } catch (Throwable t) {
            trace("fillBeast failed " + t);
        }
    }

    /** 悬浮真灵面板的「培养」「出战」动作。 */
    static String trueSpiritAction(String action) {
        try {
            JSONObject store = readSaveObj();
            if (store == null) {
                store = new JSONObject();
                store.put("point", 0);
            }
            JSONObject bag = store.optJSONObject("true_spirit_bag");
            JSONArray list = bag == null ? null : bag.optJSONArray("truespiritinfo");
            if (list == null && bag != null) {
                list = bag.optJSONArray("list");
            }
            if (list == null || list.length() == 0) {
                // 存档里的 true_spirit_bag 可能是空的（putTrueSpiritInPetBag 清过）；
                // 优先用 /sdcard/yj/true_spirit.json / 默认的 9005。
                JSONObject ov = trueSpiritBag();
                JSONArray ol = ov.optJSONArray("truespiritinfo");
                if (ol == null) {
                    ol = ov.optJSONArray("list");
                }
                if (ol != null && ol.length() > 0) {
                    bag = ov;
                    list = ol;
                }
            }
            if (bag == null) {
                bag = trueSpiritBag();
            }
            if (list == null) {
                list = bag.optJSONArray("truespiritinfo");
            }
            if (list == null || list.length() == 0) {
                return "尚未拥有真灵。";
            }
            JSONObject b = list.optJSONObject(0);
            if (b == null) {
                return "真灵数据为空。";
            }
            if ("train".equals(action)) {
                int lv = b.optInt("level", 1);
                if (lv < 99) {
                    lv++;
                    b.put("level", String.valueOf(lv));
                    b.put("basehp", String.valueOf((int) (b.optInt("basehp", 25000) * 1.05)));
                    b.put("basemp", String.valueOf((int) (b.optInt("basemp", 12000) * 1.05)));
                    b.put("basestr", String.valueOf((int) (b.optInt("basestr", 4000) * 1.05)));
                    b.put("basedef", String.valueOf((int) (b.optInt("basedef", 2980) * 1.05)));
                }
            } else if ("fight".equals(action)) {
                b.put("tamestate", "2");
            } else if ("rest".equals(action)) {
                b.put("tamestate", "4");
            }
            store.put("true_spirit_bag", bag);
            writeSave(store.toString());
            // 同时写设备覆盖文件，trueSpiritBag() 下次优先读它，
            // 否则客户端上传的默认 bag 会把培养结果盖回 1 级。
            try {
                java.io.FileOutputStream fo = new java.io.FileOutputStream(
                        "/sdcard/yj/true_spirit.json");
                fo.write(bag.toString().getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) {
            }
            trace("trueSpiritAction " + action + " -> " + trueSpiritSummary());
        } catch (Throwable t) {
            return "操作失败: " + t;
        }
        return trueSpiritSummary();
    }

    /** 把 509334/509336 的至尊属性并回已装备的 1316/1513，避免人物穿着基础版。 */
    private static void mergeNoblestIntoBase(JSONObject sd) {
        try {
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items == null) {
                return;
            }
            int[][] pairs = {{1316, 509334}, {1513, 509336}};
            for (int[] pair : pairs) {
                JSONObject base = null;
                JSONObject up = null;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    if (it.optInt("A5", 0) == pair[0]) {
                        base = it;
                    } else if (it.optInt("A5", 0) == pair[1]) {
                        up = it;
                    }
                }
                if (base == null || up == null) {
                    continue;
                }
                for (String k : new String[]{"A7", "A8", "A9", "AA", "AF", "CF", "CC",
                        "CH", "CI", "CJ", "CK", "CU", "CE", "CV"}) {
                    if (up.has(k)) {
                        base.put(k, up.get(k));
                    }
                }
                for (int i = items.length() - 1; i >= 0; i--) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null && it.optInt("A5", 0) == pair[1]) {
                        items.remove(i);
                    }
                }
                trace("mergeNoblest " + pair[1] + " -> " + pair[0]);
            }
        } catch (Throwable t) {
            trace("mergeNoblest failed " + t);
        }
    }

    /** 清掉 509334 上调试注入的非法字段（CW..DZ/CQ），避免客户端崩溃。 */
    private static void sanitizeTestItem(JSONObject sd) {
        try {
            if (new java.io.File("/sdcard/yj/no_sanitize").exists()) {
                return;
            }
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items == null) {
                return;
            }
            String[] bad = {"CW", "CX", "CY", "CZ", "DA", "DB", "DC", "DD", "DE", "DF",
                    "DG", "DH", "DI", "DJ", "DK", "DL", "DM", "DN", "DO", "DP",
                    "DQ", "DR", "DS", "DT", "DU", "DV", "DW", "DX", "DY", "DZ", "CQ"};
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || it.optInt("A5", 0) != 509334) {
                    continue;
                }
                for (String k : bad) {
                    it.remove(k);
                }
                it.put("CU", 0);
                it.put("CE", 0);
                it.put("CV", 100);
                trace("sanitizeTestItem 509334 cleaned");
            }
        } catch (Throwable t) {
            trace("sanitizeTestItem failed " + t);
        }
    }

    /** 冰霜末世：用户指定资质/基础属性。 */
    private static JSONObject trueSpiritPetEntry() {
        JSONObject b = new JSONObject();
        try {
            b.put("basedef", 1000);
            b.put("basehp", 20000);
            b.put("basemp", 10000);
            b.put("basestr", 6000);
            b.put("beast_judgement", new JSONArray().put(0));
            b.put("blood", 0);
            b.put("bonelevel", 4);
            b.put("can_reset_prop", 1);
            b.put("can_reset_skill", 1);
            b.put("can_super_reborn", 1);
            b.put("comprehension", 5);
            b.put("dataid", 9005);
            b.put("defimprove", 1600);
            b.put("ferity", 1);
            b.put("guide_id", 26);
            b.put("hpimprove", 4000);
            b.put("is_beast", 1);
            b.put("iselite", 0);
            b.put("isspecialchange", 0);
            b.put("level", 60);
            b.put("lifetime", 0);
            b.put("mixlevel", 0);
            b.put("mpimprove", 3000);
            b.put("petequips", new JSONArray());
            b.put("petexp", 0);
            b.put("petid", 4399);
            b.put("quality", 4);
            b.put("spiritid", 9005);
            b.put("stage", 4);
            b.put("type", 17);
            JSONArray sk = new JSONArray();
            sk.put(9005000).put(9005010).put(9005020).put(9005030);
            b.put("skill", sk);
            b.put("spiritpower", 4);
            b.put("strimprove", 3500);
            b.put("tamestate", 0);
            JSONArray tr = new JSONArray();
            tr.put(1).put(1);
            for (int i = 2; i < 9; i++) {
                tr.put(0);
            }
            b.put("triones", tr);
        } catch (Throwable ignored) {
        }
        return b;
    }

    /**
     * 真灵放宠物背包：去掉 true_spirit_bag，把 9005 用指定的
     * 20000/6000/10000/1000 与资质 4000/3500/1600/3000 写进 pet.petinfo。
     */
    /**
     * 清掉以前塞进宠物背包的 9005 真灵。9005 的 is_beast/shape 会让
     * 11.4 的角色选择预览在 GLThread 里崩，登录/上传时统一移除。
     */
    private static void removeTrueSpiritFromPetBag(JSONObject sd) {
        try {
            Object petObj = sd.opt("pet");
            if (petObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) petObj;
                for (int i = arr.length() - 1; i >= 0; i--) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null && (o.optInt("dataid", 0) == 9005
                            || o.optInt("spiritid", 0) == 9005
                            || o.optInt("basepetid", 0) == 9005)) {
                        arr.remove(i);
                    }
                }
            } else if (petObj instanceof JSONObject) {
                JSONArray arr = ((JSONObject) petObj).optJSONArray("petinfo");
                if (arr != null) {
                    for (int i = arr.length() - 1; i >= 0; i--) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null && (o.optInt("dataid", 0) == 9005
                                || o.optInt("spiritid", 0) == 9005
                                || o.optInt("basepetid", 0) == 9005)) {
                            arr.remove(i);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            trace("removeTrueSpiritFromPetBag failed " + t);
        }
    }

    private static void putTrueSpiritInPetBag(JSONObject sd) {
        try {
            boolean hasSpirit = false;
            JSONObject tsb = sd.optJSONObject("true_spirit_bag");
            JSONArray tsList = tsb == null ? null : tsb.optJSONArray("truespiritinfo");
            hasSpirit = tsList != null && tsList.length() > 0;
            if (!hasSpirit) {
                // 存档里 true_spirit_bag 被清空时，仍然用默认/设备覆盖的 9005
                // 往宠物背包里放一只，这样宠物页能看到并操作真灵。
                JSONObject def = trueSpiritBag();
                JSONArray dl = def.optJSONArray("truespiritinfo");
                if (dl == null) {
                    dl = def.optJSONArray("list");
                }
                if (dl != null && dl.length() > 0) {
                    tsList = dl;
                    tsb = def;
                    hasSpirit = true;
                    sd.put("true_spirit_bag", def);
                }
            }
            if (new java.io.File("/sdcard/yj/clear_ts").exists()) {
                hasSpirit = false;
            }
            Object petObj = sd.opt("pet");
            JSONArray petArr = null;
            JSONArray petInfo = null;
            if (petObj instanceof JSONArray) {
                petArr = (JSONArray) petObj;
                for (int i = petArr.length() - 1; i >= 0; i--) {
                    JSONObject o = petArr.optJSONObject(i);
                    if (o != null && (o.optInt("spiritid", 0) == 9005
                            || o.optInt("basepetid", 0) == 9005
                            || o.optInt("dataid", 0) == 299)) {
                        petArr.remove(i);
                    }
                }
                if (hasSpirit) {
                    petArr.put(trueSpiritPetEntry());
                }
            } else if (petObj instanceof JSONObject) {
                JSONObject pet = (JSONObject) petObj;
                petInfo = pet.optJSONArray("petinfo");
                if (petInfo == null) {
                    petInfo = new JSONArray();
                    pet.put("petinfo", petInfo);
                }
                for (int i = petInfo.length() - 1; i >= 0; i--) {
                        JSONObject o = petInfo.optJSONObject(i);
                        if (o != null && (o.optInt("spiritid", 0) == 9005
                                || o.optInt("basepetid", 0) == 9005
                                || o.optInt("dataid", 0) == 299)) {
                            petInfo.remove(i);
                        }
                    }
                    if (hasSpirit) {
                        petInfo.put(trueSpiritPetEntry());
                    }
            }
            if (petObj == null && hasSpirit) {
                JSONArray arr = new JSONArray();
                arr.put(trueSpiritPetEntry());
                sd.put("pet", arr);
            }
            if (new java.io.File("/sdcard/yj/clear_ts").exists()) {
                JSONObject empty = new JSONObject();
                empty.put("bagsize", 8);
                empty.put("truespiritinfo", new JSONArray());
                sd.put("true_spirit_bag", empty);
                trace("clear_ts: true spirit removed");
                return;
            }
            if (!hasSpirit) {
                JSONObject empty = new JSONObject();
                empty.put("bagsize", 8);
                empty.put("truespiritinfo", new JSONArray());
                sd.put("true_spirit_bag", empty);
            }
        } catch (Throwable t) {
            trace("putTrueSpiritInPetBag failed " + t);
        }
    }

    /** 悬浮「真灵」按钮弹窗用的文本。 */
    static String trueSpiritSummary() {
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject bag = trueSpiritBag();
            JSONArray list = bag.optJSONArray("truespiritinfo");
            if (list == null) {
                list = bag.optJSONArray("list");
            }
            if (list == null || list.length() == 0) {
                return "尚未拥有真灵。";
            }
            for (int i = 0; i < list.length() && i < 4; i++) {
                JSONObject b = list.optJSONObject(i);
                if (b == null) {
                    continue;
                }
                int dataid = b.optInt("spiritid", b.optInt("dataid", 0));
                String name = "冰霜末世";
                try {
                    JSONObject row = table("pet") == null ? null
                            : table("pet").optJSONObject(String.valueOf(dataid));
                    if (row != null && row.optString("name", "").length() > 0) {
                        name = row.optString("name");
                    }
                } catch (Throwable ignored) {
                }
                sb.append("真灵·").append(name).append("   S级\n");
                sb.append("状态 ").append(b.optInt("tamestate", 2) == 2 ? "已出战" : "待命")
                        .append("\n");
                sb.append("等级 ").append(b.optInt("level", 1))
                        .append("    灵力 ").append(b.optInt("spiritpower", 0))
                        .append("    悟性 ").append(b.optInt("comprehension", 0))
                        .append("    根骨 ").append(b.optInt("bonelevel", 0)).append("\n");
                sb.append("气血 ").append(b.optInt("opbasehp", b.optInt("basehp", 0)))
                        .append("    魔法 ").append(b.optInt("opbasemp", b.optInt("basemp", 0)))
                        .append("    攻击 ").append(b.optInt("opbasestr", b.optInt("basestr", 0)))
                        .append("    防御 ").append(b.optInt("opbasedef", b.optInt("basedef", 0)))
                        .append("\n");
                JSONArray sk = b.optJSONArray("skill");
                sb.append("技能 ");
                if (sk != null) {
                    for (int k = 0; k < sk.length(); k++) {
                        if (k > 0) {
                            sb.append(" / ");
                        }
                        sb.append(sk.opt(k));
                    }
                }
                sb.append("\n");
                if (i + 1 < list.length()) {
                    sb.append("\n");
                }
            }
        } catch (Throwable t) {
            return "真灵数据读取失败: " + t;
        }
        return sb.toString();
    }

    /** 11.4 里所有有资源的真灵（`pet.sdb` 9000~9015 + `ai_pet_90xx.sdb` 都在 APK 里）。 */
    private static final int[] TRUE_SPIRIT_IDS = {
            9000, 9001, 9002, 9003, 9004, 9005, 9006, 9007, 9008, 9011, 9012, 9015,
    };

    /**
     * 一只真灵的存档条目（**官方 `true_spirits.pyc` 的 ts_make 形状**）。
     *
     * <p>以前这里发的是**宠物形状**（`dataid`/`petid`/`basehp`/`petexp`…），
     * 客户端就按宠物去建对象、画 `pet_avatar_9005.png`，那份图集只在打开宠物面板时
     * 才加载 → 打完一关纹理 purge 之后第二次进图取帧拿到 NULL →
     * `CCSprite::setDisplayFrame(NULL)` 解引用 0x128，SIGSEGV（fix254 就是为这个把
     * 真灵整个关掉的）。
     *
     * <p>11.4 客户端真正的真灵反序列化键表（libgame.so 0x1c372b0~0x1c373c0 连续字符串）：
     * <pre>
     *   iselite basepetid opbasehp opbasemp opbasestr opbasedef
     *   hpimprove mpimprove strimprove defimprove comprehension spiritpower bonelevel
     *   tamestate triones petequips passiveskill talentskill mastername
     * </pre>
     * 再加上 `spiritid` / `globalid` / `level` / `exp` / `skill` / `rare_blood_id`。
     * 注意 11.4 **没有** `sageskillposition`（那是 13.0 才有的键），多给会被忽略。
     */
    private static JSONObject trueSpiritEntry(int spiritid, int globalid) {
        JSONObject e = new JSONObject();
        try {
            JSONObject row = table("pet") == null ? null
                    : table("pet").optJSONObject(String.valueOf(spiritid));
            int hp = row != null ? row.optInt("hp_min", 0) : 0;
            int mp = row != null ? row.optInt("mp_min", 0) : 0;
            int str = row != null ? row.optInt("str_min", 0) : 0;
            int def = row != null ? row.optInt("def_min", 0) : 0;
            int hpI = row != null ? (row.optInt("hp_improve_min", 0)
                    + row.optInt("hp_improve_max", 0)) / 2 : 0;
            int mpI = row != null ? (row.optInt("mp_improve_min", 0)
                    + row.optInt("mp_improve_max", 0)) / 2 : 0;
            int strI = row != null ? (row.optInt("str_improve_min", 0)
                    + row.optInt("str_improve_max", 0)) / 2 : 0;
            int defI = row != null ? (row.optInt("def_improve_min", 0)
                    + row.optInt("def_improve_max", 0)) / 2 : 0;
            int comp = row != null ? row.optInt("comprehension_max", 0) : 0;
            int bone = row != null ? row.optInt("bone_level_max", 0) : 0;
            int sp = row != null ? row.optInt("spirit_power_max", 0) : 0;

            e.put("spiritid", spiritid);
            e.put("globalid", globalid);
            e.put("basepetid", 0);
            e.put("level", 1);
            e.put("exp", 0);
            e.put("iselite", 0);
            e.put("opbasehp", hp);
            e.put("opbasemp", mp);
            e.put("opbasestr", str);
            e.put("opbasedef", def);
            // 资质：表里的区间取中值（官方是区间内随机）
            e.put("hpimprove", hpI);
            e.put("mpimprove", mpI);
            e.put("strimprove", strI);
            e.put("defimprove", defI);
            e.put("comprehension", comp);
            e.put("spiritpower", sp);
            e.put("bonelevel", bone);
            e.put("tamestate", 2);
            JSONArray tr = new JSONArray();
            tr.put(1).put(1);
            for (int i = 2; i < 9; i++) {
                tr.put(0);
            }
            e.put("triones", tr);
            e.put("petequips", new JSONArray());
            JSONArray sk = new JSONArray();
            JSONArray born = row == null ? null : row.optJSONArray("born_skill");
            if (born != null) {
                for (int i = 0; i < born.length(); i++) {
                    sk.put(born.optInt(i, 0));
                }
            }
            e.put("skill", sk);
            e.put("passiveskill", new JSONArray());
            e.put("talentskill", new JSONArray());
            e.put("mastername", "");
            e.put("rare_blood_id", 0);
        } catch (Throwable t) {
            trace("trueSpiritEntry failed " + t);
        }
        return e;
    }

    /** 真灵关闭时，把存档里残留的 true_spirit_bag / truespiritinfo 清成空容器。 */
    private static void stripTrueSpirit(JSONObject sd) {
        if (sd == null || new java.io.File("/sdcard/yj/truespirit_on").exists()) {
            return;
        }
        try {
            JSONObject empty = new JSONObject();
            empty.put("bagsize", 8);
            empty.put("maxspiritid", 439999);
            empty.put("list", new JSONArray());
            empty.put("truespiritinfo", new JSONArray());
            sd.put("true_spirit_bag", empty);
            sd.put("truespiritinfo", new JSONArray());
            sd.put("truespiritlist", new JSONArray());
        } catch (Throwable t) {
            trace("stripTrueSpirit failed " + t);
        }
    }

    private static JSONArray trueSpiritList() {
        JSONArray list = new JSONArray();
        boolean all = new java.io.File("/sdcard/yj/truespirit_all").exists();
        try {
            if (all) {
                int gid = 4300;
                for (int i = 0; i < TRUE_SPIRIT_IDS.length; i++) {
                    list.put(trueSpiritEntry(TRUE_SPIRIT_IDS[i], gid++));
                }
            } else {
                list.put(trueSpiritEntry(9005, 4399));
            }
        } catch (Throwable t) {
            trace("trueSpiritList failed " + t);
        }
        return list;
    }

    private static JSONObject trueSpiritBag() {
        // fix260：真灵**默认关闭**（fix259 按官方形状下发后仍然闪退 —— 说明 11.4 这个
        // 版本的真灵渲染路径本身就不安全，不是字段形状能救的）。
        // 所以回到 fix254 的安全状态：容器照发（形状合法、list 为空），不下发任何真灵。
        //   · 想再试一次：touch /sdcard/yj/truespirit_on（再 touch truespirit_all 拿全部 12 只）
        //   · 已经崩过想立刻恢复：touch /sdcard/yj/truespirit_off（fix259 里也有这个开关）
        // 代码保留上面那套官方形状的构造器，随时可以再开，不必重打包。
        if (!new java.io.File("/sdcard/yj/truespirit_on").exists()) {
            JSONObject none = new JSONObject();
            try {
                none.put("bagsize", 8);
                none.put("truespiritinfo", new JSONArray());
                none.put("list", new JSONArray());
                none.put("maxspiritid", 439999);
            } catch (Throwable ignored) {
            }
            return none;
        }
        // 设备上可覆盖：/sdcard/yj/true_spirit.json 直接放整个 bag 对象。
        // 这样调字段不用重打 700MB 包，改文件重启游戏即可。
        try {
            java.io.File f = new java.io.File("/sdcard/yj/true_spirit.json");
            if (f.exists()) {
                byte[] raw = readAll(new java.io.FileInputStream(f));
                JSONObject o = new JSONObject(new String(raw, "UTF-8"));
                JSONArray ol = o.optJSONArray("truespiritinfo");
                if (ol == null) {
                    ol = o.optJSONArray("list");
                }
                if (ol != null && ol.length() > 0) {
                    trace("true_spirit_bag from /sdcard/yj/true_spirit.json");
                    return o;
                }
                trace("true_spirit override empty, use default");
            }
        } catch (Throwable t) {
            trace("true_spirit override failed " + t);
        }
        JSONObject bag = new JSONObject();
        try {
            JSONArray list = trueSpiritList();
            bag.put("bagsize", 8);
            bag.put("maxspiritid", 439999);
            bag.put("list", list);
            // 11.4 的内层键是 truespiritinfo（和 petinfo/roleinfo 同一张 key 表）；
            // 16.9/13.0 用 list。两个都给，客户端读哪个都能拿到。
            bag.put("truespiritinfo", list);
            trace("true_spirit_bag: spiritid list=" + list.length()
                    + (new java.io.File("/sdcard/yj/truespirit_all").exists() ? " (all)" : ""));
        } catch (Throwable ignored) {
        }
        return bag;
    }

    private static JSONObject sBlocks;

    /**
     * 把 16.9 官服 roledata2 应答里那 100 多个配置块原样下发。
     *
     * <p>这些块是"服务器下发"的部分：活动列表、限时关卡开关(timelimitlevel)、
     * 至尊/真灵相关的配置、图鉴地址等。11.4 的客户端读到不认识的键会忽略，
     * 但认识的键会改变主界面显示（这正是要验证的）。
     *
     * <p>故意省掉 {@code update_list}：那是 52 个 CDN 资源包，客户端会去下载。
     */
    private static void applyServerBlocks(JSONObject r) {
        // 极北（Thule）任务链状态：必须放在任何 return 之前，否则没有
        // blocks.json 的机器上这块会被跳过。
        //   echo 2 > /sdcard/yj/thule_state   → 重启游戏生效，不用重打包
        try {
            int st = 2;
            String ov = null;
            java.io.File tf = new java.io.File("/sdcard/yj/thule_state");
            if (tf.exists()) {
                byte[] b = readAll(new java.io.FileInputStream(tf));
                ov = new String(b, "UTF-8").trim();
            }
            if (ov != null && ov.length() > 0) {
                st = Integer.parseInt(ov.trim());
            }
            if (st >= 0 && !r.has("thule_task_chain_info")) {
                JSONObject chain = new JSONObject();
                chain.put("task_chain_state", st);
                r.put("thule_task_chain_info", chain);
                trace("thule_task_chain_info.state = " + st);
            }
        } catch (Throwable ignoredTH) {
        }
        try {
            if (sBlocks == null) {
                // 优先读设备上的 /sdcard/yj/blocks.json（改这个文件不用重打包，
                // 重启游戏就生效），没有才用 APK 里那份。
                java.io.File f = new java.io.File("/sdcard/yj/blocks.json");
                if (f.exists()) {
                    byte[] b = readAll(new java.io.FileInputStream(f));
                    sBlocks = new JSONObject(new String(b, "UTF-8"));
                    trace("server blocks from /sdcard/yj/blocks.json");
                } else {
                    boolean wantAsset = new java.io.File("/sdcard/yj/blocks_asset").exists();
                    if (!wantAsset) {
                        sBlocks = new JSONObject();
                        trace("server blocks: none (no /sdcard/yj/blocks.json)");
                        return;
                    }
                    java.io.InputStream in = sCtx.getAssets().open("offline_blocks.json");
                    byte[] b = readAll(in);
                    in.close();
                    sBlocks = new JSONObject(new String(b, "UTF-8"));
                }
            }
            java.util.Iterator<String> it = sBlocks.keys();
            int n = 0;
            while (it.hasNext()) {
                String k = it.next();
                if (!r.has(k)) {
                    r.put(k, sBlocks.get(k));
                    n++;
                }
            }
            trace("server blocks sent: " + n);
        } catch (Throwable t) {
            trace("server blocks failed " + t);
        }
    }

    /**
     * 传承槽（slot_stunt）配置：**官方 login 应答里带的四张常量表**。
     *
     * 至尊装备传承面板里「传承等级」读装备的 CE 字段（实测 CE=7 → 显示 7级），
     * 但「属性传承比例」不是 CE%，而是客户端拿这四张表算出来的：
     *
     *   slot_stunt_grade_power_rate  [0.2, 0.3, 0.6, 1, 1.5]       按品级
     *   slot_stunt_init_power        [200, 300, 600, 1000, 1500]   初始值
     *   slot_stunt_lv_power          每级一个值（50 级）            按传承等级
     *   slot_stunt_star_power        [1000..6000]                  按星级
     *
     * 我们之前一条都没下发 → 比例恒为 0（等级却显示正常），这就是「至尊传承
     * 显示 0%」的原因。数值取自官方 16.9 的真实应答（纯配置，与账号无关），
     * 11.4 的表结构一致。
     */
    private static void putSlotStuntConfig(JSONObject o) {
        try {
            JSONArray gradeRate = new JSONArray();
            double[] gr = {0.2, 0.3, 0.6, 1, 1.5};
            for (int i = 0; i < gr.length; i++) {
                gradeRate.put(gr[i]);
            }
            JSONArray initPower = new JSONArray();
            int[] ip = {200, 300, 600, 1000, 1500};
            for (int i = 0; i < ip.length; i++) {
                initPower.put(ip[i]);
            }
            JSONArray starPower = new JSONArray();
            int[] sp = {1000, 2000, 3000, 4000, 5000, 6000};
            for (int i = 0; i < sp.length; i++) {
                starPower.put(sp[i]);
            }
            // 官服实测（roledata2 fixture）正好 50 项：
            // 1~10 级 100、11~20 级 200、21~30 级 300、31~35 级 400、
            // 36~40 级 500、41~45 级 600、46~50 级 800
            JSONArray lvPower = new JSONArray();
            int[] bands = {100, 200, 300, 400, 500, 600, 800};
            int[] counts = {10, 10, 10, 5, 5, 5, 5};
            for (int b = 0; b < bands.length; b++) {
                for (int i = 0; i < counts[b]; i++) {
                    lvPower.put(bands[b]);
                }
            }
            o.put("slot_stunt_grade_power_rate", gradeRate);
            o.put("slot_stunt_init_power", initPower);
            o.put("slot_stunt_star_power", starPower);
            o.put("slot_stunt_lv_power", lvPower);

            // 诛仙剑阵继承比例（官方 16.9 roledata2 应答里的三个常量，都是 0.5）。
            // 11.4 客户端 libgame 里同样有这三个 key：
            //   0x1bf3120 sword_law_attr_ratio
            //   0x1bf3138 sword_law_power_ratio
            //   0x1bf3150 sword_law_pray_power_ratio
            // 面板「剑阵将继承嵌入法宝属性的%d%%」= 0.5 × 100 = 50%；
            // 缺了它们 A=0 → 面板恒显示 0%。见 0x87cbcc / 0x90a3e4 的解析代码。
            o.put("sword_law_attr_ratio", 0.5);
            o.put("sword_law_power_ratio", 0.5);
            o.put("sword_law_pray_power_ratio", 0.5);

            // 灵猴现世（1130）召出的六耳猕猴影子伤害系数。官方 16.9 下发 1.2，
            // 11.4 服务端从来没发过 → 客户端按 0 处理，六耳打人没伤害。
            // key 在 libgame 0x1bf62b8，解析在 0x90a5d8。
            o.put("enliven_shadow_ratio", 1.2);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 传承等级迁移：把 CU / CW 里写着的老数据搬到 CE。
     *
     * 实证（用户存档里那把「万千花篮」，A2=500018）：
     *     {"A2":500018, ..., "CE":27, "CV":100, ...}
     * 法宝传承面板显示「传承等级：27级 / 属性传承比例：27.0%」，
     * 也就是 **CE = 传承等级，比例 = CE%**。CU 是另一个池子（客户端不读）。
     */
    /**
     * 至尊传承：客户端上传的装备对象里经常不带 CV（或者带 0），
     * 上传一次就把服务端刚写进去的比例盖回 0，面板永远 0.0%。
     * 这里在下发/落盘前按 CE 把 CV 补回（1 级 = 1%，上限 100）。
     */
    private static void normalizeNoblestInherit(JSONObject sd) {
        if (sd == null) {
            return;
        }
        try {
            java.util.ArrayList<Object> roots = new java.util.ArrayList<Object>();
            JSONObject bag = sd.optJSONObject("bag");
            if (bag != null && bag.optJSONArray("item") != null) {
                roots.add(bag.optJSONArray("item"));
            }
            String[] keys = {"equips", "prayequips", "saled_equips_list", "removedequips"};
            for (String k : keys) {
                Object o = sd.opt(k);
                if (o instanceof JSONArray) {
                    roots.add(o);
                }
            }
            for (Object root : roots) {
                JSONArray arr = (JSONArray) root;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    int ce = Math.max(it.optInt("CE", 0),
                            Math.max(it.optInt("CU", 0), it.optInt("CW", 0)));
                    JSONObject nb = table("noblest_equip");
                    boolean isNoblest = nb != null
                            && nb.optJSONObject(String.valueOf(it.optInt("A2", 0))) != null;
                    boolean hasInherit = isNoblest || it.has("CE") || it.has("CV")
                            || it.has("CD") || it.has("CQ");
                    if (!hasInherit) {
                        continue;
                    }
                    if (ce > 0) {
                        it.put("CE", ce);
                        it.put("CU", ce);
                        it.put("CW", ce);
                    }
                    // CV 是「传承等级上限」，官服恒为 100（存档实证：所有装备 CV=100）。
                    // 以前这里写成 CV = min(100, CE) —— 等于每锻升一级就把上限压到
                    // 当前等级，下一次锻升立刻"已到上限"。这是「传承等级起不来」的
                    // 第二个原因（第一个是 CE 读成了 CU）。
                    if (it.optInt("CV", 0) != 100) {
                        it.put("CV", 100);
                    }
                    // 聚炼字段默认值（存档里客户端自己序列化出来的就是 CL=0 / CM=1）。
                    if (!it.has("CL")) {
                        it.put("CL", 0);
                    }
                    if (!it.has("CM")) {
                        it.put("CM", 1);
                    }
                    // 传承槽：CD 指向源装备的实例 id。官方不去动 CQ（客户端自己重建），
                    // 这里也**不再**往 CQ 上写 CE/CV —— 那会把源装备的传承等级改掉。
                    // 但如果 CD 有、CQ 丢了（老档里服务端只落了 CD），按 CD 把源装备
                    // 快照补回来，面板才有「传递值」可显示。
                    int cd = it.optInt("CD", 0);
                    if (cd > 0 && it.optJSONObject("CQ") == null) {
                        JSONObject srcItem = findByA5(arr, cd);
                        if (srcItem != null && srcItem != it) {
                            JSONObject cq = new JSONObject(srcItem.toString());
                            int selfUid = it.optInt("A5", 0);
                            cq.put("CG", selfUid);
                            it.put("CQ", cq);
                            srcItem.put("CG", selfUid);
                            trace("noblest inherit: rebuild CQ A5=" + selfUid + " <- " + cd);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            trace("normalizeNoblestInherit failed " + t);
        }
    }

    private static void migrateInheritLevel(JSONObject sd) {
        try {
            JSONArray arr = new JSONArray();
            JSONObject bag = sd.optJSONObject("bag");
            JSONArray items = bag == null ? null : bag.optJSONArray("item");
            if (items != null) {
                arr.put(items);
            }
            JSONArray eq = sd.optJSONArray("equips");
            if (eq != null) {
                arr.put(eq);
            }
            for (int a = 0; a < arr.length(); a++) {
                JSONArray list = arr.optJSONArray(a);
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    int ce = it.optInt("CE", 0);
                    if (ce > 0) {
                        continue;
                    }
                    int cu = Math.max(it.optInt("CU", 0), it.optInt("CW", 0));
                    if (cu > 0) {
                        it.put("CE", cu);
                        trace("inherit level migrated A5=" + it.optInt("A5", 0) + " CU->CE " + cu);
                    }
                }
            }
        } catch (Throwable t) {
            trace("migrateInheritLevel failed " + t);
        }
    }

    /**
     * 主动技能对齐（以 16.9 的 role_creation.initial_response 为准）。
     *
     * 16.9 建角色时只做两件事：
     *   1) fategod.skills = role.sdb 里该职业的 fateGod_skill；
     *   2) 被动技能等级归零。
     * 主动技能列表本身由客户端维护（学习/拖动后随 savedata 上传），
     * 服务端只做「同职业校验 + 槽位整理」，不重排玩家的技能顺序。
     *
     * 11.4 这边以前只往 fategod 里补 skills（而且只在 key 缺失时补），
     * 所以角色换过职业后会留下**上一个职业**的本命神技能；客户端按这个
     * 列表去生成主动技能，就会把 7xxx 的技能塞进唐僧/悟空身上，
     * 表现就是「主动技能全都不对」。
     *
     * 这里每次都按当前 roleid 重写 fategod.skills，并把主动技能里
     * 不属于该职业 / 不在 initiative_skill.sdb 里的条目清掉；
     * 列表被清空时用「本命神技能 + 推荐技能」补 4 个，保证技能栏不是空的。
     */
    private static void reconcileRoleSkills(JSONObject store) {
        try {
            JSONObject rd = store.optJSONObject("roledata");
            if (rd == null) {
                return;
            }
            JSONObject player = rd.optJSONObject("player");
            JSONObject role = rd.optJSONObject("role");
            if (role == null) {
                role = new JSONObject();
                rd.put("role", role);
            }
            int roleId = player == null ? 0 : player.optInt("roleid", 0);
            if (roleId <= 0) {
                roleId = roleIdInRoles(sCurRid);
                if (roleId > 0 && player != null) {
                    player.put("roleid", roleId);
                }
            }
            if (roleId <= 0) {
                return;
            }
            // roles.json / profile 必须和存档里的职业一致，否则登录界面画的是
            // 旧职业、进游戏加载的却是新职业的技能，同样会「主动技能全错」。
            if (sCurRid > 0) {
                syncRoleClass(sCurRid, roleId);
            }

            JSONObject roleRow = table("role") == null ? null
                    : table("role").optJSONObject(String.valueOf(roleId));
            JSONArray fate = roleRow == null ? null : roleRow.optJSONArray("fateGod_skill");

            // fategod.skills 以 role.sdb 为准，**每次都覆盖**（16.9 的 initial_response 就是直接赋值）。
            JSONObject fg = role.optJSONObject("fategod");
            if (fg == null) {
                fg = new JSONObject();
                role.put("fategod", fg);
            }
            if (fate != null && fate.length() > 0) {
                fg.put("skills", new JSONArray(fate.toString()));
            }
            if (!fg.has("id")) {
                fg.put("id", 0);
            }
            if (!fg.has("exp")) {
                fg.put("exp", 0);
            }

            JSONObject rs = role.optJSONObject("roleskill");
            if (rs == null) {
                rs = new JSONObject();
                role.put("roleskill", rs);
            }
            JSONArray ini = rs.optJSONArray("initiative_skill");
            java.util.LinkedHashMap<Integer, JSONObject> byId =
                    new java.util.LinkedHashMap<Integer, JSONObject>();
            int foreign = 0;
            if (ini != null) {
                for (int i = 0; i < ini.length(); i++) {
                    JSONObject sk = ini.optJSONObject(i);
                    if (sk == null) {
                        continue;
                    }
                    int sid = sk.optInt("skillid", 0);
                    if (!skillAllowedForRole(sid, roleId)) {
                        foreign++;
                        continue;
                    }
                    if (byId.containsKey(Integer.valueOf(sid))) {
                        continue;
                    }
                    byId.put(Integer.valueOf(sid), sk);
                }
            }

            java.util.ArrayList<JSONObject> list =
                    new java.util.ArrayList<JSONObject>(byId.values());
            if (list.isEmpty()) {
                // 新角色 / 转职后 / 旧档里全是别的职业技能：按 16.9 的
                // 「本命神技能优先」补一套该职业的初始主动技能。
                list = defaultActiveSkills(roleId);
                trace("roleskill: seeded " + list.size() + " skills for role " + roleId);
            } else if (foreign > 0) {
                trace("roleskill: dropped " + foreign + " foreign skills for role " + roleId);
            }

            boolean[] used = new boolean[6];
            java.util.ArrayList<JSONObject> kept = new java.util.ArrayList<JSONObject>();
            java.util.ArrayList<JSONObject> moved = new java.util.ArrayList<JSONObject>();
            for (JSONObject sk : list) {
                int idx = sk.optInt("skillindex", 0);
                if (idx >= 1 && idx <= 5 && !used[idx]) {
                    used[idx] = true;
                    kept.add(sk);
                } else {
                    moved.add(sk);
                }
            }
            for (JSONObject sk : moved) {
                int slot = 1;
                while (slot <= 5 && used[slot]) {
                    slot++;
                }
                if (slot > 5) {
                    // 超过 5 个就只能丢掉，绝不能再塞进 5 号槽（会和备用技能撞车）
                    trace("roleskill: drop overflow skill " + sk.optInt("skillid", 0));
                    continue;
                }
                used[slot] = true;
                sk.put("skillindex", slot);
                trace("roleskill: " + sk.optInt("skillid", 0) + " -> slot " + slot);
                kept.add(sk);
            }
            java.util.Collections.sort(kept, new java.util.Comparator<JSONObject>() {
                public int compare(JSONObject a, JSONObject b) {
                    return a.optInt("skillindex", 0) - b.optInt("skillindex", 0);
                }
            });
            JSONArray out = new JSONArray();
            for (JSONObject sk : kept) {
                sk.put("skilltype", 15);
                out.put(sk);
            }
            rs.put("initiative_skill", out);
            trace("roleskill: reconciled " + out.length() + " skills, roleid=" + roleId);
        } catch (Throwable t) {
            trace("reconcileRoleSkills failed " + t);
        }
    }

    /** 按 roleid 从 roles.json 里取职业；找不到返回 0。 */
    private static int roleIdInRoles(int rid) {
        try {
            JSONArray roles = readRoles();
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r != null && r.optInt("rid", 0) == rid) {
                    return r.optInt("roleid", 0);
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** roles.json / profile_<rid>.json 跟存档职业对齐（只改职业，不动等级/进度）。 */
    private static void syncRoleClass(int rid, int roleId) {
        try {
            JSONArray roles = readRoles();
            boolean changed = false;
            for (int i = 0; i < roles.length(); i++) {
                JSONObject r = roles.optJSONObject(i);
                if (r != null && r.optInt("rid", 0) == rid
                        && r.optInt("roleid", 0) != roleId) {
                    r.put("roleid", roleId);
                    changed = true;
                }
            }
            if (changed) {
                writeRoles(roles);
                trace("role class synced: rid=" + rid + " roleid=" + roleId);
            }
            JSONObject p = readProfile();
            if (p != null && p.optInt("rid", 0) == rid
                    && p.optInt("roleid", 0) != roleId) {
                p.put("roleid", roleId);
                writeProfile(p);
                trace("profile class synced: rid=" + rid + " roleid=" + roleId);
            }
        } catch (Throwable t) {
            trace("syncRoleClass failed " + t);
        }
    }

    /**
     * 11.4 的 initiative_skill.sdb 只有 1xxx~7xxx；
     * 技能 id 的千位就是职业 id（1100/1000=1，7240/1000=7）。
     */
    private static boolean skillAllowedForRole(int sid, int roleId) {
        if (sid <= 0 || roleId < 1 || roleId > 7) {
            return false;
        }
        if (sid / 1000 != roleId) {
            return false;
        }
        JSONObject tab = table("initiative_skill");
        return tab != null && tab.optJSONObject(String.valueOf(sid)) != null;
    }

    /**
     * 该职业的初始主动技能：本命神技能（role.sdb 的 fateGod_skill）优先，
     * 再用 initiative_skill.sdb 里 is_suggest=1 的技能补到 4 个。
     * 和 16.9 的 played save 一样，默认 4 个已装备技能（第 5 格备用留空）。
     */
    private static java.util.ArrayList<JSONObject> defaultActiveSkills(int roleId) {
        java.util.ArrayList<JSONObject> out = new java.util.ArrayList<JSONObject>();
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<Integer>();
        try {
            JSONObject roleRow = table("role") == null ? null
                    : table("role").optJSONObject(String.valueOf(roleId));
            JSONArray fate = roleRow == null ? null : roleRow.optJSONArray("fateGod_skill");
            if (fate != null) {
                for (int i = 0; i < fate.length(); i++) {
                    int sid = fate.optInt(i, 0);
                    if (skillAllowedForRole(sid, roleId)) {
                        ids.add(Integer.valueOf(sid));
                    }
                }
            }
            if (ids.size() < 4) {
                JSONObject tab = table("initiative_skill");
                if (tab != null) {
                    java.util.TreeSet<Integer> suggest = new java.util.TreeSet<Integer>();
                    Iterator<String> it = tab.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONObject row = tab.optJSONObject(k);
                        if (row == null || row.optInt("is_suggest", 0) != 1) {
                            continue;
                        }
                        try {
                            int sid = Integer.parseInt(k);
                            if (skillAllowedForRole(sid, roleId)) {
                                suggest.add(Integer.valueOf(sid));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    for (Integer sid : suggest) {
                        if (ids.size() >= 4) {
                            break;
                        }
                        ids.add(sid);
                    }
                }
            }
            if (ids.size() < 4) {
                JSONObject tab = table("initiative_skill");
                if (tab != null) {
                    java.util.TreeSet<Integer> all = new java.util.TreeSet<Integer>();
                    Iterator<String> it = tab.keys();
                    while (it.hasNext()) {
                        try {
                            int sid = Integer.parseInt(it.next());
                            if (skillAllowedForRole(sid, roleId)) {
                                all.add(Integer.valueOf(sid));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    for (Integer sid : all) {
                        if (ids.size() >= 4) {
                            break;
                        }
                        ids.add(sid);
                    }
                }
            }
            int slot = 1;
            for (Integer sid : ids) {
                if (slot > 5) {
                    break;
                }
                JSONObject sk = new JSONObject();
                sk.put("skillid", sid.intValue());
                sk.put("skilltype", 15);
                sk.put("skillindex", slot);
                out.add(sk);
                slot++;
            }
        } catch (Throwable t) {
            trace("defaultActiveSkills failed " + t);
        }
        return out;
    }

    /**
     * 11.4 的称号 id 是 6600001..（title.sdb），而 16.9 模板里的
     * show_title 是 1,2,3... 这种小 id；客户端按全 id 匹配，所以面板
     * 里所有称号都显示「未激活」。这里按 title 表重建 show_title。
     */
    private static void unlockTitles(JSONObject sd) {
        if (sd == null) {
            return;
        }
        try {
            JSONObject tab = table("title");
            if (tab == null || tab.length() == 0) {
                return;
            }
            java.util.List<Integer> ids = new java.util.ArrayList<Integer>();
            java.util.Iterator<String> it = tab.keys();
            while (it.hasNext()) {
                try {
                    ids.add(Integer.valueOf(Integer.parseInt(it.next())));
                } catch (Throwable ignored) {
                }
            }
            java.util.Collections.sort(ids);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < ids.size(); i++) {
                arr.put(ids.get(i).intValue());
            }
            JSONObject rd = sd.optJSONObject("roledata");
            JSONObject role = rd == null ? null : rd.optJSONObject("role");
            if (role != null) {
                role.put("show_title", arr);
                trace("titles: show_title -> " + ids.size() + " ids");
            }
        } catch (Throwable t) {
            trace("unlockTitles failed " + t);
        }
    }

    private static JSONObject buildSavedataBlock() {
        try {
            JSONObject store = readSaveObj();
            if (store == null || store.length() == 0) {
                return null;
            }
            if (sV13) {
                // 13.x 用客户端自己上传的原生新档；11.4 的仙剑/引导/问仙/宠物加料
                // 会写坏 13.x 的 native 容器（scudo SIGSEGV），这里只补最低校验字段。
                return vanillaSave(store);
            }
            // Round-trip everything the client ever uploaded, instead of a
            // whitelist: a whitelist silently dropped anything not enumerated here
            // (equips, removedequips, firstpass, hiremark, ...), which is exactly
            // how worn gear "disappeared" between sessions.
            //
            // ⚠️ 问仙/峰值必须先写回 store 再拷贝：客户端上传的 A7..AA 已经叠过
            // 峰值，如果只在 sd 上叠，下一次上传就会在叠过的值上再叠一次。
            praySyncStore(store);
            applyPeakPray(store);
            // 传说装备补 fairy_grade，解锁仙器面板。
            markFairyEquip(store);
            // 主动技能 / 本命神技能按当前职业校正（16.9 的 role_creation 同款规则）。
            reconcileRoleSkills(store);
            writeSave(store.toString());
            JSONObject sd = new JSONObject(store.toString());

            // 传承等级字段迁移：**CE 才是客户端读的那个**（从存档实证：
            // 「万千花篮」A2=500018 是 {CE:27, CV:100}，法宝传承面板就显示
            // 27级 / 27.0%）。之前锻升往 CU/CW 里写过，这里统一搬回 CE。
            migrateInheritLevel(sd);
            normalizeNoblestInherit(sd);

            // 「跳过新手教程」：悬浮按钮写下 /sdcard/yj/skipguide 后，把所有引导 id
            // 都标成已完成（roledata.player.completeguide）。客户端据此不再弹教学。
            if (new java.io.File("/sdcard/yj/skipguide").exists()) {
                fillCompleteGuide(sd);
            }

            // 满配沙盒：背包格子开满 + 角色等级拉高（主界面靠等级/引导解锁的系统
            // 按钮，比如"真灵"那一排，都是 module_open_guide.sdb 的等级门槛）。
            // 关掉就建 /sdcard/yj/lowlevel。
            if (!new java.io.File("/sdcard/yj/lowlevel").exists()) {
                boostSave(sd);
            }

            // 本命神材料/本体：1w 唤神石 + 509200
            grantFateGodItems(sd);
            sanitizeFateGod(sd);

            // 称号：旧模板的 show_title 是 16.9 的小 id（1,2,3...），11.4 的
            // title.sdb 是 6600001..，客户端按全 id 匹配不到，面板就全是「未激活」。
            // 这里按 11.4 的 title 表重建 show_title（全 id），激活/装备状态随之上报。
            unlockTitles(sd);

            // 主界面那排系统按钮（含真灵）按 module_open_guide 的 9092xx 引导 id
            // 判断；旧代码只填到 900999，909205.. 这些模块永远不命中。
            fillCompleteGuide(sd);

            // 真灵：16.9 的 roledata2 一定带 true_spirit_bag，11.4 客户端
            // 靠这个块创建右上角的真灵入口；单机版之前只塞了 pet.petinfo，
            // 原生真灵页因此被隐藏。这里按 16.9 的形状补回去。
            try {
                JSONObject tsb = trueSpiritBag();
                JSONArray tsList = tsb.optJSONArray("truespiritinfo");
                if (tsList == null) {
                    tsList = tsb.optJSONArray("list");
                }
                if (!tsb.has("maxspiritid")) {
                    tsb.put("maxspiritid", 439999);
                }
                if (!tsb.has("list") && tsList != null) {
                    tsb.put("list", tsList);
                }
                sd.put("true_spirit_bag", tsb);
                if (tsList != null) {
                    sd.put("truespiritinfo", tsList);
                    sd.put("truespiritlist", tsList);
                }
                trace("true_spirit_bag: sent, list=" + (tsList == null ? 0 : tsList.length()));
            } catch (Throwable t) {
                trace("true_spirit_bag send failed " + t);
            }

            // 融合材料：beast_mix_rule.sdb 里 6820000（229+217 那只）要
            // 503345×2 + 503346×6。直接放进背包（用 itemJson 的完整形状，
            // 只给 A2/A4 会让客户端开背包时 SIGSEGV）。
            try {
                JSONObject bagObj = sd.optJSONObject("bag");
                if (bagObj != null) {
                    JSONArray its = bagObj.optJSONArray("item");
                    if (its == null) {
                        its = new JSONArray();
                        bagObj.put("item", its);
                    }
                    int[] mixMats = {503345, 503346, 503208, 503209, 503210, 503199, 503206, 503336, 503212,
                            503207, 503227, 503370};
                    for (int mi = 0; mi < mixMats.length; mi++) {
                        int mid = mixMats[mi];
                        boolean has = false;
                        for (int i = 0; i < its.length(); i++) {
                            JSONObject o = its.optJSONObject(i);
                            if (o != null && o.optInt("A2", 0) == mid) {
                                o.put("A4", 9999);
                                has = true;
                                break;
                            }
                        }
                        if (!has) {
                            its.put(itemJson(mid, 9999));
                        }
                    }
                    // 至尊饰品两件（千尊佛缘镜 500223 / 万古圣临盏 500224），
                    // 按装备形状给，星级 5（MAIL_STAR）。
                    int[] jewels = {500223, 500224, 1304, 1311, 500225};
                    for (int ji = 0; ji < jewels.length; ji++) {
                        int jid = jewels[ji];
                        boolean jhas = false;
                        for (int i = 0; i < its.length(); i++) {
                            JSONObject o = its.optJSONObject(i);
                            if (o != null && o.optInt("A2", 0) == jid) {
                                jhas = true;
                                break;
                            }
                        }
                        if (!jhas) {
                            its.put(equipJson(jid, MAIL_STAR));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 存档自检：装备条目必须是完整形状（有 CC 星级、A7/A8/A9/AA 属性）。
            // 以前用 itemJson 发装备只给了 A2/A4/A9=8，客户端渲染这种残缺装备
            // 会空指针闪退（A9=8 在装备里是"攻击 8"，不是星级）。
            try {
                JSONObject bagObj2 = sd.optJSONObject("bag");
                JSONArray its2 = bagObj2 == null ? null : bagObj2.optJSONArray("item");
                if (its2 != null) {
                    for (int i = 0; i < its2.length(); i++) {
                        JSONObject o = its2.optJSONObject(i);
                        if (o == null) {
                            continue;
                        }
                        int a2 = o.optInt("A2", 0);
                        if (a2 <= 0) {
                            continue;
                        }
                        JSONObject row = table("noblest_equip") == null ? null
                                : table("noblest_equip").optJSONObject(String.valueOf(a2));
                        if (row == null && table("equip") != null) {
                            row = table("equip").optJSONObject(String.valueOf(a2));
                        }
                        if (row == null) {
                            continue;
                        }
                        if (!o.has("CC")) {
                            o.put("CC", 0);
                        }
                        if (!o.has("A7") && row.optInt("hp_min", 0) > 0) {
                            o.put("A7", row.optInt("hp_min"));
                        }
                        if (!o.has("A8") && row.optInt("mp_min", 0) > 0) {
                            o.put("A8", row.optInt("mp_min"));
                        }
                        if ((!o.has("A9") || o.optInt("A9", 0) == 8) && row.optInt("str_min", 0) > 0) {
                            o.put("A9", row.optInt("str_min"));
                        }
                        if (!o.has("AA") && row.optInt("def_min", 0) > 0) {
                            o.put("AA", row.optInt("def_min"));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 调试残留清理：509334 上被注入过 CW..DZ/CQ 等非法字段，
            // 客户端读到会 SIGSEGV。下发前把这只装备恢复成干净结构。
            mergeNoblestIntoBase(sd);
            sanitizeTestItem(sd);
            // 商店卖过的启灵珠可能带 A5/A6/A9/BG → 客户端读档崩。下发前洗成 {A2,A4}。
            sanitizeAuraBalls(sd);
            // 不可堆叠道具的 A4 必须=1（旧商店发过 99 个 510072 → 读档崩）
            sanitizeItemStacks(sd);
            ensureSoarMaterials(sd);

            // 用户要求：真灵以后统一放宠物背包（pet.petinfo），
            // 不再下发独立 true_spirit_bag。
            // putTrueSpiritInPetBag(sd); // 9005 塞宠物背包会让角色选择预览崩溃，停用
            removeTrueSpiritFromPetBag(sd);

            // isValidPoint requires the member to exist; 0 is fine.
            Object pt = sd.opt("point");
            if (!(pt instanceof Number)) {
                sd.put("point", 0);
            }

            // isValidData: bagsize + maxglobalitemid + spiritcount must all exist.
            JSONObject bag = sd.optJSONObject("bag");
            if (bag == null) {
                bag = new JSONObject();
            }
            if (!bag.has("bagsize")) {
                bag.put("bagsize", 72);
            }
            if (!bag.has("maxglobalitemid")) {
                bag.put("maxglobalitemid", 4400);
            }
            if (!bag.has("spiritcount")) {
                bag.put("spiritcount", 0);
            }
            if (!(bag.opt("item") instanceof JSONArray)) {
                bag.put("item", new JSONArray());
            }
            sd.put("bag", bag);

            // 第二次登录送：四把仙剑 + 5000 法灵精魄（只发一次，bag.givensword 落盘）。
            // 登录次数在 action=login 的 enterserver 阶段 +1（见 bumpLoginCount）。
            if (loginCount() >= 2) {
                grantSwordsOnce(sd);
            }

            // 用户要求（A）：预建 6 职业各一个 1 级新角色，但**不动现有存档的等级**。
            // 所以这里不再把当前存档压到 1 级。

            // 背包里的魂器品质装备统一改成八阶（AV = 阶数）。
            // 存档里可能是旧值，所以下发前再规范一次，保证客户端拿到的就是八阶。
            normalizeSoulDegrees(bag);

            // 四把诛仙仙剑统一刷成「10 阶 / 5 孔 / 五行金」（老存档里的也一起修）
            normalizeSwords(sd);

            // 诛仙剑阵继承嵌入的每把仙剑 50% 属性（离线版客户端不算，服务端补）
            applySwordArrayInherit(bag);

            // isValidData: "psl" must exist and be an array. "mlru" is optional
            // but has to be an array when present.
            JSONObject lvl = sd.optJSONObject("level");
            if (lvl == null) {
                lvl = new JSONObject();
            }
            if (!(lvl.opt("psl") instanceof JSONArray)) {
                lvl.put("psl", new JSONArray());
            }
            if (lvl.has("mlru") && !(lvl.opt("mlru") instanceof JSONArray)) {
                lvl.remove("mlru");
            }
            if (!lvl.has("phfiel")) {
                lvl.put("phfiel", 0);
            }
            // 「关卡全解锁」已彻底移除，只保留极北之地开放
            // （极北是靠 roledata2 里的 thule_task_chain_info.task_chain_state=2 开的，
            //  见 applyServerBlocks，跟关卡进度无关）。
            // 之前灌进存档的 psl 条目不再清理：清空会让地图渲染 SIGSEGV。
            sd.put("level", lvl);

            // 邮件送的青龙妖圣（229）：petinfo 里没有就补一只，技能全给。
            if (sd.opt("pet") instanceof JSONArray) {
                JSONObject pb = new JSONObject();
                pb.put("bagsize", 8);
                pb.put("petinfo", sd.optJSONArray("pet"));
                sd.put("pet", pb);
            }
            JSONObject petBag = sd.optJSONObject("pet");
            if (petBag == null) {
                petBag = new JSONObject();
                sd.put("pet", petBag);
            }
            if (!(petBag.opt("petinfo") instanceof JSONArray)) {
                petBag.put("petinfo", new JSONArray());
            }
            if (petBag.optInt("bagsize", 0) < 8) {
                petBag.put("bagsize", 8);
            }

            // player.isValidData: isMember("roleid")
            JSONObject rd = sd.optJSONObject("roledata");
            if (rd != null) {
                JSONObject player = rd.optJSONObject("player");
                if (player != null) {
                    // 存档是"一份存档多个角色"共用：客户端重装后默认选中的 roleid
                    // 可能和存档里记的不一样，一旦不一样客户端就当新号处理——
                    // 于是新手教程重跑、背包清空。所以这里每次都按玩家当前选的
                    // 角色覆盖 roleid（readProfile 里记的就是登录时选的那个）。
                    JSONObject p = readProfile();
                    int pickedRole = p != null ? p.optInt("roleid", 0) : 0;
                    if (pickedRole > 0 && player.optInt("roleid", 0) != pickedRole) {
                        trace("savedata: roleid " + player.optInt("roleid", 0)
                                + " -> " + pickedRole + " (profile)");
                        player.put("roleid", pickedRole);
                    } else if (pickedRole <= 0 && !player.has("roleid")) {
                        player.put("roleid", 1);
                    }
                }
                // 等级固定 90（用户要求「所有角色改为 90 级」）。
                // 每次下发存档都写一遍，客户端上传什么等级都盖回去。
                try {
                    JSONObject roleLv = rd.optJSONObject("role");
                    if (roleLv == null) {
                        roleLv = new JSONObject();
                        rd.put("role", roleLv);
                    }
                    roleLv.put("rolelv", MAX_ROLE_LV);
                    roleLv.put("roleexp", 0);
                    // 主动技能已经在上面的 reconcileRoleSkills() 里按职业校正过。
                } catch (Throwable t) {
                    trace("force role level failed " + t);
                }
                // 教程/引导全部标记为已完成。
                // 引导表：guide_sequence(51 条序列) + guide_step(274 步) +
                // guide_prince_step(王子 38 步)。等级到 50 后会激活「本命神引导」，
                // 它要求去虚空之境，而虚空之境的面板是活动系统驱动的（空）→ 卡死。
                // 把全部 id 塞进 completeguide，教程就认为全都做完了。
                try {
                    JSONObject pl2 = rd.optJSONObject("player");
                    if (pl2 != null) {
                        JSONArray cg = pl2.optJSONArray("completeguide");
                        if (cg == null) {
                            cg = new JSONArray();
                            pl2.put("completeguide", cg);
                        }
                        java.util.HashSet<Integer> haveG = new java.util.HashSet<Integer>();
                        for (int i = 0; i < cg.length(); i++) {
                            haveG.add(Integer.valueOf(cg.optInt(i, 0)));
                        }
                        int addedG = 0;
                        String[] gtabs = {"guide_sequence", "guide_step", "guide_prince_step"};
                        for (int gi = 0; gi < gtabs.length; gi++) {
                            JSONObject gt = table(gtabs[gi]);
                            if (gt == null) {
                                continue;
                            }
                            java.util.Iterator<String> git = gt.keys();
                            while (git.hasNext()) {
                                String gk = git.next();
                                try {
                                    int gid = Integer.parseInt(gk);
                                    if (haveG.add(Integer.valueOf(gid))) {
                                        cg.put(gid);
                                        addedG++;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        if (addedG > 0) {
                            trace("guide: completed +" + addedG + " total=" + cg.length());
                        }
                    }
                } catch (Throwable t) {
                    trace("guide complete failed " + t);
                }
            }

            // pet.isValidData is isArray(), and an EMPTY array fails it - so an
            // empty pet member must be dropped rather than sent.
            //
            // ⚠️ 宠物下发改成「原样透传 + 只送一次」：
            // 以前这里会把存档里的宠物按 GIFT_PETS 重新筛一遍，**缺的用 petJson()
            // 重建**。但存档里的 pet 是对象 {bagsize, petinfo:[...]}，而这段代码
            // 只认数组形态 → 每次登录都判定"没有宠物" → 重建一只 Lv1 宝宝档，
            // 玩家养起来的那只（等级/经验/属性）就被顶掉了。
            // 现在：存档里有就**原样**发下去（不碰等级/属性），没有才补一只，
            // 而且补过一次就写 pet.given 标记，之后再也不补。
            JSONArray outPets = new JSONArray();
            JSONArray stored = petBag.optJSONArray("petinfo");
            // 每只宠物都要有一个唯一实例 id（petid）；真服存档里
            // pet.maxglobalpetid 就是下一个可分配的实例 id（新号是 4399）。
            int nextPetId = petBag.optInt("maxglobalpetid", 4399);
            if (nextPetId < 4399) {
                nextPetId = 4399;
            }
            if (stored != null) {
                for (int i = 0; i < stored.length(); i++) {
                    JSONObject o = stored.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    // 真灵（兽）不能留在普通宠物栏：petinfo 是按 pet.sdb 的
                    // dataid 反序列化的，9005 在 pet.sdb 里没有这一行，客户端
                    // 解析出 null 后 GLThread 直接 SIGSEGV（libgame 崩溃栈可复现）。
                    if (o.optInt("dataid", 0) == 9005) {
                        continue;
                    }
                    // 技能表按当前规则重算一遍：早期版本把七位的 6700000 系列
                    // （玲珑圣君/青鸾）当通用技能发给了所有宠物，存档里已经写死了，
                    // 不刷新的话旧宠物还是带着别人的技能。
                    int pid0 = o.optInt("dataid", 0);
                    if (pid0 > 0) {
                        o.put("skill", petSkillsFor(pid0));
                    }
                    int iid = o.optInt("petid", 0);
                    if (iid <= 0) {
                        o.put("petid", nextPetId++);
                    } else if (iid >= nextPetId) {
                        nextPetId = iid + 1;
                    }
                    outPets.put(o);
                }
            }
            // 补发 GIFT_PETS 里缺的那几只（只补一次，pet.given 落盘后不再补）。
            // 229 青龙妖圣 / 253 青龙邪君：存档里已有的一律原样保留，不重建。
            if (!petGiftGiven()) {
                boolean added = false;
                for (int gi = 0; gi < GIFT_PETS.length; gi++) {
                    int gid = GIFT_PETS[gi];
                    boolean have = false;
                    for (int i = 0; i < outPets.length(); i++) {
                        JSONObject o = outPets.optJSONObject(i);
                        if (o != null && o.optInt("dataid", 0) == gid) {
                            have = true;
                            break;
                        }
                    }
                    if (have) {
                        continue;
                    }
                    JSONObject gp = petJson(gid);
                    gp.put("petid", nextPetId++);
                    outPets.put(gp);
                    added = true;
                    trace("pet granted once: " + gid);
                }
                if (added) {
                    petBag.put("petinfo", outPets);
                    setPetGiftGiven();
                    // 同时落盘：客户端要到下次上传存档才会把宠物写回来，
                    // 中间重进会丢，所以这里直接写进 stored save。
                    try {
                        JSONObject st = readSaveObj();
                        JSONObject sp = st.optJSONObject("pet");
                        if (sp == null) {
                            sp = new JSONObject();
                            st.put("pet", sp);
                        }
                        if (sp.optInt("bagsize", 0) < 8) {
                            sp.put("bagsize", 8);
                        }
                        sp.put("petinfo", outPets);
                        writeSave(st.toString());
                    } catch (Throwable t2) {
                        trace("pet persist failed " + t2);
                    }
                }
            }
            // 宠物页不能是空的：petinfo 为空时补一只青龙妖圣。
            // 以前只在 pet.given 未置位时补，可 petinfo 一旦被老的
            // 「数组/对象」bug 清空，标记却早已落盘 → 再也补不回来，
            // 宠物页就这么空着（2026-11-08 实测就是这个状态）。
            if (outPets.length() == 0) {
                JSONObject seed = petJson(GIFT_PETS[0]);
                seed.put("petid", nextPetId++);
                outPets.put(seed);
                trace("pet: empty petinfo -> seed " + GIFT_PETS[0]);
            }
            // pet 必须是**对象**：客户端自己上传的就是
            // {"pet":{"bagsize":8,"petinfo":[...]}}（见 protocol.log），
            // 真服 roledata2 也是 {bagsize,petinfo,maxglobalpetid}。
            // 这里以前把 pet 写成数组，YJPet 的反序列化直接失败 →
            // 宠物页空白、宠物不跟随。对象形状实测能正常显示（2026-11-08）。
            JSONObject petOut = petBag;
            petOut.put("petinfo", outPets);
            if (petOut.optInt("bagsize", 0) < 8) {
                petOut.put("bagsize", 8);
            }
            petOut.put("maxglobalpetid", nextPetId);
            sd.put("pet", petOut);


            // ---- 登录奖励 ----------------------------------------------------
            // Every "start game" refills 点券 / 灵魂 / 仙气 / 战功 / 冥币 to 99w.
            //
            // 点券 is not a bag counter: it lives on YJPlayer and
            // analyzeAndApplyDataToGame applies savedata.roledata.player["point"]
            // through YJPlayer::vtable[0x30] (= setPoints, @0x41ab58).
            // The other four are bag counters that
            // YJBagManager::deserializeFromJsonValue reads straight from the bag
            // block. Neither needs a native hook.
            bag.put("spiritcount", LOGIN_BONUS);
            bag.put("auracount", LOGIN_BONUS);
            bag.put("exploitscount", LOGIN_BONUS);
            bag.put("ghostcoincount", LOGIN_BONUS);
            // 点券不在这里写：往 roledata.player 里塞 point 会让
            // analyzeAndApplyDataToGame 走到 setPoints 分支后判失败，界面弹
            // "存档数据已损坏!"。点券沿用原来的做法（应答里的 "point":0 被
            // grantCurrencies 的字符串替换成 99 万）。

            // Worn gear guard. createItemByJson (the only item constructor the bag
            // deserializer uses) restores an item from its ID alone - no count, no
            // gems, no "this one is worn" flag. Nothing in the client reads
            // savedata.equips back into YJBagManager either, so a worn piece can
            // end up neither equipped nor in the bag. Make sure every equipped id
            // also exists as a bag entry so the player still has it.
            JSONArray equips = sd.optJSONArray("equips");
            if (equips != null && equips.length() > 0) {
                java.util.Set<Integer> have = new java.util.HashSet<Integer>();
                JSONArray items = bag.optJSONArray("item");
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it != null) {
                            have.add(it.optInt("A5", it.optInt("dataid", 0)));
                        }
                    }
                }
                int added = 0;
                for (int i = 0; i < equips.length(); i++) {
                    JSONObject e = equips.optJSONObject(i);
                    if (e == null) {
                        continue;
                    }
                    int id = e.optInt("dataid", 0);
                    if (id <= 0 || have.contains(id)) {
                        continue;
                    }
                    items.put(itemJson(id));
                    have.add(id);
                    added++;
                }
                if (added > 0) {
                    bag.put("item", items);
                    trace("savedata: re-added " + added + " worn items to the bag");
                }
            }
            clearBagOnce(sd);
            stripBrokenSwords(sd);
            // 问仙：把服务端权威的数值写进物品，并重建 savedata.prayequips。
            // 10 阶传说装备再自动叠满峰值属性（已在函数开头写回 store）。
            // yj_pray / yj_peak 是服务端内部状态，不下发。
            sd.remove("yj_pray");
            sd.remove("yj_peak");
            return sd;
        } catch (Throwable t) {
            trace("buildSavedataBlock failed " + t);
            return null;
        }
    }

    /** Overlay our stored character onto a canned payload.
     *
     * The reference build hardcodes one character; we keep whatever the player
     * created in profile.json, so a new role is not replaced by that template.
     */
    /** 灵魂/仙气/战功 are currency counters inside the bag block:
     *  spiritcount = 灵魂, auracount = 仙气, exploitscount = 战功.
     *  They are not items, so they must be patched as numbers.
     */
    private static String grantCurrencies(String json) {
        return json.replace("\"spiritcount\":0", "\"spiritcount\":" + GRANT_SPIRIT)
                   .replace("\"auracount\":0", "\"auracount\":" + GRANT_SPIRIT)
                   .replace("\"exploitscount\":0", "\"exploitscount\":" + GRANT_SPIRIT)
                   .replace("\"ghostcoincount\":0", "\"ghostcoincount\":" + GRANT_SPIRIT)
                   .replace("\"spiritcount\": 0", "\"spiritcount\":" + GRANT_SPIRIT)
                   .replace("\"auracount\": 0", "\"auracount\":" + GRANT_SPIRIT)
                   .replace("\"exploitscount\": 0", "\"exploitscount\":" + GRANT_SPIRIT);
    }

    /**
     * Overlay our stored character onto a canned payload.
     *
     * Shapes are dictated by the client (file VA) and are NOT interchangeable:
     *
     *   YJGlobalManager::setRoleListJson @0x3ef7f6
     *       if (!v.isObject()) return;            <- an ARRAY is silently dropped
     *       for (name : v.getMemberNames()) {     <- name = atoi() = service id
     *           el = v[name]; if (!el.isArray()) continue;
     *           si = getServerInfoByServerId(atoi(name)); if (!si) continue;
     *           si->clearRoleList();
     *           for (r : el) if (r->deserializeFromJsonValue()) si->addRole(r);
     *       }
     *
     *   YJServerInfo::deserializeFromJsonValue @0x5ee1f8
     *       service / name / **roleids** (array) / isnew / createtime / starttime
     *       (isValidData @0x5eddb4 only needs "service")
     *
     * So the role list is OBJECT {serviceId: [roles]} and the per-service role id
     * array is "roleids". Shipping an array here is what made the client believe no
     * role existed and send a brand-new createrole2 on every launch - which wipes
     * the character each time.
     */
    private static String personalize(String json) {
        try {
            java.io.File pf = profileFile();
            if (!pf.exists()) {
                return json;
            }
            JSONObject p = new JSONObject(Des.utf8(readAll(pf)));
            String name = p.optString("username", "玩家");
            int rid = p.optInt("rid", 1);
            int roleid = p.optInt("roleid", 1);
            int lv = p.optInt("rolelv", 1);

            JSONObject d = new JSONObject(json);
            if (d.has("rid")) {
                d.put("rid", rid);
            }
            if (d.has("roleid")) {
                d.put("roleid", roleid);
            }
            if (d.has("rolelv")) {
                d.put("rolelv", lv);
            }
            if (d.has("lastrid")) {
                d.put("lastrid", String.valueOf(rid));
            }
            if (d.has("rolelist")) {
                d.put("rolelist", roleListFor(name, roleid, rid));
                // The role list and the "last login" record are validated
                // together: a non-empty rolelist with an empty lastdata makes the
                // client dereference a null JsonCpp value.
                JSONObject last = new JSONObject();
                last.put("service", 1);
                last.put("rid", rid);
                last.put("roleid", roleid);
                last.put("username", name);
                last.put("rolelv", lv);
                d.put("lastdata", last);
            }
            if (d.has("services")) {
                d.put("services", servicesFor(rid));
            }
            return d.toString();
        } catch (Throwable t) {
            trace("personalize failed " + t);
            return json;
        }
    }

    /** services[] with the shape YJServerInfo::deserializeFromJsonValue reads. */
    private static JSONArray servicesFor(int rid) {
        JSONArray arr = new JSONArray();
        try {
            // 多声明几个服务：仙盟/跨服模块按 service id 查服务器信息，
            // 只声明 1 时它会拿不到（getServerInfoByServerId 返回空）->
            // 直接报「仙盟信息获取失败」，连请求都不会发出去。
            JSONArray allRoles = wantNewRole() ? new JSONArray() : readRoles();
            int maxSvc = 5;
            for (int i = 0; i < allRoles.length(); i++) {
                JSONObject r = allRoles.optJSONObject(i);
                if (r != null) {
                    maxSvc = Math.max(maxSvc, r.optInt("service", 1));
                }
            }
            for (int svc = 1; svc <= maxSvc; svc++) {
                JSONObject s = new JSONObject();
                s.put("service", svc);
                String svcName = "单机服" + svc;
                for (int i = 0; i < allRoles.length(); i++) {
                    JSONObject r = allRoles.optJSONObject(i);
                    if (r != null && r.optInt("service", 1) == svc) {
                        svcName = "单机服" + svc + "·" + r.optString("username", "");
                        break;
                    }
                }
                s.put("name", svc == 1 ? "单机服" : svcName);
                JSONArray rids = new JSONArray();
                JSONArray roles = wantNewRole() ? new JSONArray() : readRoles();
                for (int i = 0; i < roles.length(); i++) {
                    JSONObject r = roles.optJSONObject(i);
                    if (r != null) {
                        // 每个服都把全部 rid 列上：空数组会让客户端在服务器列表里
                        // 取不到角色（fault addr 0x10 空指针）。真正的角色过滤在
                        // rolelist 里做（只给当前激活的那一个）。
                        rids.put(r.optInt("rid", 1));
                    }
                }
                s.put("roleids", rids);
                s.put("isnew", 0);
                s.put("createtime", 0);
                s.put("starttime", 0);
                arr.put(s);
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    private static JSONObject baseBlock() {
        JSONObject b = new JSONObject();
        try {
            b.put("host", "127.0.0.1");
            b.put("port", PORT);
            b.put("service", 1);
            // 必须给对象：客户端按 base["pkservice"]["host"]/["port"] 取，
            // 给整数会让 asCString() 拿到 NULL，再构造 std::string 就抛
            // std::logic_error 把进程带走。
            JSONObject pk = new JSONObject();
            pk.put("host", "127.0.0.1");
            pk.put("port", PORT);
            b.put("pkservice", pk);
        } catch (Exception ignored) {
        }
        return b;
    }

    /**
     * Write the stored game state into files/savedata.dat BEFORE cocos starts.
     *
     * The client loads that file at boot (loadBaseData -> _loadDataFromPathToJson
     * -> _analyseSaveData) and then immediately rewrites it from its own members,
     * which resets "cache" to null. So the file has to be (re)written by us on
     * every launch; the native hook on _analyseSaveData then feeds cache.bag /
     * cache.level to YJBagManager / YJLevelManager, which is what actually
     * restores the inventory, gear and cleared-level records.
     */
    public static void prepareLocalSave(Context ctx) {
        try {
            // 上次玩的是哪个角色，就把哪个角色的档铺到 files/savedata.dat
            try {
                File lr = new File(dir(), "lastrid.txt");
                if (lr.exists()) {
                    String s = new String(readAll(lr), "UTF-8").trim();
                    int rid = Integer.parseInt(s);
                    if (rid > 0) {
                        sCurRid = rid;
                    }
                }
            } catch (Throwable ignored) {
            }
            File store = saveFile();
            if (!store.exists()) {
                return;
            }
            // our own store files are plaintext; files/savedata.dat is
            // base64(DES(json)) exactly like the client writes it
            String raw = Des.utf8(readAll(store));
            if (raw == null || raw.length() < 2) {
                return;
            }
            JSONObject state = new JSONObject(raw);
            // 邮件里的青龙妖圣不再从这份"开机铺盘"的存档里摘掉：
            // 摘了就等于客户端一启动就把它当作"不存在"，下次上传直接写成没有。
            JSONObject wrap = new JSONObject();
            wrap.put("appid", "4399zmxyx");
            wrap.put("appversion", "3.0.2");
            wrap.put("version", "3.0.2");
            JSONObject p = readProfile();
            wrap.put("rid", p != null ? String.valueOf(p.optInt("rid", 1)) : "1");
            wrap.put("roleid", p != null ? p.optInt("roleid", 1) : 1);
            wrap.put("service", 1);
            wrap.put("servicename", "单机服");
            wrap.put("username", p != null ? p.optString("username", "玩家") : "玩家");
            wrap.put("uuid", "64D8CD29FC6B5BEF7F21E4AEA5F7998A");
            wrap.put("modification_time", "2026-01-01 00:00:00");
            wrap.put("cache", state);
            FileOutputStream o = new FileOutputStream(new File(ctx.getFilesDir(), "savedata.dat"));
            o.write(Des.encrypt(Des.bytes(wrap.toString())));
            o.close();
            trace("prepareLocalSave: wrote savedata.dat with cache keys " + sortedKeys(state));
        } catch (Throwable t) {
            trace("prepareLocalSave failed " + t);
        }
    }

    private static String sortedKeys(JSONObject o) {
        java.util.List<String> l = new java.util.ArrayList<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            l.add(it.next());
        }
        java.util.Collections.sort(l);
        return l.toString();
    }

    /**
     * Decrypt files/savedata.dat and return its plain JSON, for libmod.so.
     *
     * The game only keeps the identity fields when it loads this file, so the
     * companion library parses this copy with the game's own JsonCpp and feeds
     * cache.bag / cache.level to the deserializers.
     */
    /** Absolute path of the local-save file written by libmod. */
    public static String localSavePath() {
        File dir = new File(sCtx.getFilesDir(), "offline");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "save.json").getAbsolutePath();
    }

    /** First item object of the stored bag, as JSON, for validation. */
    public static String firstItemJson() {
        try {
            JSONObject root = new JSONObject(Des.utf8(readAll(saveFile())));
            JSONObject bag = root.optJSONObject("bag");
            if (bag == null) {
                return null;
            }
            JSONArray items = bag.optJSONArray("item");
            if (items == null || items.length() == 0) {
                return null;
            }
            return items.getJSONObject(0).toString();
        } catch (Throwable t) {
            trace("firstItemJson failed " + t);
            return null;
        }
    }

    public static String readLocalSaveJson() {
        try {
            // Our own copy (plain text). files/savedata.dat is useless here: the
            // client rewrites it with only the identity fields a few seconds
            // after startup, dropping "cache".
            // Prefer the client's own serialisation (written by libmod at the
            // game's save points); fall back to the old protocol store.
            java.io.File local = new java.io.File(localSavePath());
            if (local.exists()) {
                return Des.utf8(readAll(local));
            }
            File f = saveFile();
            if (!f.exists()) {
                return null;
            }
            return Des.utf8(readAll(f));
        } catch (Throwable t) {
            trace("readLocalSaveJson failed " + t);
            return null;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return bo.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return bo.toByteArray();
    }

    /**
     * Grant exp (and a small drop) for a cleared level, then persist it.
     *
     * Exp is written into the stored roledata so the value survives a restart
     * even if the client ignores the response fields; the response also carries
     * roleexp/rolelv in case it does read them.
     */
    private static void awardVictory(JSONObject req, JSONObject r) {
        try {
            JSONObject store = readSaveObj();
            JSONObject rd = store.optJSONObject("roledata");
            if (rd == null) {
                rd = new JSONObject();
                store.put("roledata", rd);
            }
            JSONObject role = rd.optJSONObject("role");
            if (role == null) {
                role = new JSONObject();
                rd.put("role", role);
            }
            int lv = role.optInt("rolelv", 1);
            int exp = role.optInt("roleexp", 0);
            int gained = 60 + 20 * Math.max(0, lv - 1);
            exp += gained;
            // level curve: 100 exp per level for the first few levels
            while (exp >= 100 * lv && lv < 80) {
                exp -= 100 * lv;
                lv++;
            }
            role.put("rolelv", lv);
            role.put("roleexp", exp);
            writeSave(store.toString());
            r.put("rolelv", lv);
            r.put("roleexp", exp);
            r.put("exp", gained);
            r.put("reward", new JSONArray());
            r.put("itemlimit", new JSONArray());
            trace("awardVictory: lv=" + lv + " exp=" + exp + " (+ " + gained + ")");
        } catch (Throwable t) {
            trace("awardVictory failed " + t);
        }
    }

    private static JSONObject readSaveObj() throws Exception {
        String s = readSave();
        if (s == null || s.length() < 2) {
            return new JSONObject();
        }
        return new JSONObject(s);
    }

    private static void mergeSave(JSONObject r) throws Exception {
        String s = readSave();
        if (s == null || s.length() < 2) {
            return;
        }
        JSONObject store = new JSONObject(s);
        Iterator<String> it = store.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!r.has(k)) {
                r.put(k, store.get(k));
            }
        }
    }

    private static boolean isTransport(String k) {
        return "uuid".equals(k) || "cuid".equals(k) || "cname".equals(k) || "channel".equals(k)
                || "version".equals(k) || "resourceverion".equals(k) || "dataversion".equals(k)
                || "gametime".equals(k) || "lastservicetime".equals(k) || "service".equals(k)
                || "rid".equals(k) || "action".equals(k) || "username".equals(k)
                || "serviceid".equals(k);
    }
}





