package org.yjmobile.zmxy.offline;

/** Canned responses taken verbatim from a known-working single-player build.
 *
 *  A reference mod (造梦西游单机版.apk) ships a local server in its
 *  classes2.dex whose payloads make the retail client build skills, levels and
 *  inventory correctly. Those exact bodies are used here so the client takes its
 *  normal code paths instead of us patching around missing data.
 */
final class Canned {
    private Canned() {
    }

    /** login: services + rolelist + levelid/lvdata/spotinfo */
    static final String LOGIN = "{\"code\":1000,\"state\":0,\"time\":1730000000,\"lastrid\":\"1001\",\"service\":1,\"rid\":1001,\"roleid\":1,\"rolelv\":1,\"levelid\":700001,\"levelnum\":1,\"mode\":4,\"price\":0,\"spirit\":0,\"isusehire\":false,\"name\":\"\",\"bindmsg\":\"\",\"msg\":\"\",\"lvdata\":{},\"spotinfo\":{},\"rolelist\":[],\"services\":[{\"service\":1,\"name\":\"local\",\"createtime\":0,\"starttime\":0,\"endtime\":0,\"new\":0,\"state\":1,\"rolelist\":[{\"rid\":1001,\"roleid\":1,\"username\":\"Wukong\",\"rolelv\":1,\"rolelevel\":1,\"level\":1,\"exp\":0,\"vip\":0,\"wingid\":0,\"suitid\":0,\"weaponid\":0}]}]}";

    /** level clear: level/list/drops/dropsarr/result/win/passed/star */
    static final String VICTORY = "{\"code\":1000,\"state\":0,\"time\":1730000000,\"lastrid\":\"1001\",\"service\":1,\"rid\":1001,\"roleid\":1,\"rolelv\":1,\"levelid\":700001,\"levelnum\":1,\"mode\":4,\"price\":0,\"spirit\":0,\"isusehire\":false,\"name\":\"\",\"bindmsg\":\"\",\"msg\":\"\",\"lvdata\":{},\"spotinfo\":{},\"rolelist\":[],\"services\":[{\"service\":1,\"name\":\"local\",\"createtime\":0,\"starttime\":0,\"endtime\":0,\"new\":0,\"state\":1,\"rolelist\":[{\"rid\":1001,\"roleid\":1,\"username\":\"Wukong\",\"rolelv\":1,\"rolelevel\":1,\"level\":1,\"exp\":0,\"vip\":0,\"wingid\":0,\"suitid\":0,\"weaponid\":0}]}],\"level\":{\"id\":700001,\"star\":3,\"pass\":1,\"exp\":0,\"money\":0,\"drops\":[{\"dataid\":4300,\"A2\":4300,\"A4\":1,\"A5\":4399,\"A6\":0,\"A9\":8,\"AQ\":0,\"AT\":0,\"AV\":0,\"AW\":0.0,\"B7\":0,\"BG\":[{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0}]}],\"firstpass\":true},\"list\":[{\"dataid\":4300,\"A2\":4300,\"A4\":1,\"A5\":4399,\"A6\":0,\"A9\":8,\"AQ\":0,\"AT\":0,\"AV\":0,\"AW\":0.0,\"B7\":0,\"BG\":[{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0}]}],\"message\":\"\",\"point\":0,\"result\":1,\"win\":true,\"passed\":true,\"star\":3,\"drops\":[{\"dataid\":4300,\"A2\":4300,\"A4\":1,\"A5\":4399,\"A6\":0,\"A9\":8,\"AQ\":0,\"AT\":0,\"AV\":0,\"AW\":0.0,\"B7\":0,\"BG\":[{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0}]}],\"dropsarr\":[{\"dataid\":4300,\"A2\":4300,\"A4\":1,\"A5\":4399,\"A6\":0,\"A9\":8,\"AQ\":0,\"AT\":0,\"AV\":0,\"AW\":0.0,\"B7\":0,\"BG\":[{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0},{\"BH\":-1,\"BI\":0,\"BJ\":0}]}],\"dropinfo\":[],\"exp\":0,\"money\":0}";

    /** bag opened: kinds/item/stone/petitem/fashion */
    static final String BAG = "{\"code\":1000,\"point\":0,\"kinds\":[],\"item\":[],\"stone\":[],\"petitem\":[],\"fashion\":[]}";
}
