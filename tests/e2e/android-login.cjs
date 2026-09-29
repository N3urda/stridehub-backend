/* Tests the APK's actual Java-generated LoginScript against a disposable backend.
 * Real HTTP login tests never mock routes. A separate synthetic section checks
 * malicious/malformed page boundaries; it is not evidence of backend behavior.
 * Requires JAVA_HOME, STRIDEHUB_TEST_URL=http://localhost:8082, Playwright/Chromium.
 * Credentials are read from an ignored file and passed to Java on stdin only.
 */
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = process.env.STRIDEHUB_TEST_URL;
assert.equal(base, 'http://localhost:8082', 'Explicit disposable STRIDEHUB_TEST_URL required');
const root = process.cwd();
assert.ok(fs.existsSync(path.join(root, 'var/runtime/android-e2e-database/dreeve.db')), 'Dedicated Android test database required');
assert.ok(process.env.JAVA_HOME, 'Set JAVA_HOME to a Java 17+ JDK');
const credentials = JSON.parse(fs.readFileSync(process.env.TRAINING_CREDENTIALS_FILE || 'var/runtime/credentials.json', 'utf8'));
const harnessDir = path.join(root, 'var/runtime/android-login-java');
fs.mkdirSync(harnessDir, {recursive: true, mode: 0o700});
const harness = `import io.stridehub.app.LoginScript;
import io.stridehub.app.ServerAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public class TestLoginScript {
    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String username = new String(Base64.getDecoder().decode(input.readLine()), StandardCharsets.UTF_8);
        String password = new String(Base64.getDecoder().decode(input.readLine()), StandardCharsets.UTF_8);
        System.out.print(LoginScript.build(ServerAddress.parse(args[0], true), username, password, Boolean.parseBoolean(args[1])));
    }
}`;
fs.writeFileSync(path.join(harnessDir, 'TestLoginScript.java'), harness, {mode: 0o600});
const source = path.join(root, 'android/app/src/main/java/io/stridehub/app');
const compile = spawnSync(path.join(process.env.JAVA_HOME, 'bin/javac'), ['-encoding', 'UTF-8', '-d', harnessDir,
    path.join(source, 'ServerAddress.java'), path.join(source, 'LoginScript.java'), path.join(harnessDir, 'TestLoginScript.java')], {encoding: 'utf8'});
assert.equal(compile.status, 0, 'Java login harness compilation failed: ' + compile.stderr);
function script(url, username, password, remember = true) {
    const result = spawnSync(path.join(process.env.JAVA_HOME, 'bin/java'), ['-cp', harnessDir, 'TestLoginScript', url, String(remember)], {
        input: Buffer.from(username).toString('base64') + '\n' + Buffer.from(password).toString('base64') + '\n', encoding: 'utf8'
    });
    assert.equal(result.status, 0, 'Java LoginScript builder failed');
    return result.stdout;
}
let browser;
let passed = 0;
function report(name) { passed++; console.log('PASS ' + name); }
const screenshotDir = path.join(root, 'var/runtime/android-screenshots');
fs.mkdirSync(screenshotDir, {recursive: true});

async function realLogin(password, remember) {
    const context = await browser.newContext({viewport: {width: 390, height: 844}});
    const page = await context.newPage();
    const requests = [];
    page.on('request', request => requests.push(request));
    await page.goto(base + '/admin/login');
    const response = page.waitForResponse(res => res.request().method() === 'POST' && new URL(res.url()).pathname === '/admin/login');
    // If Java generation fails before submitting, the pending listener must not create an unhandled rejection.
    response.catch(() => {});
    const result = await page.evaluate(script(base, credentials.username, password, remember));
    assert.equal(result.status, 'submitted');
    const post = await response;
    assert.equal(post.status(), 302, 'The real form must use the backend redirect flow');
    const payload = new URLSearchParams(post.request().postData());
    assert.ok(payload.get('_csrf_token'));
    assert.ok(payload.get('_username') === credentials.username, 'Username submitted exactly');
    assert.ok(payload.get('_password') === password, 'Password submitted exactly');
    assert.equal(Boolean(payload.get('_remember_me')), remember);
    assert.equal(payload.get('_target_path'), base + '/admin/training/today');
    for (const request of requests) {
        assert.ok(!request.url().includes(encodeURIComponent(password)), 'Credentials must not enter request URLs');
    }
    return {page, context};
}

async function syntheticChecks() {
    const fixtureBase = 'http://127.0.0.1:8189';
    const context = await browser.newContext();
    const page = await context.newPage();
    let html = '';
    let postCount = 0;
    await context.route('**/*', async route => {
        if (route.request().method() === 'POST') postCount++;
        await route.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: html});
    });
    const form = (action = '/admin/login', fields = '<input name="_username" type="text"><input name="_password" type="password"><input name="_csrf_token" type="hidden" value="fixture-token">', extra = '') =>
        '<!doctype html><html><body><form method="post" action="' + action + '" ' + extra + '>' + fields + '<button type="submit">登录</button></form></body></html>';
    const sensitive = ['runner\"<script>window.credentialCodeRan=true</script>\\汉字\n\u2028', 'pass\";window.credentialCodeRan=true;//\\&+=\n\u2029'];
    const built = script(fixtureBase, ...sensitive, false);
    const cases = [
        ['cross-origin action', form('https://outside.invalid/admin/login'), fixtureBase + '/admin/login', 'untrusted_form'],
        ['same-origin wrong action', form('/admin/other'), fixtureBase + '/admin/login', 'untrusted_form'],
        ['unexpected page', form(), fixtureBase + '/admin/unexpected', 'untrusted_page'],
        ['wrong origin page', form(), 'http://localhost:8189/admin/login', 'untrusted_page'],
        ['missing CSRF', form('/admin/login', '<input name="_username" type="text"><input name="_password" type="password">'), fixtureBase + '/admin/login', null],
        ['empty CSRF', form().replace('value="fixture-token"', 'value=""'), fixtureBase + '/admin/login', null],
        ['GET form', form().replace('method="post"', 'method="get"'), fixtureBase + '/admin/login', 'untrusted_form'],
        ['password text field', form().replace('type="password"', 'type="text"'), fixtureBase + '/admin/login', null],
        ['query on login URL', form(), fixtureBase + '/admin/login?next=other', 'untrusted_page'],
        ['no login form', '<!doctype html><p>Maintenance</p>', fixtureBase + '/admin/login', 'form_not_found']
    ];
    for (const [name, body, url, expected] of cases) {
        html = body;
        await page.goto(url);
        const result = await page.evaluate(built);
        if (expected) assert.equal(result.status, expected, name);
        else assert.notEqual(result.status, 'submitted', name);
        assert.equal(postCount, 0, name + ' must not send credentials');
        assert.equal(await page.evaluate(() => Boolean(window.credentialCodeRan)), false);
        assert.equal(await page.locator('input[name="_password"]').count() ? await page.locator('input[name="_password"]').inputValue() : '', '', name + ' must not fill password');
    }
    report('synthetic boundary: foreign action/origin, wrong route/method, missing CSRF/fields, and unexpected HTML cause no credential POST');
    html = form();
    await page.goto(fixtureBase + '/admin/login');
    await page.evaluate(() => document.querySelector('form').addEventListener('submit', event => event.preventDefault()));
    assert.equal((await page.evaluate(built)).status, 'submitted');
    // HTML single-line inputs normalize CR/LF by definition; other characters must survive.
    assert.ok(await page.locator('[name="_username"]').inputValue() === sensitive[0].replace(/[\r\n]/g, ''), 'Special username preserved under HTML input normalization');
    assert.ok(await page.locator('[name="_password"]').inputValue() === sensitive[1].replace(/[\r\n]/g, ''), 'Special password preserved under HTML input normalization');
    assert.equal(await page.evaluate(() => Boolean(window.credentialCodeRan)), false);
    assert.equal(postCount, 0, 'Synthetic submit is deliberately intercepted locally');
    report('synthetic quoting: quotes, slashes, markup, Unicode and line separators remain data; CR/LF follows HTML input normalization');
    html = form('/stridehub/admin/login');
    await page.goto(fixtureBase + '/stridehub/admin/login');
    await page.evaluate(() => document.querySelector('form').addEventListener('submit', event => event.preventDefault()));
    assert.equal((await page.evaluate(script(fixtureBase + '/stridehub/', 'demo-runner', 'demo-pass'))).status, 'submitted');
    assert.equal(await page.locator('[name="_target_path"]').inputValue(), fixtureBase + '/stridehub/admin/training/today');
    assert.equal(postCount, 0);
    report('synthetic installation path: login and today target retain configured server subdirectory');
    await context.close();
}

(async () => {
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE || undefined});
    const valid = await realLogin(credentials.password, true);
    await valid.page.waitForURL(base + '/admin/training/today');
    await valid.page.locator('#today-date').waitFor();
    const today = await valid.page.evaluate(async () => ({status: (await fetch('/admin/training/api/today')).status}));
    assert.equal(today.status, 200);
    const storage = await valid.page.evaluate(() => JSON.stringify({local: {...localStorage}, session: {...sessionStorage}}));
    assert.ok(!storage.includes(credentials.password), 'Login must not copy password into Web Storage');
    assert.ok(!storage.includes(credentials.apiKey), 'Web session must not expose the API key');
    assert.ok(!JSON.stringify(await valid.context.cookies()).includes(credentials.password), 'Cookie is an authenticated session, not a stored password');
    await valid.page.screenshot({path: path.join(screenshotDir, 'browser-today.png'), fullPage: true});
    report('real backend: Java login script uses CSRF, authenticated cookies and today redirect with no password in Web Storage');
    await valid.context.close();
    const invalid = await realLogin('ANDROID-E2E-deliberately-incorrect-password', false);
    await invalid.page.locator('.border-red-200').waitFor();
    assert.equal(new URL(invalid.page.url()).pathname, '/admin/login');
    const rejected = await invalid.context.request.get(base + '/admin/training/api/today', {maxRedirects: 0});
    assert.equal(rejected.status(), 302);
    assert.equal(await invalid.page.locator('[name="_password"]').inputValue(), '');
    report('real backend: incorrect password remains unauthenticated and server clears password input');
    await invalid.context.close();
    await syntheticChecks();
    console.log(JSON.stringify({passed, result: 'PASS', coverage: '2 real HTTP login groups + 3 synthetic security/quoting/subdirectory groups; not Android emulator evidence'}));
})().catch(error => {
    // Avoid printing evaluated JavaScript or credential-containing payloads in failures.
    let message = error.message || String(error);
    for (const secret of [credentials.password, credentials.apiKey, credentials.username].filter(Boolean)) message = message.split(secret).join('[redacted]');
    console.error(message);
    process.exitCode = 1;
}).finally(async () => { if (browser) await browser.close(); });
