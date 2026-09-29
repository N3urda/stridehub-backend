package io.stridehub.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native connection controls; the password is never saved into a Bundle or preferences. */
final class ConnectionScreen {
    interface Listener {
        void connect(String url, String username, String password, boolean allowHttp, boolean remember);
    }

    static final int INK = Color.rgb(28, 48, 39);
    static final int GREEN = Color.rgb(33, 94, 75);
    static final int PAPER = Color.rgb(244, 246, 240);

    static View create(Activity activity, String url, String username, boolean http, boolean remember,
                       String message, Listener listener) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 24), dp(activity, 28), dp(activity, 24), dp(activity, 24));
        scroll.addView(body);

        TextView mark = text(activity, "S", 30, GREEN);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(null, Typeface.BOLD);
        mark.setBackground(rounded(Color.rgb(228, 237, 208), dp(activity, 18)));
        body.addView(mark, new LinearLayout.LayoutParams(dp(activity, 60), dp(activity, 60)));
        TextView title = text(activity, "StrideHub", 32, INK);
        title.setTypeface(null, Typeface.BOLD);
        add(body, title, 20);
        add(body, text(activity, "把训练和身体状态放在一起", 16, INK), 4);
        add(body, text(activity, "连接你的服务，继续今天的安排。", 14, Color.DKGRAY), 8);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18));
        card.setBackground(rounded(Color.WHITE, dp(activity, 22)));
        add(body, card, 26);

        EditText address = field(activity, card, "服务地址", "https://stridehub.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(url);
        EditText account = field(activity, card, "用户名", "后端管理员用户名", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        account.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        account.setText(username);
        EditText password = field(activity, card, "密码", "后端管理员密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_GO);
        CheckBox stay = new CheckBox(activity);
        stay.setText("保持登录 7 天");
        stay.setTextSize(14);
        stay.setChecked(remember);
        add(card, stay, 8);
        CheckBox allow = new CheckBox(activity);
        allow.setText(R.string.allow_http);
        allow.setTextSize(14);
        allow.setChecked(http);
        add(card, allow, 0);
        add(card, text(activity, "HTTP 不加密账号和数据，公网请使用 HTTPS。", 12, Color.DKGRAY), 4);

        TextView status = text(activity, message == null ? "" : message, 14, Color.rgb(153, 49, 40));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
        add(card, status, 12);

        Button connect = new Button(activity);
        connect.setText("连接并登录");
        connect.setTextColor(Color.WHITE);
        connect.setTextSize(16);
        connect.setAllCaps(false);
        connect.setBackground(rounded(GREEN, dp(activity, 14)));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(activity, 52));
        buttonParams.topMargin = dp(activity, 18);
        card.addView(connect, buttonParams);
        Runnable submit = () -> {
            try {
                ServerAddress.parse(address.getText().toString(), allow.isChecked());
                if (account.getText().toString().trim().isEmpty() || password.length() == 0) {
                    throw new IllegalArgumentException("请填写用户名和密码。");
                }
                String secret = password.getText().toString();
                password.setText("");
                listener.connect(address.getText().toString(), account.getText().toString().trim(), secret, allow.isChecked(), stay.isChecked());
            } catch (IllegalArgumentException error) {
                status.setText(error.getMessage());
                status.setVisibility(View.VISIBLE);
            }
        };
        connect.setOnClickListener(v -> submit.run());
        password.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            submit.run();
            return true;
        });
        add(body, text(activity, "使用和网页版相同的账号。密码不会保存在 APK 中。", 13, Color.DKGRAY), 20);
        add(body, text(activity, "手机上的 localhost 指手机本身。连接电脑时，请使用手机可访问的域名或电脑局域网 IP。", 13, Color.DKGRAY), 8);
        return scroll;
    }

    private static EditText field(Activity activity, LinearLayout parent, String label, String hint, int type) {
        TextView caption = text(activity, label, 14, INK);
        caption.setTypeface(null, Typeface.BOLD);
        add(parent, caption, parent.getChildCount() == 0 ? 0 : 14);
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setContentDescription(label);
        input.setHint(hint);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        input.setBackground(rounded(PAPER, dp(activity, 10)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(activity, 52));
        params.topMargin = dp(activity, 6);
        parent.addView(input, params);
        return input;
    }

    static TextView text(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    static int dp(Activity activity, int size) {
        return Math.round(size * activity.getResources().getDisplayMetrics().density);
    }

    private static void add(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Math.round(margin * parent.getResources().getDisplayMetrics().density);
        parent.addView(view, params);
    }
}
