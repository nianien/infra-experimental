(function (window, document) {
    const state = {
        currentUser: null,
        confirmCallback: null,
        eventsBound: false,
        stylesInjected: false
    };

    const templates = {
        loginModal: `
            <div id="login-modal" class="auth-modal" role="dialog" aria-modal="true">
                <div class="auth-modal__panel auth-modal__panel--sm">
                    <div class="auth-modal__header">
                        <h3>用户登录</h3>
                        <button class="auth-modal__close" data-auth-close="login">&times;</button>
                    </div>
                    <div id="login-alert" class="auth-alert"></div>
                    <form id="login-form">
                        <div class="auth-field">
                            <label for="login-username">用户名 *</label>
                            <input type="text" id="login-username" required placeholder="请输入用户名" autocomplete="username">
                        </div>
                        <div class="auth-field">
                            <label for="login-password">密码 *</label>
                            <input type="password" id="login-password" required placeholder="请输入密码" autocomplete="current-password">
                        </div>
                        <div class="auth-modal__actions">
                            <button type="button" class="auth-btn auth-btn--secondary" data-auth-close="login">取消</button>
                            <button type="submit" class="auth-btn auth-btn--primary">登录</button>
                        </div>
                    </form>
                </div>
            </div>
        `,
        confirmModal: `
            <div id="confirm-modal" class="auth-modal" role="dialog" aria-modal="true">
                <div class="auth-modal__panel auth-modal__panel--sm">
                    <div class="auth-modal__header">
                        <h3 id="confirm-modal-title">确认</h3>
                        <button class="auth-modal__close" data-auth-close="confirm">&times;</button>
                    </div>
                    <div id="confirm-modal-message" class="auth-modal__body">确定要执行此操作吗？</div>
                    <div class="auth-modal__actions">
                        <button type="button" class="auth-btn auth-btn--secondary" data-auth-close="confirm">取消</button>
                        <button type="button" class="auth-btn auth-btn--primary" id="confirm-modal-btn">确认</button>
                    </div>
                </div>
            </div>
        `,
        userMenuModal: `
            <div id="user-menu-modal" class="auth-modal" role="dialog" aria-modal="true">
                <div class="auth-modal__panel auth-modal__panel--sm">
                    <div class="auth-modal__header">
                        <div>
                            <h3 style="margin-bottom: 4px;">账号中心</h3>
                            <p id="user-menu-username" class="auth-muted-text"></p>
                        </div>
                        <button class="auth-modal__close" data-auth-close="user-menu">&times;</button>
                    </div>
                    <div class="auth-menu">
                        <button type="button" class="auth-btn auth-btn--ghost" id="open-change-password">修改密码</button>
                        <button type="button" class="auth-btn auth-btn--danger" id="user-menu-logout">登出</button>
                    </div>
                </div>
            </div>
        `,
        changePasswordModal: `
            <div id="change-password-modal" class="auth-modal" role="dialog" aria-modal="true">
                <div class="auth-modal__panel auth-modal__panel--sm">
                    <div class="auth-modal__header">
                        <h3>修改密码</h3>
                        <button class="auth-modal__close" data-auth-close="change-password">&times;</button>
                    </div>
                    <div id="change-password-alert" class="auth-alert"></div>
                    <form id="change-password-form">
                        <div class="auth-field">
                            <label for="current-password">当前密码 *</label>
                            <input type="password" id="current-password" required placeholder="请输入当前密码" autocomplete="current-password">
                        </div>
                        <div class="auth-field">
                            <label for="new-password">新密码 *</label>
                            <input type="password" id="new-password" required placeholder="请输入新密码" autocomplete="new-password">
                        </div>
                        <div class="auth-field">
                            <label for="confirm-password">确认新密码 *</label>
                            <input type="password" id="confirm-password" required placeholder="请再次输入新密码" autocomplete="new-password">
                        </div>
                        <div class="auth-modal__actions">
                            <button type="button" class="auth-btn auth-btn--secondary" data-auth-close="change-password">取消</button>
                            <button type="submit" class="auth-btn auth-btn--primary">保存</button>
                        </div>
                    </form>
                </div>
            </div>
        `
    };

    const styleContent = `
        .auth-modal {
            display: none;
            position: fixed;
            inset: 0;
            background: rgba(0,0,0,0.45);
            backdrop-filter: blur(6px);
            z-index: 2000;
            align-items: center;
            justify-content: center;
            padding: 24px;
        }
        .auth-modal.active {
            display: flex;
        }
        .auth-modal__panel {
            background: #fff;
            border-radius: 18px;
            padding: 28px;
            width: 100%;
            max-height: 90vh;
            overflow-y: auto;
            box-shadow: 0 20px 40px rgba(15, 23, 42, 0.2);
            border: 1px solid #f1f5f9;
            position: relative;
        }
        .auth-modal__panel--sm {
            max-width: 420px;
        }
        .auth-modal__header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            margin-bottom: 18px;
        }
        .auth-modal__header h3 {
            margin: 0;
            font-size: 20px;
            color: #0f172a;
        }
        .auth-modal__close {
            border: none;
            background: #f1f5f9;
            border-radius: 50%;
            width: 32px;
            height: 32px;
            font-size: 18px;
            cursor: pointer;
            color: #64748b;
            transition: all 0.2s ease;
        }
        .auth-modal__close:hover {
            background: #e2e8f0;
            color: #0f172a;
        }
        .auth-field {
            display: flex;
            flex-direction: column;
            gap: 6px;
            margin-bottom: 18px;
        }
        .auth-field label {
            font-size: 14px;
            color: #0f172a;
            font-weight: 500;
        }
        .auth-field input {
            border: 1px solid #e2e8f0;
            border-radius: 10px;
            padding: 10px 14px;
            font-size: 14px;
            transition: border-color 0.2s ease, box-shadow 0.2s ease;
        }
        .auth-field input:focus {
            outline: none;
            border-color: #22c55e;
            box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.15);
        }
        .auth-modal__actions {
            display: flex;
            justify-content: flex-end;
            gap: 12px;
            margin-top: 16px;
        }
        .auth-btn {
            border: none;
            border-radius: 999px;
            padding: 10px 18px;
            font-size: 14px;
            font-weight: 500;
            cursor: pointer;
            transition: all 0.2s ease;
        }
        .auth-btn--primary {
            background: #22c55e;
            color: #fff;
        }
        .auth-btn--primary:hover {
            background: #16a34a;
        }
        .auth-btn--secondary {
            background: #f1f5f9;
            color: #0f172a;
        }
        .auth-btn--secondary:hover {
            background: #e2e8f0;
        }
        .auth-btn--ghost {
            background: #f1f5f9;
            color: #0f172a;
        }
        .auth-btn--danger {
            background: #ef4444;
            color: #fff;
        }
        .auth-btn--danger:hover {
            background: #dc2626;
        }
        .auth-alert {
            display: none;
            border-left: 4px solid #22c55e;
            background: #f0fdf4;
            color: #166534;
            padding: 10px 12px;
            border-radius: 10px;
            margin-bottom: 12px;
            font-size: 14px;
        }
        .auth-alert.is-error {
            border-color: #ef4444;
            background: #fef2f2;
            color: #b91c1c;
        }
        .auth-alert.is-visible {
            display: block;
        }
        .auth-menu {
            display: flex;
            flex-direction: column;
            gap: 14px;
        }
        .auth-modal__body {
            color: #334155;
            font-size: 15px;
            margin-bottom: 12px;
            line-height: 1.6;
        }
        .auth-muted-text {
            margin: 0;
            font-size: 13px;
            color: #64748b;
        }
        @media (max-width: 640px) {
            .auth-modal__panel {
                padding: 22px;
            }
            .auth-modal__panel--sm {
                max-width: 90vw;
            }
        }
    `;

    function ensureStyles() {
        if (state.stylesInjected) {
            return;
        }
        const style = document.createElement('style');
        style.setAttribute('data-auth-style', 'true');
        style.textContent = styleContent;
        document.head.appendChild(style);
        state.stylesInjected = true;
    }

    function ensureElement(id, template) {
        if (document.getElementById(id)) {
            return;
        }
        const wrapper = document.createElement('div');
        wrapper.innerHTML = template.trim();
        const el = wrapper.firstElementChild;
        document.body.appendChild(el);
    }

    function ensureModals() {
        ensureStyles();
        ensureElement('login-modal', templates.loginModal);
        ensureElement('confirm-modal', templates.confirmModal);
        ensureElement('user-menu-modal', templates.userMenuModal);
        ensureElement('change-password-modal', templates.changePasswordModal);
    }

    function getCurrentUserInfo() {
        if (state.currentUser) {
            return state.currentUser;
        }
        try {
            const userStr = localStorage.getItem('currentUser');
            if (userStr) {
                state.currentUser = JSON.parse(userStr);
            }
            return state.currentUser;
        } catch (e) {
            console.warn('获取当前用户信息失败:', e);
            return null;
        }
    }

    function getCurrentUser() {
        const info = getCurrentUserInfo();
        return info ? info.username : null;
    }

    function getCurrentToken() {
        const info = getCurrentUserInfo();
        return info ? info.token : null;
    }

    function getRequestHeaders() {
        const headers = { 'Content-Type': 'application/json' };
        const token = getCurrentToken();
        if (token) {
            headers['Authorization'] = 'Bearer ' + token;
            headers['X-User'] = token;
        }
        return headers;
    }

    function updateLoginUI() {
        const loginBtn = document.getElementById('login-btn');
        const userInfo = document.getElementById('user-info');
        const usernameDisplay = document.getElementById('username-display');

        const info = getCurrentUserInfo();

        if (info && info.username) {
            if (loginBtn) {
                loginBtn.style.display = 'none';
                loginBtn.style.visibility = 'hidden';
            }
            if (userInfo) {
                userInfo.style.display = 'flex';
            }
            if (usernameDisplay) {
                usernameDisplay.textContent = info.username;
            }
            bindUserMenuTrigger();
        } else {
            if (loginBtn) {
                loginBtn.style.display = 'inline-flex';
                loginBtn.style.visibility = 'visible';
            }
            if (userInfo) {
                userInfo.style.display = 'none';
            }
        }
    }

    function bindUserMenuTrigger() {
        const trigger = document.getElementById('user-menu-trigger');
        if (trigger && !trigger.dataset.authBound) {
            trigger.addEventListener('click', (event) => {
                event.preventDefault();
                openUserMenuModal();
            });
            trigger.dataset.authBound = 'true';
        }
    }

    function showLoginModal() {
        const modal = document.getElementById('login-modal');
        if (!modal) return;
        const form = document.getElementById('login-form');
        if (form) {
            form.reset();
        }
        showAuthAlert('login-alert', '');
        modal.classList.add('active');
        setTimeout(() => {
            const input = document.getElementById('login-username');
            if (input) {
                input.focus();
            }
        }, 100);
    }

    function closeLoginModal() {
        const modal = document.getElementById('login-modal');
        if (modal) {
            modal.classList.remove('active');
        }
    }

    function showConfirmModal(options = {}) {
        const modal = document.getElementById('confirm-modal');
        if (!modal) return;
        const title = modal.querySelector('#confirm-modal-title');
        const message = modal.querySelector('#confirm-modal-message');
        const btn = modal.querySelector('#confirm-modal-btn');

        if (title) {
            title.textContent = options.title || '确认';
        }
        if (message) {
            message.textContent = options.message || '确定要执行此操作吗？';
        }
        if (btn) {
            btn.textContent = options.buttonText || '确认';
            btn.className = options.buttonType === 'danger'
                ? 'auth-btn auth-btn--danger'
                : 'auth-btn auth-btn--primary';
        }

        state.confirmCallback = options.onConfirm || null;
        modal.classList.add('active');
    }

    function closeConfirmModal() {
        const modal = document.getElementById('confirm-modal');
        if (modal) {
            modal.classList.remove('active');
        }
        state.confirmCallback = null;
    }

    function confirmAction() {
        if (state.confirmCallback) {
            const callback = state.confirmCallback;
            state.confirmCallback = null;
            closeConfirmModal();
            Promise.resolve(callback()).catch((err) => console.error('操作失败:', err));
        } else {
            closeConfirmModal();
        }
    }

    function showAuthAlert(containerId, message, type = 'success') {
        if (typeof window.showAlert === 'function') {
            window.showAlert(containerId, message, type);
            return;
        }
        const container = document.getElementById(containerId);
        if (!container) return;
        if (!message) {
            container.classList.remove('is-visible');
            container.classList.remove('is-error');
            container.textContent = '';
            return;
        }
        container.textContent = message;
        container.classList.add('is-visible');
        if (type === 'error') {
            container.classList.add('is-error');
        } else {
            container.classList.remove('is-error');
        }
        setTimeout(() => {
            container.classList.remove('is-visible');
        }, 5000);
    }

    async function handleLoginSubmit(event) {
        event.preventDefault();
        const username = document.getElementById('login-username').value.trim();
        const password = document.getElementById('login-password').value;

        if (!username || !password) {
            showAuthAlert('login-alert', '请输入用户名和密码', 'error');
            return;
        }

        try {
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });
            const data = await response.json();
            if (response.ok && data.success && data.token) {
                state.currentUser = {
                    username: data.username || username,
                    token: data.token
                };
                localStorage.setItem('currentUser', JSON.stringify(state.currentUser));
                closeLoginModal();
                updateLoginUI();
            } else {
                showAuthAlert('login-alert', data.error || data.message || '登录失败，请检查用户名和密码', 'error');
            }
        } catch (error) {
            console.error('登录请求失败:', error);
            showAuthAlert('login-alert', '登录请求失败，请稍后重试', 'error');
        }
    }

    function openUserMenuModal() {
        const modal = document.getElementById('user-menu-modal');
        const text = document.getElementById('user-menu-username');
        if (text) {
            const username = getCurrentUser();
            text.textContent = username ? `当前用户：${username}` : '未登录';
        }
        if (modal) {
            modal.classList.add('active');
        }
    }

    function closeUserMenuModal() {
        const modal = document.getElementById('user-menu-modal');
        if (modal) {
            modal.classList.remove('active');
        }
    }

    function handleLogoutFromMenu() {
        closeUserMenuModal();
        logout();
    }

    function logout() {
        localStorage.removeItem('currentUser');
        state.currentUser = null;
        location.reload();
    }

    function showChangePasswordModal() {
        closeUserMenuModal();
        const modal = document.getElementById('change-password-modal');
        if (!modal) return;
        const form = document.getElementById('change-password-form');
        if (form) {
            form.reset();
        }
        setChangePasswordAlert('');
        modal.classList.add('active');
        setTimeout(() => {
            const input = document.getElementById('current-password');
            if (input) {
                input.focus();
            }
        }, 100);
    }

    function closeChangePasswordModal() {
        const modal = document.getElementById('change-password-modal');
        if (modal) {
            modal.classList.remove('active');
        }
    }

    function setChangePasswordAlert(message, type = 'error') {
        const container = document.getElementById('change-password-alert');
        if (!container) return;
        if (!message) {
            container.classList.remove('is-visible', 'is-error');
            container.textContent = '';
            return;
        }
        container.textContent = message;
        container.classList.add('is-visible');
        if (type === 'error') {
            container.classList.add('is-error');
        } else {
            container.classList.remove('is-error');
        }
    }

    async function handleChangePasswordSubmit(event) {
        event.preventDefault();
        const currentPassword = document.getElementById('current-password').value.trim();
        const newPassword = document.getElementById('new-password').value.trim();
        const confirmPassword = document.getElementById('confirm-password').value.trim();

        if (!currentPassword || !newPassword || !confirmPassword) {
            setChangePasswordAlert('请完整填写所有字段');
            return;
        }

        if (newPassword !== confirmPassword) {
            setChangePasswordAlert('两次输入的密码不一致');
            return;
        }

        try {
            const response = await fetch('/api/auth/change-password', {
                method: 'POST',
                headers: getRequestHeaders(),
                body: JSON.stringify({ oldPassword: currentPassword, newPassword })
            });
            const data = await response.json().catch(() => ({}));
            if (response.ok) {
                setChangePasswordAlert('密码修改成功', 'success');
                setTimeout(() => {
                    closeChangePasswordModal();
                }, 1200);
            } else {
                setChangePasswordAlert(data.error || data.message || '修改失败，请重试');
            }
        } catch (error) {
            console.error('修改密码失败:', error);
            setChangePasswordAlert('修改失败，请稍后重试');
        }
    }

    function handleGlobalClicks(event) {
        const target = event.target;
        if (target.matches('[data-auth-close="login"]')) {
            closeLoginModal();
        } else if (target.matches('[data-auth-close="confirm"]')) {
            closeConfirmModal();
        } else if (target.matches('[data-auth-close="user-menu"]')) {
            closeUserMenuModal();
        } else if (target.matches('[data-auth-close="change-password"]')) {
            closeChangePasswordModal();
        }
    }

    function handleBackdropClick(event) {
        if (event.target.classList.contains('auth-modal')) {
            event.target.classList.remove('active');
        }
    }

    function handleEsc(event) {
        if (event.key === 'Escape') {
            closeLoginModal();
            closeConfirmModal();
            closeUserMenuModal();
            closeChangePasswordModal();
        }
    }

    function bindEvents() {
        if (state.eventsBound) {
            return;
        }
        state.eventsBound = true;

        document.addEventListener('click', handleGlobalClicks);
        document.addEventListener('click', handleBackdropClick);
        document.addEventListener('keydown', handleEsc);

        const loginForm = document.getElementById('login-form');
        if (loginForm) {
            loginForm.addEventListener('submit', handleLoginSubmit);
        }
        const confirmBtn = document.getElementById('confirm-modal-btn');
        if (confirmBtn) {
            confirmBtn.addEventListener('click', confirmAction);
        }
        const changePwdForm = document.getElementById('change-password-form');
        if (changePwdForm) {
            changePwdForm.addEventListener('submit', handleChangePasswordSubmit);
        }
        const loginBtn = document.getElementById('login-btn');
        if (loginBtn && !loginBtn.dataset.authBound) {
            loginBtn.addEventListener('click', (event) => {
                event.preventDefault();
                showLoginModal();
            });
            loginBtn.dataset.authBound = 'true';
        }
        const changePwdBtn = document.getElementById('open-change-password');
        if (changePwdBtn) {
            changePwdBtn.addEventListener('click', showChangePasswordModal);
        }
        const userLogoutBtn = document.getElementById('user-menu-logout');
        if (userLogoutBtn) {
            userLogoutBtn.addEventListener('click', handleLogoutFromMenu);
        }
    }

    document.addEventListener('DOMContentLoaded', () => {
        ensureModals();
        bindEvents();
        updateLoginUI();
    });

    window.Auth = {
        getCurrentUserInfo,
        getCurrentUser,
        getCurrentToken,
        getRequestHeaders,
        updateLoginUI,
        showLoginModal,
        closeLoginModal,
        showConfirmModal,
        closeConfirmModal,
        confirmAction,
        showChangePasswordModal,
        closeChangePasswordModal,
        bindUserMenuTrigger,
        logout
    };

    window.getCurrentUserInfo = getCurrentUserInfo;
    window.getCurrentUser = getCurrentUser;
    window.getCurrentToken = getCurrentToken;
    window.getRequestHeaders = getRequestHeaders;
    window.updateLoginUI = updateLoginUI;
    window.showLoginModal = showLoginModal;
    window.closeLoginModal = closeLoginModal;
    window.showConfirmModal = showConfirmModal;
    window.closeConfirmModal = closeConfirmModal;
    window.confirmAction = confirmAction;
    window.showChangePasswordModal = showChangePasswordModal;
    window.closeChangePasswordModal = closeChangePasswordModal;
    window.openUserMenuModal = openUserMenuModal;
    window.closeUserMenuModal = closeUserMenuModal;
    window.logout = logout;
})(window, document);

