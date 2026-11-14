package cn.m4399.operate;

import android.content.Context;

/** Working replacement for the stub OperateCenterConfig (the stub's Builder
 *  methods return null, which is what crashes init4399SDKPlatform). */
public class OperateCenterConfig {
    private boolean bo;
    private boolean bp;
    private String bq;
    private PopLogoStyle br;
    private PopWinPosition bs;
    private Context mAppContext;
    private int mOrientation;

    public enum PopLogoStyle {
        POPLOGOSTYLE_ONE, POPLOGOSTYLE_TWO, POPLOGOSTYLE_THREE, POPLOGOSTYLE_FOUR
    }

    public enum PopWinPosition {
        POS_LEFT, POS_BOTTOM, POS_RIGHT, POS_TOP
    }

    public OperateCenterConfig(Builder builder) {
        this.mAppContext = builder.appContext;
        this.bo = builder.debugEnabled;
        this.mOrientation = builder.orientation;
        this.bp = builder.supportExcess;
        this.bq = builder.gameKey;
        this.br = builder.popLogoStyle;
        this.bs = builder.popWinPosition;
    }

    public boolean isDebugEnabled() { return bo; }
    public Context getAppContext() { return mAppContext; }
    public int getOrientation() { return mOrientation; }
    public boolean isSupporExcess() { return bp; }
    public PopWinPosition getPopWinPosition() { return bs; }
    public int getPopLogoStyle() { return 0; }
    public String getGameKey() { return bq; }

    public String toString() {
        return "OperateCenterConfig{gameKey=" + bq + "}";
    }

    public static class Builder {
        Context appContext;
        boolean debugEnabled;
        String gameKey;
        int orientation;
        PopLogoStyle popLogoStyle;
        PopWinPosition popWinPosition;
        boolean supportExcess;

        private void initDefaultValues() {
            this.orientation = 1;              // landscape
            this.gameKey = "";
            this.popLogoStyle = PopLogoStyle.POPLOGOSTYLE_ONE;
            this.popWinPosition = PopWinPosition.POS_TOP;
            this.debugEnabled = false;
            this.supportExcess = false;
        }

        public OperateCenterConfig build() {
            return new OperateCenterConfig(this);
        }

        public Builder setOrientation(int i) {
            this.orientation = i;
            return this;
        }

        public Builder setDebugEnabled(boolean z) {
            this.debugEnabled = z;
            return this;
        }

        public Builder setSupportExcess(boolean z) {
            this.supportExcess = z;
            return this;
        }

        public Builder setPopLogoStyle(PopLogoStyle popLogoStyle) {
            this.popLogoStyle = popLogoStyle;
            return this;
        }

        public Builder setGameKey(String str) {
            this.gameKey = str;
            return this;
        }

        public Builder setPopWinPosition(PopWinPosition popWinPosition) {
            this.popWinPosition = popWinPosition;
            return this;
        }

        public Builder(Context context) {
            initDefaultValues();
            this.appContext = context.getApplicationContext();
            if (this.appContext == null) {
                this.appContext = context;
            }
        }
    }
}
