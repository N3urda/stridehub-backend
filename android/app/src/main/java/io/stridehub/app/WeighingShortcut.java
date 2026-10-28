package io.stridehub.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import java.util.Collections;

/** A launcher shortcut opens only the local weighing page; no URL or account input is accepted. */
public final class WeighingShortcut {
    public static final String ACTION = "io.stridehub.app.action.WEIGH";
    private WeighingShortcut() { }
    public static void register(Activity activity) {
        ShortcutManager manager = activity.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        try {
            ShortcutInfo shortcut = new ShortcutInfo.Builder(activity, "weigh")
                .setShortLabel("称重").setLongLabel("打开称重，自动寻找我的秤")
                .setIcon(Icon.createWithResource(activity, R.mipmap.ic_launcher))
                .setIntent(new Intent(activity, MainActivity.class).setAction(ACTION))
                .build();
            manager.setDynamicShortcuts(Collections.singletonList(shortcut));
        } catch (IllegalStateException | SecurityException ignored) { /* Launcher shortcuts are optional. */ }
    }
}
