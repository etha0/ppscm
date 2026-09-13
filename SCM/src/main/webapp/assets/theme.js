// Apply the saved theme before styles render to avoid a bright flash on navigation.
(() => {
    'use strict';
    const key = 'scm.theme';
    let preference = null;
    try { preference = localStorage.getItem(key); } catch (_) {}
    const system = window.matchMedia('(prefers-color-scheme: dark)');
    const valid = value => value === 'light' || value === 'dark';
    function apply() {
        const dark = valid(preference) ? preference === 'dark' : system.matches;
        document.documentElement.dataset.theme = dark ? 'dark' : 'light';
        document.querySelectorAll('[data-theme-toggle]').forEach(button => {
            button.setAttribute('aria-pressed', String(dark));
            button.title = dark ? '밝은 모드로 전환' : '다크 모드로 전환';
        });
    }
    apply();
    document.addEventListener('DOMContentLoaded', apply);
    document.addEventListener('click', event => {
        if (!event.target.closest('[data-theme-toggle]')) return;
        preference = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
        try { localStorage.setItem(key, preference); } catch (_) {}
        apply();
    });
    window.addEventListener('storage', event => {
        if (event.key === key || event.key === null) {
            preference = event.newValue;
            apply();
        }
    });
    if (system.addEventListener) system.addEventListener('change', apply);
})();
