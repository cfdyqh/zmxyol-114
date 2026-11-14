package cn.m4399.operate;

/** Working replacement for the stub 4399 User model shipped in the v8 dex. */
public final class User {
    private boolean activated;
    private boolean idChecked;
    private String name;
    private String nick;
    private int phoneBound;
    private String phoneNumber;
    private String state;
    private String suid;
    private String uid;
    private int vipState;

    public User() {
        this.uid = "";
        this.name = "";
        this.nick = "";
        this.state = "";
    }

    public User(String str, String str2, String str3, String str4, String str5, boolean z,
                int i, boolean z2, String str6, int i2) {
        this.uid = str;
        this.suid = str2;
        this.name = str3;
        this.nick = str4;
        this.state = str5;
        this.activated = z;
        this.phoneBound = i;
        this.idChecked = z2;
        this.phoneNumber = str6;
        this.vipState = i2;
    }

    public String getUid() {
        return uid;
    }

    public String toString() {
        return "User{uid=" + uid + ", name=" + name + "}";
    }

    protected String getSuid() {
        return suid;
    }

    public String getName() {
        return name;
    }

    public String getNick() {
        return nick;
    }

    public String getState() {
        return state;
    }

    public boolean isActivated() {
        return activated;
    }

    public boolean isIdChecked() {
        return idChecked;
    }

    public int getPhoneBound() {
        return phoneBound;
    }

    public int getVipState() {
        return vipState;
    }
}
