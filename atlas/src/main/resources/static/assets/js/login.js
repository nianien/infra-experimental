(function (window, document) {
    let cachedUser = null;

    function safeParseUser(userStr) {
        try {
            return JSON.parse(userStr);
        } catch (e) {
            console.warn('解析用户信息失败:', e);
            return null;
        }
    }

    function getCurrentUserInfo() {
        if (cachedUser) {
            return cachedUser;
        }
        try {
            const userStr = localStorage.getItem('currentUser');
            if (userStr) {
                cachedUser = safeParseUser(userStr);
            }
        } catch (e) {
            console.warn('获取当前用户信息失败:', e);
        }
        return cachedUser;
    }

    function setCurrentUser(user) {
        cachedUser = user || null;
        if (cachedUser) {
            localStorage.setItem('currentUser', JSON.stringify(cachedUser));
        } else {
            localStorage.removeItem('currentUser');
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

    function isLoggedIn() {
        const info = getCurrentUserInfo();
        return !!(info && info.username);
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

    function displayAlert(containerId, message, type = 'success') {
        if (typeof window.showAlert === 'function') {
            window.showAlert(containerId, message, type);
            return;
        }
        const container = document.getElementById(containerId);
        if (!container) return;
        if (!message) {
            container.innerHTML = '';
            container.style.display = 'none';
            return;
        }
        container.innerHTML = `<div class="alert alert-${type === 'error' ? 'error' : 'success'}">${message}</div>`;
        container.style.display = 'flex';
        setTimeout(() => {
            container.style.display = 'none';
            container.innerHTML = '';
        }, 5000);
    }

    function setChangePasswordAlert(message, type = 'error') {
        const container = document.getElementById('change-password-alert');
        if (!container) return;
        if (!message) {
            container.innerHTML = '';
            container.style.display = 'none';
            return;
        }
        container.innerHTML = `<div class="alert alert-${type === 'error' ? 'error' : 'success'}">${message}</div>`;
        container.style.display = 'flex';
    }

    function bindUserMenuTrigger() {
        const trigger = document.getElementById('user-menu-trigger');
        if (trigger && !trigger.dataset.boundUserMenu) {
            trigger.addEventListener('click', (event) => {
                event.preventDefault();
                openUserMenuModal();
            });
            trigger.dataset.boundUserMenu = 'true';
        }
    }

    function updateLoginUI() {
        const loginBtn = document.getElementById('login-btn');
        const userInfo = document.getElementById('user-info');
        const usernameDisplay = document.getElementById('username-display');
        const loggedIn = isLoggedIn();

        if (loginBtn) {
            loginBtn.style.display = loggedIn ? 'none' : 'inline-flex';
        }
        if (userInfo) {
            userInfo.style.display = loggedIn ? 'flex' : 'none';
        }
        if (usernameDisplay) {
            usernameDisplay.textContent = loggedIn ? getCurrentUser() : '';
        }

        if (loggedIn) {
            bindUserMenuTrigger();
        }

        if (typeof window.onLoginStateChange === 'function') {
            try {
                window.onLoginStateChange(getCurrentUserInfo());
            } catch (error) {
                console.error('onLoginStateChange 执行失败:', error);
            }
        }
    }

    function checkLoginStatus() {
        getCurrentUserInfo();
        updateLoginUI();
    }

    function showLoginModal() {
        const modal = document.getElementById('login-modal');
        if (!modal) return;
        const form = document.getElementById('login-form');
        if (form) {
            form.reset();
        }
        displayAlert('login-alert', '');
        modal.classList.add('active');
        setTimeout(() => {
            const input = document.getElementById('login-username');
            if (input) input.focus();
        }, 100);
    }

    function closeLoginModal() {
        const modal = document.getElementById('login-modal');
        if (modal) {
            modal.classList.remove('active');
        }
    }

    async function handleLoginSubmit(event) {
        event.preventDefault();
        const username = document.getElementById('login-username').value.trim();
        const password = document.getElementById('login-password').value;

        if (!username || !password) {
            displayAlert('login-alert', '请输入用户名和密码', 'error');
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
                setCurrentUser({
                    username: data.username || username,
                    token: data.token
                });
                closeLoginModal();
                updateLoginUI();
                displayAlert('namespaces-alert', '登录成功', 'success');
            } else {
                displayAlert('login-alert', data.error || data.message || '登录失败，请检查用户名和密码', 'error');
            }
        } catch (error) {
            console.error('登录请求失败:', error);
            displayAlert('login-alert', '登录请求失败，请稍后重试', 'error');
        }
    }

    function openUserMenuModal() {
        const modal = document.getElementById('user-menu-modal');
        const usernameEl = document.getElementById('user-menu-username');
        if (usernameEl) {
            const username = getCurrentUser();
            usernameEl.textContent = username ? `当前用户：${username}` : '未登录';
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
        setCurrentUser(null);
        updateLoginUI();
        if (typeof window.onLogout === 'function') {
            try {
                window.onLogout();
            } catch (error) {
                console.error('onLogout 执行失败:', error);
            }
        } else {
            location.reload();
        }
    }

    function showChangePasswordModal() {
        closeUserMenuModal();
        const modal = document.getElementById('change-password-modal');
        if (!modal) return;
        const form = document.getElementById('change-password-form');
        if (form) form.reset();
        setChangePasswordAlert('');
        modal.classList.add('active');
        setTimeout(() => {
            const input = document.getElementById('current-password');
            if (input) input.focus();
        }, 100);
    }

    function closeChangePasswordModal() {
        const modal = document.getElementById('change-password-modal');
        if (modal) {
            modal.classList.remove('active');
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
                body: JSON.stringify({
                    oldPassword: currentPassword,
                    newPassword: newPassword
                })
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

    function bindModalEvents() {
        document.addEventListener('click', (event) => {
            const loginModal = document.getElementById('login-modal');
            if (loginModal && event.target === loginModal) {
                closeLoginModal();
            }
            const userMenuModal = document.getElementById('user-menu-modal');
            if (userMenuModal && event.target === userMenuModal) {
                closeUserMenuModal();
            }
            const changePwdModal = document.getElementById('change-password-modal');
            if (changePwdModal && event.target === changePwdModal) {
                closeChangePasswordModal();
            }
        });

        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape') {
                closeLoginModal();
                closeUserMenuModal();
                closeChangePasswordModal();
            }
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        const loginForm = document.getElementById('login-form');
        if (loginForm) {
            loginForm.addEventListener('submit', handleLoginSubmit);
        }
        const changePwdForm = document.getElementById('change-password-form');
        if (changePwdForm) {
            changePwdForm.addEventListener('submit', handleChangePasswordSubmit);
        }
        const changePwdBtn = document.getElementById('open-change-password');
        if (changePwdBtn) {
            changePwdBtn.addEventListener('click', showChangePasswordModal);
        }
        const logoutBtn = document.getElementById('user-menu-logout');
        if (logoutBtn) {
            logoutBtn.addEventListener('click', handleLogoutFromMenu);
        }
        bindUserMenuTrigger();
        bindModalEvents();
        checkLoginStatus();
    });

    window.getCurrentUserInfo = getCurrentUserInfo;
    window.getCurrentUser = getCurrentUser;
    window.getCurrentToken = getCurrentToken;
    window.isLoggedIn = isLoggedIn;
    window.getRequestHeaders = getRequestHeaders;
    window.checkLoginStatus = checkLoginStatus;
    window.updateLoginUI = updateLoginUI;
    window.showLoginModal = showLoginModal;
    window.closeLoginModal = closeLoginModal;
    window.bindUserMenuTrigger = bindUserMenuTrigger;
    window.openUserMenuModal = openUserMenuModal;
    window.closeUserMenuModal = closeUserMenuModal;
    window.handleLogoutFromMenu = handleLogoutFromMenu;
    window.logout = logout;
    window.showChangePasswordModal = showChangePasswordModal;
    window.closeChangePasswordModal = closeChangePasswordModal;
})();

