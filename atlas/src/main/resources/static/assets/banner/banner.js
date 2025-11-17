(function () {
    function createActionsHtml(actions) {
        if (!Array.isArray(actions) || actions.length === 0) {
            return '';
        }
        const itemsHtml = actions
            .map(action => {
                if (!action || !action.label) {
                    return '';
                }
                const type = action.variant === 'secondary' ? 'btn btn-secondary' : 'btn btn-primary';
                const attrs = [
                    action.href ? `href="${action.href}"` : '',
                    action.target ? `target="${action.target}"` : '',
                    action.rel ? `rel="${action.rel}"` : '',
                    action.onClick ? `onclick="${action.onClick}"` : '',
                ].filter(Boolean).join(' ');
                if (action.href) {
                    return `<a class="${type}" ${attrs}>${action.label}</a>`;
                }
                return `<button class="${type}" type="button" ${action.onClick ? `onclick="${action.onClick}"` : ''}>${action.label}</button>`;
            })
            .join('');
        return itemsHtml ? `<div class="extra-actions">${itemsHtml}</div>` : '';
    }

    window.initAtlasBanner = function initAtlasBanner(containerId, options = {}) {
        const container = document.getElementById(containerId);
        if (!container) {
            console.warn('[atlas-banner] container not found:', containerId);
            return;
        }

        const {
            title = 'Atlas 基础架构平台',
            subtitle = '',
            logoIcon = 'A',
            eyebrow = '',
            actions = [],
            extraActionsHtml = '',
            loginSlotHtml = '',
            showLogin = true
        } = options;

        const subtitleHtml = subtitle
            ? `<span class="atlas-banner__logo-subtitle">${subtitle}</span>`
            : '';
        const eyebrowHtml = eyebrow
            ? `<span class="atlas-banner__eyebrow">${eyebrow}</span>`
            : '';
        const actionsHtml = createActionsHtml(actions);
        
        // 自动添加返回首页按钮（如果不在首页）
        let homeButtonHtml = '';
        const currentPath = window.location.pathname;
        const isIndexPage = currentPath === '/' || 
                           currentPath === '/index.html' || 
                           currentPath.endsWith('/index.html') ||
                           (currentPath.endsWith('/') && currentPath.length <= 1);
        if (!isIndexPage) {
            homeButtonHtml = `
                <div class="atlas-banner__quick-links">
                    <a class="atlas-banner__quick-btn" href="/index.html">
                        <span class="atlas-banner__quick-icon">⌂</span>
                        <span>返回首页</span>
                    </a>
                </div>
            `;
        }
        
        const combinedActions = [actionsHtml, homeButtonHtml, extraActionsHtml].filter(Boolean).join('');

        // 自动生成登录相关的 HTML（使用 auth.js 的标准结构）
        // 如果提供了 loginSlotHtml，使用它；否则如果 showLogin 为 true，自动生成
        let loginHtml = '';
        if (loginSlotHtml) {
            loginHtml = loginSlotHtml;
        } else if (showLogin) {
            loginHtml = `
                <div id="login-status">
                    <a href="#" class="btn-login" id="login-btn">
                        <span>🔐</span>
                        <span>登录</span>
                    </a>
                    <div id="user-info" class="user-info" style="display: none;">
                        <button type="button" class="user-chip" id="user-menu-trigger">
                            <span class="username">欢迎, <strong id="username-display"></strong></span>
                            <span class="user-menu-caret">⌄</span>
                        </button>
                    </div>
                </div>
            `;
        }

        container.innerHTML = `
            <header class="atlas-banner">
                <div class="atlas-banner__content">
                    <div class="atlas-banner__info">
                        <div class="atlas-banner__logo">
                            <div class="atlas-banner__logo-icon">${logoIcon}</div>
                            <div class="atlas-banner__logo-text">
                                ${eyebrowHtml}
                                <span class="atlas-banner__logo-title">${title}</span>
                                ${subtitleHtml}
                            </div>
                        </div>
                    </div>
                    <div class="atlas-banner__actions">
                        ${combinedActions}
                        ${loginHtml ? `<div class="atlas-banner__login">${loginHtml}</div>` : ''}
                    </div>
                </div>
            </header>
        `;
    };
})();

