package io.stridehub.app;

/** One-shot login into the configured server's existing CSRF-protected form. */
public final class LoginScript {
    private LoginScript() { }

    public static String build(ServerAddress address, String username, String password) {
        return build(address, username, password, true);
    }

    public static String build(ServerAddress address, String username, String password, boolean remember) {
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("请输入用户名和密码");
        }
        return "(() => {\n" + guard(address, false) + "\n"
            + "const username = " + jsonString(username) + ";\n"
            + "const password = " + jsonString(password) + ";\n"
            + "const remember = " + remember + ";\n"
            + "const targetUrl = " + jsonString(address.route("/admin/training/today")) + ";\n"
            + "let target = fields('_target_path');\n"
            + "let remembered = fields('_remember_me');\n"
            + "if (target.length > 1 || remembered.length > 1 || (target.length && !valid(target[0], 'hidden')) "
            + "|| (remembered.length && !valid(remembered[0], 'checkbox'))) return {status:'untrusted_form'};\n"
            + "if (!target.length) { const field = document.createElement('input'); field.type = 'hidden'; "
            + "field.name = '_target_path'; form.appendChild(field); target = [field]; }\n"
            + "target[0].value = targetUrl;\n"
            + "if (remembered.length) { remembered[0].value = 'on'; remembered[0].checked = remember; }\n"
            + "else if (remember) { const field = document.createElement('input'); field.type = 'hidden'; "
            + "field.name = '_remember_me'; field.value = 'on'; form.appendChild(field); }\n"
            + "user[0].value = username; pass[0].value = password;\n"
            + "if (typeof HTMLFormElement.prototype.requestSubmit === 'function') { "
            + "HTMLFormElement.prototype.requestSubmit.call(form); } "
            + "else { HTMLFormElement.prototype.submit.call(form); }\n"
            + "return {status:'submitted'};\n})()";
    }

    /** A non-mutating probe. away_from_login is a location result, never proof of authentication. */
    public static String probe(ServerAddress address) {
        return "(() => {\n" + guard(address, true) + "\nreturn {status:'login_form'};\n})()";
    }

    private static String guard(ServerAddress address, boolean allowAway) {
        if (address == null) throw new IllegalArgumentException("请输入服务器地址");
        return "const expected = new URL(" + jsonString(address.route("/admin/login")) + ");\n"
            + "const base = new URL(" + jsonString(address.baseUrl()) + ");\n"
            + "const current = new URL(window.location.href);\n"
            + "const basePath = base.pathname === '/' ? '' : base.pathname;\n"
            + "if (current.origin !== base.origin || current.username || current.password "
            + "|| !(current.pathname === basePath || current.pathname.startsWith(basePath + '/'))) "
            + "return {status:'untrusted_page'};\n"
            + "if (current.href !== expected.href) return {status:'"
            + (allowAway ? "away_from_login" : "untrusted_page") + "'};\n"
            + "const candidates = Array.from(document.forms).filter(f => Array.from(f.elements)"
            + ".some(e => e.name === '_username' || e.name === '_password'));\n"
            + "if (!candidates.length) return {status:'form_not_found'};\n"
            + "if (candidates.length !== 1) return {status:'untrusted_form'};\n"
            + "const form = candidates[0];\n"
            + "let action; try { action = new URL(form.getAttribute('action') || current.href, document.baseURI); } "
            + "catch (_) { return {status:'untrusted_form'}; }\n"
            + "if (action.href !== expected.href || (form.getAttribute('method') || 'get').toLowerCase() !== 'post' "
            + "|| !['', '_self'].includes(form.getAttribute('target') || '')) return {status:'untrusted_form'};\n"
            + "const fields = name => Array.from(form.elements).filter(e => e.name === name);\n"
            + "const valid = (field, type) => field instanceof HTMLInputElement && field.form === form "
            + "&& field.type === type && !field.disabled;\n"
            + "const user = fields('_username'), pass = fields('_password'), csrf = fields('_csrf_token');\n"
            + "if (user.length !== 1 || pass.length !== 1 || csrf.length !== 1 "
            + "|| !valid(user[0], 'text') || !valid(pass[0], 'password') || !valid(csrf[0], 'hidden') "
            + "|| !csrf[0].value.trim()) return {status:'untrusted_form'};";
    }

    /** JSON literal escaping also covers script delimiters and invalid UTF-16 inputs. */
    static String jsonString(String value) {
        if (value == null) throw new IllegalArgumentException("字符串不能为空");
        StringBuilder result = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x7f || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029
                        || (Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(i + 1))))) {
                        appendUnicodeEscape(result, c);
                    } else {
                        result.append(c);
                        if (Character.isHighSurrogate(c)) result.append(value.charAt(++i));
                    }
            }
        }
        return result.append('"').toString();
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        final char[] hex = "0123456789abcdef".toCharArray();
        out.append("\\u").append(hex[(c >> 12) & 15]).append(hex[(c >> 8) & 15])
            .append(hex[(c >> 4) & 15]).append(hex[c & 15]);
    }
}
