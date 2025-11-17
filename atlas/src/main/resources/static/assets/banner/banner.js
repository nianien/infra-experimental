(function () {
    function createMetaHtml(metaItems) {
        if (!Array.isArray(metaItems) || metaItems.length === 0) {
            return '';
        }
        const itemsHtml = metaItems
            .filter(Boolean)
            .map(item => `<span class="atlas-banner__meta-item">${item}</span>`)
            .join('');
        return itemsHtml ? `<div class="atlas-banner__meta">${itemsHtml}</div>` : '';
    }

    function createInfoBlock(tagline, metaItems, extraInfoHtml) {
        const taglineHtml = tagline
            ? `<p class="atlas-banner__tagline">${tagline}</p>`
            : '';
        const metaHtml = createMetaHtml(metaItems);
        return `${taglineHtml}${metaHtml}${extraInfoHtml || ''}`;
    }

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
            tagline = '',
            metaItems = [],
            extraInfoHtml = '',
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
        const infoBlockHtml = createInfoBlock(tagline, metaItems, extraInfoHtml);
        const actionsHtml = createActionsHtml(actions);
        const combinedActions = [actionsHtml, extraActionsHtml].filter(Boolean).join('');

        const loginHtml = loginSlotHtml || (showLogin ? `
            <div class="user-info">
                <span class="user-name" id="userName"></span>
                <button class="login-btn" id="loginBtn" style="display: none;">登录</button>
                <button class="logout-btn" id="logoutBtn" style="display: none;">登出</button>
            </div>
        ` : '');

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
                        ${infoBlockHtml}
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

