(() => {
    'use strict';
    const address = document.getElementById('android-server-address');
    const button = document.getElementById('android-copy-address');
    const status = document.getElementById('android-copy-status');
    if (!address || !button || !status) return;
    button.hidden = false;
    button.addEventListener('click', async () => {
        let copied = false;
        try {
            if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(address.value); copied = true; }
        } catch { /* HTTP or browser permission may require manual selection below. */ }
        if (!copied) {
            address.focus(); address.select(); address.setSelectionRange(0, address.value.length);
            try { copied = document.execCommand('copy'); } catch { /* Selected address remains available for manual copying. */ }
        }
        button.textContent = copied ? '已复制' : '重新复制';
        status.textContent = copied ? '地址已复制，可粘贴到 App 的“服务地址”。' : '地址已选中。请长按选择“复制”，或按 Ctrl+C / ⌘C。';
        if (copied) button.focus();
    });
})();
