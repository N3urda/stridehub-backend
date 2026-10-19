package io.stridehub.app;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Native navigation stays available even when the selected server is unreachable. */
final class BrowserLayout {
    interface Actions {
        void navigate(String path);
        void menu(View anchor);
        void retry();
    }

    final LinearLayout root;
    final WebView web;
    final ProgressBar progress;
    final TextView status;
    final LinearLayout notice;
    final LinearLayout tabs;

    BrowserLayout(Activity activity, ServerAddress server, Actions actions) {
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ConnectionScreen.PAPER);
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(ConnectionScreen.dp(activity, 12), 0, ConnectionScreen.dp(activity, 8), 0);
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(ConnectionScreen.text(activity, "StrideHub", 18, ConnectionScreen.INK));
        TextView host = ConnectionScreen.text(activity, server.baseUrl(), 11, Color.DKGRAY);
        host.setSingleLine(true);
        titles.addView(host);
        toolbar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = button(activity, "更多");
        menu.setContentDescription("更多操作");
        menu.setOnClickListener(actions::menu);
        toolbar.addView(menu, new LinearLayout.LayoutParams(ConnectionScreen.dp(activity, 64), ConnectionScreen.dp(activity, 52)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, ConnectionScreen.dp(activity, 58)));
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, ConnectionScreen.dp(activity, 3)));
        notice = new LinearLayout(activity);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setPadding(ConnectionScreen.dp(activity, 12), 0, 0, 0);
        notice.setBackgroundColor(Color.rgb(255, 244, 215));
        status = ConnectionScreen.text(activity, "", 13, ConnectionScreen.INK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button retry = button(activity, "重试");
        retry.setOnClickListener(v -> actions.retry());
        notice.addView(retry);
        notice.setVisibility(View.GONE);
        root.addView(notice);
        web = new WebView(activity);
        web.setBackgroundColor(ConnectionScreen.PAPER);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        tabs = new LinearLayout(activity);
        String[] names = {"今日", "课表", "运动", "导入"};
        String[] paths = {"/admin/training/today", "/admin/training", "/admin/running", "/admin/upload"};
        for (int i = 0; i < names.length; i++) {
            Button tab = button(activity, names[i]);
            String path = paths[i];
            tab.setOnClickListener(v -> actions.navigate(path));
            tabs.addView(tab, new LinearLayout.LayoutParams(0, ConnectionScreen.dp(activity, 54), 1));
        }
        root.addView(tabs);
    }

    void error(String message) {
        progress.setVisibility(View.INVISIBLE);
        status.setText(message);
        notice.setVisibility(View.VISIBLE);
    }

    private Button button(Activity activity, String name) {
        Button button = new Button(activity);
        button.setText(name);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(ConnectionScreen.GREEN);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }
}
