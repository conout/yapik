package com.example.myapplication.storage;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import com.example.myapplication.R;

public final class ThemeStore {

    private static final String PREFERENCES = "appearance";
    private static final String DARK_THEME = "dark_theme";

    private ThemeStore() {
    }

    public static Context wrap(Context context) {
        boolean dark = isDark(context);
        Configuration configuration = new Configuration(
                context.getResources().getConfiguration()
        );
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO);
        return context.createConfigurationContext(configuration);
    }

    public static void apply(Activity activity) {
        activity.setTheme(R.style.Theme_MyApplication);
    }

    public static boolean isDark(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(DARK_THEME, false);
    }

    public static void setDark(Context context, boolean dark) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(DARK_THEME, dark)
                .apply();
    }
}
