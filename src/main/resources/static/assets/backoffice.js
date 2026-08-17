const state = {
    csrf: '', users: [], prompts: [], credentials: [], sessions: [], system: null,
    dashboard: null, audit: [], view: 'overview', selectedUserId: null, selectedUser: null
};
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const fmtDate = value => value ? new Intl.DateTimeFormat('ru-RU',{dateStyle:'short',timeStyle:'medium'}).format(new Date(value)) : '—';
const fmtAgo = value => {
    if (!value) return '—';
    const sec = Math.max(0, Math.round((Date.now()-new Date(value).getTime())/1000));
    if (sec < 60) return `${sec} сек назад`;
    if (sec < 3600) return `${Math.floor(sec/60)} мин назад`;
    if (sec < 86400) return `${Math.floor(sec/3600)} ч назад`;
    return `${Math.floor(sec/86400)} дн назад`;
};
let pending = 0;

document.addEventListener('DOMContentLoaded', init);

async function init() {
    bindShell();
    try {
        const session = await rawApi('/api/admin/auth/session');
        state.csrf = session.csrf || '';
        await reloadAll();
        setStatus('Готово');
    } catch (error) {
        if (error.status === 401 || error.status === 403) location.replace('/login.html');
        else renderFatal(error.message);
    }
}

function bindShell() {
    $('logout').onclick = async () => {
        try { await api('/api/admin/auth/logout',{method:'POST'}); } catch (_) {}
        location.replace('/login.html');
    };
    $('btn-add-user').onclick = () => openUserModal();
    $('user-list').onclick = event => {
        const button = event.target.closest('[data-user-id]');
        if (!button) return;
        state.selectedUserId = Number(button.dataset.userId);
        state.view = 'user';
        void loadSelectedUser();
    };
    $('system-nav').onclick = event => {
        const button = event.target.closest('[data-view]');
        if (!button) return;
        state.view = button.dataset.view;
        state.selectedUserId = null;
        state.selectedUser = null;
        syncNav(); renderWorkspace();
    };
    $('workspace').addEventListener('click', workspaceClick);
    $('workspace').addEventListener('submit', workspaceSubmit);
    $('modal-root').addEventListener('click', modalClick);
    $('modal-root').addEventListener('submit', modalSubmit);
}

async function reloadAll() {
    const [dashboard, users, prompts, credentials, sessions, system, audit] = await Promise.all([
        api('/api/admin/dashboard'), api('/api/admin/users'), api('/api/admin/prompts'),
        api('/api/admin/credentials'), api('/api/admin/sessions'), api('/api/admin/system'), api('/api/admin/audit')
    ]);
    Object.assign(state,{dashboard,users,prompts,credentials,sessions,system,audit});
    renderUserList(); syncNav(); renderWorkspace();
}

async function refreshCore() {
    const [dashboard, users, credentials, sessions] = await Promise.all([
        api('/api/admin/dashboard'), api('/api/admin/users'), api('/api/admin/credentials'), api('/api/admin/sessions')
    ]);
    Object.assign(state,{dashboard,users,credentials,sessions});
    renderUserList();
}

async function loadSelectedUser() {
    if (!state.selectedUserId) return;
    try {
        setStatus('Загрузка…');
        state.selectedUser = await api(`/api/admin/users/${state.selectedUserId}`);
        syncNav(); renderUserList(); renderWorkspace();
    } catch (error) { toast(error.message,'error'); }
    finally { setStatus('Готово'); }
}

async function rawApi(url, options={}) {
    pending++; setStatus('Работаю…');
    try {
        const response = await fetch(url,{...options,cache:'no-store'});
        const text = await response.text();
        let data = {};
        if (text) { try { data=JSON.parse(text); } catch (_) { data={message:text}; } }
        if (!response.ok) { const error=new Error(data.message||`HTTP ${response.status}`); error.status=response.status; error.code=data.code; throw error; }
        return data;
    } finally { pending=Math.max(0,pending-1); if(!pending)setStatus('Готово'); }
}

async function api(url, options={}) {
    const method=String(options.method||'GET').toUpperCase();
    const headers={...(options.headers||{})};
    if (!(options.body instanceof FormData)) headers['Content-Type']=headers['Content-Type']||'application/json';
    if (!['GET','HEAD','OPTIONS'].includes(method) && state.csrf) headers['X-Backoffice-CSRF']=state.csrf;
    try { return await rawApi(url,{...options,method,headers}); }
    catch (error) { if(error.status===401||error.status===403){location.replace('/login.html');} throw error; }
}

function setStatus(text) { const el=$('global-status'); if(el) el.textContent=text; }
function renderFatal(message) { $('workspace').innerHTML=`<div class="empty-state"><div class="eyebrow">PRODAMUS</div><h2>Не удалось загрузить back-office</h2><p>${esc(message)}</p><button class="btn" onclick="location.reload()">Повторить</button></div>`; }

function renderUserList() {
    const root=$('user-list');
    if (!state.users.length) { root.innerHTML='<div class="history-empty">Пользователей пока нет.</div>'; return; }
    root.innerHTML=state.users.map(user=>`
        <button class="tenant-nav ${state.selectedUserId===user.id?'is-active':''}" type="button" data-user-id="${user.id}">
            <div class="prodamus-user-nav-line"><b>${esc(user.displayName)}</b><span class="mini-dot ${user.enabled?'ok':'off'}"></span></div>
            <small>@${esc(user.login)}${user.email?' · '+esc(user.email):''}</small>
            <span class="nav-meta"><small>${user.promptCount} рол.</small><small>${user.activeSessions?`● ${user.activeSessions} актив.`:'нет сессий'}</small></span>
        </button>`).join('');
}

function syncNav() {
    document.querySelectorAll('.system-nav-btn').forEach(button=>button.classList.toggle('is-active',state.selectedUserId==null && button.dataset.view===state.view));
}

function renderWorkspace() {
    if (state.view==='user' && state.selectedUser) return renderUser();
    const renderers={overview:renderOverview,prompts:renderPrompts,credentials:renderCredentials,sessions:renderSessions,system:renderSystem,audit:renderAudit};
    (renderers[state.view]||renderOverview)();
}

function heading(eyebrow,title,meta='',actions='') {
    return `<div class="workspace-heading"><div><div class="eyebrow">${esc(eyebrow)}</div><h1 class="workspace-title">${esc(title)}</h1>${meta?`<div class="workspace-meta">${esc(meta)}</div>`:''}</div><div class="workspace-actions">${actions}</div></div>`;
}

function renderOverview() {
    const d=state.dashboard||{};
    $('workspace').innerHTML=`
      ${heading('PRODAMUS CONTROL CENTER','Обзор системы','Централизованное управление Windows-ассистентами')}
      <div class="metrics-grid">
        ${metric('Пользователи',d.enabledUsers||0,'активных учётных записей')}
        ${metric('Роли',d.enabledPrompts||0,'доступных сценариев продаж')}
        ${metric('AI-ключи',d.enabledCredentials||0,`суммарная ёмкость: ${d.totalCapacity||0}`)}
        ${metric('Live сейчас',d.activeSessions||0,'активных / резервируемых сессий')}
      </div>
      <div class="settings-grid overview-grid">
        <article class="settings-card">
          <div class="settings-card-head"><div><div class="eyebrow">БЫСТРЫЙ СТАРТ</div><h3>Что уже готово</h3><p>Backend подготовлен как контрольный центр Prodamus.</p></div></div>
          <div class="prodamus-check-list">
            ${check('Авторизация Windows-клиентов','Access token + ротация refresh token; режим «Запомнить меня» рассчитан на 7 дней.')}
            ${check('Роли и промпты','Каждому менеджеру можно назначить собственный набор сценариев.')}
            ${check('Пул Gemini-ключей','Постоянные ключи шифруются на сервере; клиент получает только ephemeral token.')}
            ${check('Live-session leasing','Сервер резервирует свободную ёмкость ключей, принимает heartbeat и освобождает зависшие сессии.')}
            ${check('Центральная конфигурация','Версии клиента и feature flags управляются отсюда.')}
          </div>
        </article>
        <article class="settings-card">
          <div class="settings-card-head"><div><div class="eyebrow">СОСТОЯНИЕ</div><h3>Подготовка к первому звонку</h3></div></div>
          <div class="status-stack">
            ${statusLine(state.users.some(u=>u.enabled),'Пользователь','Добавьте учётную запись менеджера')}
            ${statusLine(state.prompts.some(p=>p.enabled),'Роль','Есть активный prompt profile')}
            ${statusLine(state.credentials.some(c=>c.enabled && c.healthStatus==='OK'),'Gemini','Добавьте API key и нажмите «Проверить»')}
          </div>
          <div class="setup-note mt"><b>Транскрипты разговоров не сохраняются back-office.</b><ol><li>Сервер хранит пользователей, роли, ключи и технические метаданные сессий.</li><li>Сам звук будущий Windows-клиент отправляет напрямую в Gemini Live.</li></ol></div>
        </article>
      </div>`;
}
function metric(label,value,hint){return `<article class="metric-card"><div class="metric-label">${esc(label)}</div><div class="metric-value">${esc(value)}</div><div class="metric-hint">${esc(hint)}</div></article>`;}
function check(title,text){return `<div class="check-row"><span>✓</span><div><b>${esc(title)}</b><small>${esc(text)}</small></div></div>`;}
function statusLine(ok,title,text){return `<div class="status-line"><span class="status-light ${ok?'ok':'warn'}"></span><div><b>${esc(title)}</b><small>${esc(ok?'Готово':text)}</small></div></div>`;}

function renderUser() {
    const u=state.selectedUser;
    const roles=state.prompts;
    $('workspace').innerHTML=`
      ${heading('ПОЛЬЗОВАТЕЛЬ',u.displayName,`@${u.login} · создан ${fmtDate(u.createdAt)}`,`<span class="status-pill ${u.enabled?'active':'disabled'}">${u.enabled?'ACTIVE':'DISABLED'}</span>`)}
      <form class="settings-grid" data-user-form>
        <article class="settings-card">
          <div class="settings-card-head"><div><div class="eyebrow">УЧЁТНАЯ ЗАПИСЬ</div><h3>Профиль менеджера</h3><p>Эти данные используются Windows-приложением для входа.</p></div></div>
          <div class="two-col-form">
            <label>Имя<input class="custom-input" name="displayName" value="${attr(u.displayName)}" required></label>
            <label>Логин<input class="custom-input" name="login" value="${attr(u.login)}" required></label>
          </div>
          <label>Email<input class="custom-input" name="email" type="email" value="${attr(u.email||'')}"></label>
          <label>Новый пароль<input class="custom-input" name="password" type="password" autocomplete="new-password" placeholder="Оставьте пустым, чтобы не менять"><small>При смене пароля все сохранённые клиентские сессии пользователя будут отозваны.</small></label>
          <label class="toggle-card"><input name="enabled" type="checkbox" ${u.enabled?'checked':''}><span><b>Доступ разрешён</b><small>Отключение немедленно отзывает access/refresh tokens.</small></span></label>
        </article>
        <article class="settings-card">
          <div class="settings-card-head"><div><div class="eyebrow">РОЛИ</div><h3>Доступные сценарии</h3><p>В Windows-клиенте менеджер увидит только отмеченные роли.</p></div></div>
          <div class="role-checkbox-list">${roles.map(p=>roleCheckbox(p,u.promptIds.includes(p.id))).join('')||'<div class="history-empty">Сначала создайте хотя бы одну роль.</div>'}</div>
        </article>
        <article class="settings-card full-card">
          <div class="settings-card-head"><div><div class="eyebrow">ПЕРСОНАЛИЗАЦИЯ</div><h3>Дополнительные инструкции</h3><p>Необязательный слой, который добавляется к глобальному prompt и выбранной роли.</p></div></div>
          <label>Инструкции<textarea class="custom-input code-textarea" name="customInstructions" rows="6" placeholder="Например: менеджер отвечает только за SMB-сегмент…">${esc(u.customInstructions||'')}</textarea></label>
          <div class="settings-actions"><button class="btn btn-save prodamus-primary" type="submit">Сохранить пользователя</button><button class="btn btn-danger" type="button" data-disable-user="${u.id}" ${!u.enabled?'disabled':''}>Отключить доступ</button></div>
        </article>
      </form>
      <article class="settings-card mt">
        <div class="settings-card-head"><div><div class="eyebrow">УСТРОЙСТВА</div><h3>Сохранённые входы</h3><p>Refresh-сессии Windows-клиента. Можно отозвать отдельный компьютер без смены пароля.</p></div></div></div>
        <div class="device-list">${(u.devices||[]).map(d=>`<div class="device-row"><div class="device-icon">▣</div><div><b>${esc(d.deviceName||'Windows устройство')}</b><small class="mono">${esc(d.deviceId)}</small><small>${d.persistent?'Запомнить меня · ':''}действует до ${fmtDate(d.expiresAt)}</small></div><button class="btn btn-sm btn-danger" type="button" data-revoke-device="${attr(d.deviceId)}">Отозвать</button></div>`).join('')||'<div class="history-empty">Сохранённых устройств пока нет.</div>'}</div>
      </article>
      <article class="settings-card mt">
        <div class="settings-card-head"><div><div class="eyebrow">ИСТОРИЯ</div><h3>Последние Live-сессии</h3><p>Хранятся только технические метаданные — без текста разговора.</p></div></div></div>
        ${sessionTable(u.recentSessions,false)}
      </article>`;
}
function roleCheckbox(p,checked){return `<label class="role-check ${!p.enabled?'is-disabled':''}"><input type="checkbox" name="promptIds" value="${p.id}" ${checked?'checked':''} ${!p.enabled?'disabled':''}><span><b>${esc(p.name)}</b><small>${esc(p.description||p.model)}</small></span><em>${p.enabled?'ACTIVE':'OFF'}</em></label>`;}

function renderPrompts(){
 $('workspace').innerHTML=`${heading('РОЛИ И ПРОМПТЫ','Сценарии разговора','Промпты не передаются менеджеру в явном виде',`<button class="btn btn-save prodamus-primary" data-add-prompt>＋ Добавить роль</button>`)}
 <div class="prodamus-card-list">${state.prompts.map(p=>`<article class="settings-card prompt-card">
   <div class="settings-card-head"><div><div class="eyebrow">${esc(p.model)}</div><h3>${esc(p.name)}</h3><p>${esc(p.description||'Без описания')}</p></div><span class="status-pill ${p.enabled?'active':'disabled'}">${p.enabled?'ACTIVE':'OFF'}</span></div>
   <div class="prompt-preview">${esc(short(p.systemPrompt,310)||'Системный prompt пока пуст.')}</div>
   <div class="card-footer-meta"><span>База знаний: ${formatChars(p.knowledgeBase)}</span><span>Версия: v${p.version}</span><span>Порядок: ${p.sortOrder}</span><span>Изменён: ${fmtAgo(p.updatedAt)}</span></div>
   <div class="settings-actions"><button class="btn btn-sm" data-edit-prompt="${p.id}">Редактировать</button>${p.enabled?`<button class="btn btn-sm btn-danger" data-disable-prompt="${p.id}">Отключить</button>`:''}</div>
 </article>`).join('')||emptyCard('Ролей пока нет','Добавьте первый сценарий продаж.')}</div>`;
}

function renderCredentials(){
 $('workspace').innerHTML=`${heading('AI CREDENTIALS','Пул Gemini-ключей','Постоянные API keys зашифрованы в PostgreSQL и никогда не выдаются Windows-клиенту',`<button class="btn btn-save prodamus-primary" data-add-credential>＋ Добавить ключ</button>`)}
 <div class="prodamus-card-list">${state.credentials.map(c=>`<article class="settings-card credential-card">
   <div class="settings-card-head"><div><div class="eyebrow">${esc(c.provider)}</div><h3>${esc(c.name)}</h3><p class="mono">${esc(c.keyHint)}</p></div>${credentialHealth(c)}</div>
   <div class="credential-capacity"><div><span>Активно</span><b>${c.activeSessions} / ${c.maxConcurrentSessions}</b></div><div class="capacity-track"><span style="width:${Math.min(100,c.maxConcurrentSessions?c.activeSessions*100/c.maxConcurrentSessions:0)}%"></span></div></div>
   ${c.lastError?`<div class="credential-error">${esc(short(c.lastError,500))}</div>`:''}
   <div class="card-footer-meta"><span>${c.enabled?'Разрешён':'Отключён'}</span><span>Проверка: ${c.lastCheckedAt?fmtAgo(c.lastCheckedAt):'не выполнялась'}</span></div>
   <div class="settings-actions"><button class="btn btn-sm" data-test-credential="${c.id}">Проверить</button><button class="btn btn-sm" data-edit-credential="${c.id}">Настройки</button>${c.enabled?`<button class="btn btn-sm btn-danger" data-disable-credential="${c.id}">Отключить</button>`:''}</div>
 </article>`).join('')||emptyCard('AI-ключи не добавлены','Добавьте Gemini API key. На диске и в базе он хранится только в зашифрованном виде.')}</div>`;
}
function credentialHealth(c){const cls=c.healthStatus==='OK'?'active':c.healthStatus==='ERROR'?'disabled':'pending';return `<span class="status-pill ${cls}">${esc(c.healthStatus)}</span>`;}

function renderSessions(){
 const active=state.sessions.filter(s=>['ACTIVE','PROVISIONING'].includes(s.status)).length;
 $('workspace').innerHTML=`${heading('LIVE SESSIONS','Сессии менеджеров',`${active} активно сейчас`, `<button class="btn" data-refresh>Обновить</button>`)}
 <article class="settings-card"><div class="settings-card-head"><div><div class="eyebrow">ТЕХНИЧЕСКАЯ ИСТОРИЯ</div><h3>Последние 200 сессий</h3><p>Ни аудио, ни транскрипт разговора здесь не сохраняются.</p></div></div>${sessionTable(state.sessions,true)}</article>`;
}
function sessionTable(items,adminActions=true){
 if(!items||!items.length)return '<div class="history-empty table-empty">Сессий пока нет.</div>';
 return `<div class="data-table-wrap"><table class="data-table"><thead><tr><th>Статус</th><th>Менеджер</th><th>Роль</th><th>AI key</th><th>Старт</th><th>Устройство</th>${adminActions?'<th></th>':''}</tr></thead><tbody>${items.map(s=>`<tr>
 <td>${sessionStatus(s.status)}</td><td><b>${esc(s.userName||'—')}</b></td><td>${esc(s.promptName||'—')}</td><td class="muted-cell">${esc(s.credentialName||'—')}</td><td title="${esc(fmtDate(s.startedAt))}">${esc(fmtAgo(s.startedAt))}</td><td class="muted-cell mono">${esc(short(s.deviceId,18))}</td>${adminActions?`<td class="right-cell">${['ACTIVE','PROVISIONING'].includes(s.status)?`<button class="btn btn-sm btn-danger" data-terminate-session="${s.id}">Стоп</button>`:''}</td>`:''}</tr>`).join('')}</tbody></table></div>`;
}
function sessionStatus(status){const active=['ACTIVE'].includes(status), pending=status==='PROVISIONING', bad=['EXPIRED','PROVISIONING_ERROR'].includes(status);return `<span class="status-pill ${active?'active':pending?'pending':bad?'disabled':''}">${esc(status)}</span>`;}

function renderSystem(){ const c=state.system||{};
 $('workspace').innerHTML=`${heading('SYSTEM CONFIG','Система','Центральные параметры, которые Windows-клиент получает при bootstrap')}
 <form class="settings-grid" data-system-form>
   <article class="settings-card full-card"><div class="settings-card-head"><div><div class="eyebrow">GLOBAL PROMPT</div><h3>Общие правила Prodamus</h3><p>Этот слой добавляется перед ролью, базой знаний и персональными инструкциями менеджера.</p></div></div><textarea class="custom-input code-textarea" name="globalPrompt" rows="8">${esc(c.globalPrompt||'')}</textarea></article>
   <article class="settings-card"><div class="settings-card-head"><div><div class="eyebrow">CLIENT VERSION</div><h3>Версии Windows-приложения</h3></div></div><label>Минимально допустимая версия<input class="custom-input" name="minimumClientVersion" value="${attr(c.minimumClientVersion||'0.1.0')}" required></label><label>Актуальная версия<input class="custom-input" name="latestClientVersion" value="${attr(c.latestClientVersion||'0.1.0')}" required></label><label>Ссылка на установщик / обновление<input class="custom-input" name="clientDownloadUrl" type="url" value="${attr(c.clientDownloadUrl||'')}" placeholder="https://.../ProdamusSetup.exe"><small>Windows-клиент покажет эту ссылку, если доступна новая версия или текущая версия заблокирована.</small></label></article>
   <article class="settings-card"><div class="settings-card-head"><div><div class="eyebrow">GEMINI LIVE</div><h3>Модель по умолчанию</h3></div></div><label>Model ID<input class="custom-input mono" name="defaultModel" value="${attr(c.defaultModel||'')}" required><small>Новая роль по умолчанию создаётся с этой моделью. У конкретной роли модель можно переопределить.</small></label></article>
   <article class="settings-card full-card"><div class="settings-card-head"><div><div class="eyebrow">FEATURE FLAGS</div><h3>Функции Windows-клиента</h3></div></div><label class="toggle-card"><input type="checkbox" name="featureExpandedMode" ${c.featureExpandedMode?'checked':''}><span><b>Большой режим диалога</b><small>Разрешить expanded UI с историей реплик клиента и рекомендаций.</small></span></label><label class="toggle-card"><input type="checkbox" name="featureManualClientContext" ${c.featureManualClientContext?'checked':''}><span><b>Ручной контекст клиента</b><small>Разрешить менеджеру добавлять дополнительный контекст перед стартом разговора.</small></span></label><div class="settings-actions"><button class="btn btn-save prodamus-primary" type="submit">Сохранить систему</button></div></article>
 </form>`;
}

function renderAudit(){
 $('workspace').innerHTML=`${heading('AUDIT','Журнал действий','Последние 100 технических событий back-office',`<button class="btn" data-refresh>Обновить</button>`)}
 <article class="settings-card"><div class="data-table-wrap"><table class="data-table"><thead><tr><th>Время</th><th>Событие</th><th>Кто</th><th>Объект</th><th>Детали</th></tr></thead><tbody>${state.audit.map(a=>`<tr><td>${esc(fmtDate(a.createdAt))}</td><td><b>${esc(a.eventType)}</b></td><td>${esc(a.actor||'—')}</td><td>${esc(a.subject||'—')}</td><td class="muted-cell">${esc(short(a.detail,120)||'—')}</td></tr>`).join('')||'<tr><td colspan="5" class="table-empty">Журнал пуст.</td></tr>'}</tbody></table></div></article>`;
}

async function workspaceClick(event){
 try{
  if(event.target.closest('[data-add-prompt]'))return openPromptModal();
  const editPrompt=event.target.closest('[data-edit-prompt]'); if(editPrompt)return openPromptModal(Number(editPrompt.dataset.editPrompt));
  const disablePrompt=event.target.closest('[data-disable-prompt]'); if(disablePrompt&&confirm('Отключить эту роль? Уже идущие сессии не прерываются.')){await api(`/api/admin/prompts/${disablePrompt.dataset.disablePrompt}`,{method:'DELETE'});state.prompts=await api('/api/admin/prompts');toast('Роль отключена');renderWorkspace();return;}
  if(event.target.closest('[data-add-credential]'))return openCredentialModal();
  const editCredential=event.target.closest('[data-edit-credential]'); if(editCredential)return openCredentialModal(Number(editCredential.dataset.editCredential));
  const testCredential=event.target.closest('[data-test-credential]'); if(testCredential){event.target.disabled=true;event.target.textContent='Проверяем…';try{const result=await api(`/api/admin/credentials/${testCredential.dataset.testCredential}/test`,{method:'POST'});toast(result.message);}finally{state.credentials=await api('/api/admin/credentials');renderWorkspace();}return;}
  const disableCredential=event.target.closest('[data-disable-credential]'); if(disableCredential&&confirm('Отключить этот AI-ключ? Новые сессии на него назначаться не будут.')){await api(`/api/admin/credentials/${disableCredential.dataset.disableCredential}`,{method:'DELETE'});await refreshCore();renderWorkspace();return;}
  const terminate=event.target.closest('[data-terminate-session]'); if(terminate&&confirm('Завершить эту серверную Live-сессию?')){await api(`/api/admin/sessions/${terminate.dataset.terminateSession}/terminate`,{method:'POST'});await refreshCore();renderWorkspace();return;}
  const disableUser=event.target.closest('[data-disable-user]'); if(disableUser&&confirm('Отключить пользователя и отозвать его сохранённые сессии входа?')){await api(`/api/admin/users/${disableUser.dataset.disableUser}`,{method:'DELETE'});await refreshCore();await loadSelectedUser();toast('Пользователь отключён');return;}
  const revokeDevice=event.target.closest('[data-revoke-device]'); if(revokeDevice&&confirm('Отозвать вход на этом устройстве?')){await api(`/api/admin/users/${state.selectedUserId}/devices/revoke`,{method:'POST',body:JSON.stringify({deviceId:revokeDevice.dataset.revokeDevice})});await loadSelectedUser();toast('Устройство отозвано');return;}
  if(event.target.closest('[data-refresh]')){await reloadAll();toast('Данные обновлены');return;}
 }catch(error){toast(error.message,'error');}
}

async function workspaceSubmit(event){
 event.preventDefault();
 const form=event.target;
 try{
  if(form.matches('[data-user-form]')){
   const body={login:form.login.value.trim(),displayName:form.displayName.value.trim(),email:form.email.value.trim(),password:form.password.value,enabled:form.enabled.checked,customInstructions:form.customInstructions.value,promptIds:[...form.querySelectorAll('input[name="promptIds"]:checked')].map(x=>Number(x.value))};
   state.selectedUser=await api(`/api/admin/users/${state.selectedUserId}`,{method:'PUT',body:JSON.stringify(body)}); await refreshCore();renderWorkspace();toast('Пользователь сохранён');return;
  }
  if(form.matches('[data-system-form]')){
   const body={globalPrompt:form.globalPrompt.value,minimumClientVersion:form.minimumClientVersion.value.trim(),latestClientVersion:form.latestClientVersion.value.trim(),clientDownloadUrl:form.clientDownloadUrl.value.trim(),defaultModel:form.defaultModel.value.trim(),featureExpandedMode:form.featureExpandedMode.checked,featureManualClientContext:form.featureManualClientContext.checked};
   state.system=await api('/api/admin/system',{method:'PUT',body:JSON.stringify(body)});renderWorkspace();toast('Системные настройки сохранены');return;
  }
 }catch(error){toast(error.message,'error');}
}

function openUserModal(){
 const roleHtml=state.prompts.filter(p=>p.enabled).map(p=>roleCheckbox(p,false)).join('')||'<div class="history-empty">Активных ролей пока нет.</div>';
 openModal('Новый пользователь','ДОСТУП WINDOWS-КЛИЕНТА',`<form class="modal-form" data-create-user-form><div class="two-col-form"><label>Имя<input class="custom-input" name="displayName" required autofocus placeholder="Иван Петров"></label><label>Логин<input class="custom-input" name="login" required autocomplete="off" placeholder="ivan"></label></div><div class="two-col-form"><label>Email<input class="custom-input" name="email" type="email" placeholder="ivan@company.ru"></label><label>Пароль<input class="custom-input" name="password" type="password" minlength="6" required autocomplete="new-password"></label></div><label class="toggle-card"><input name="enabled" type="checkbox" checked><span><b>Сразу разрешить вход</b><small>Пользователь сможет авторизоваться после сохранения.</small></span></label><div class="modal-section-title">Доступные роли</div><div class="role-checkbox-list modal-role-list">${roleHtml}</div><label class="mt">Персональные инструкции<textarea class="custom-input" name="customInstructions" rows="4"></textarea></label><div id="modal-error" class="form-error d-none"></div><div class="modal-actions"><button class="btn" type="button" data-close-modal>Отмена</button><button class="btn btn-save prodamus-primary" type="submit">Добавить пользователя</button></div></form>`);
}

function openPromptModal(id=null){
 const p=id?state.prompts.find(x=>x.id===id):null;
 const model=p?.model||state.system?.defaultModel||'gemini-3.1-flash-live-preview';
 openModal(p?'Редактировать роль':'Новая роль','PROMPT PROFILE',`<form class="modal-form" data-prompt-form data-id="${p?.id||''}"><div class="two-col-form"><label>Название<input class="custom-input" name="name" value="${attr(p?.name||'')}" required autofocus placeholder="Продажа Bitrix24"></label><label>Порядок<input class="custom-input" name="sortOrder" type="number" value="${p?.sortOrder??100}"></label></div><label>Описание<input class="custom-input" name="description" value="${attr(p?.description||'')}" placeholder="Короткое описание для менеджера"></label><label>Gemini model<input class="custom-input mono" name="model" value="${attr(model)}" required></label><label>System prompt<textarea class="custom-input code-textarea" name="systemPrompt" rows="8" placeholder="Роль, правила, стиль подсказок…">${esc(p?.systemPrompt||'')}</textarea></label><label>База знаний<textarea class="custom-input code-textarea" name="knowledgeBase" rows="9" placeholder="Продукты, тарифы, УТП, возражения…">${esc(p?.knowledgeBase||'')}</textarea><small>На первом этапе это управляемый текстовый контекст. Полноценный RAG можно подключить отдельным модулем позже.</small></label><label class="toggle-card"><input name="enabled" type="checkbox" ${p?.enabled!==false?'checked':''}><span><b>Роль активна</b><small>Неактивная роль не отображается Windows-клиенту.</small></span></label><div id="modal-error" class="form-error d-none"></div><div class="modal-actions"><button class="btn" type="button" data-close-modal>Отмена</button><button class="btn btn-save prodamus-primary" type="submit">Сохранить</button></div></form>`,'wide');
}

function openCredentialModal(id=null){
 const c=id?state.credentials.find(x=>x.id===id):null;
 openModal(c?'Настройки AI-ключа':'Новый AI-ключ','GEMINI CREDENTIAL',`<form class="modal-form" data-credential-form data-id="${c?.id||''}"><div class="two-col-form"><label>Название<input class="custom-input" name="name" value="${attr(c?.name||'')}" required autofocus placeholder="Gemini Key 01"></label><label>Лимит одновременных сессий<input class="custom-input" name="maxConcurrentSessions" type="number" min="1" max="100" value="${c?.maxConcurrentSessions??1}" required></label></div><label>Gemini API key<input class="custom-input mono" name="apiKey" type="password" autocomplete="off" ${c?'':'required'} placeholder="${c?'Оставьте пустым, чтобы не менять':'AIza…'}"><small>${c?`Текущий ключ: ${esc(c.keyHint)}. Новый ключ будет зашифрован AES-256-GCM до записи в PostgreSQL.`:'Постоянный ключ никогда не выдаётся Windows-клиенту.'}</small></label><label class="toggle-card"><input name="enabled" type="checkbox" ${c?.enabled!==false?'checked':''}><span><b>Разрешить выдачу сессий</b><small>Сервер будет учитывать этот ключ при распределении свободной ёмкости.</small></span></label><div id="modal-error" class="form-error d-none"></div><div class="modal-actions"><button class="btn" type="button" data-close-modal>Отмена</button><button class="btn btn-save prodamus-primary" type="submit">Сохранить</button></div></form>`);
}

function openModal(title,eyebrow,body,size=''){$('modal-root').innerHTML=`<div class="modal-backdrop-custom"><div class="modal-card modal-custom ${size==='wide'?'modal-card-wide':''}"><div class="modal-header-custom"><div><div class="modal-step-label">${esc(eyebrow)}</div><h2>${esc(title)}</h2></div><button class="btn-close-custom" type="button" data-close-modal>✕</button></div>${body}</div></div>`;}
function closeModal(){$('modal-root').innerHTML='';}
function modalClick(event){if(event.target.matches('.modal-backdrop-custom')||event.target.closest('[data-close-modal]'))closeModal();}

async function modalSubmit(event){
 event.preventDefault(); const form=event.target; const error=$('modal-error'); if(error)error.classList.add('d-none'); const submit=form.querySelector('[type="submit"]'); if(submit)submit.disabled=true;
 try{
  if(form.matches('[data-create-user-form]')){
    const body={login:form.login.value.trim(),displayName:form.displayName.value.trim(),email:form.email.value.trim(),password:form.password.value,enabled:form.enabled.checked,customInstructions:form.customInstructions.value,promptIds:[...form.querySelectorAll('input[name="promptIds"]:checked')].map(x=>Number(x.value))};
    const created=await api('/api/admin/users',{method:'POST',body:JSON.stringify(body)}); closeModal(); await refreshCore(); state.selectedUserId=created.id;state.selectedUser=created;state.view='user';renderUserList();syncNav();renderWorkspace();toast('Пользователь добавлен');return;
  }
  if(form.matches('[data-prompt-form]')){
    const id=form.dataset.id; const body={name:form.name.value.trim(),description:form.description.value.trim(),systemPrompt:form.systemPrompt.value,knowledgeBase:form.knowledgeBase.value,model:form.model.value.trim(),enabled:form.enabled.checked,sortOrder:Number(form.sortOrder.value||100)};
    await api(id?`/api/admin/prompts/${id}`:'/api/admin/prompts',{method:id?'PUT':'POST',body:JSON.stringify(body)}); closeModal();state.prompts=await api('/api/admin/prompts');await refreshCore();renderWorkspace();toast('Роль сохранена');return;
  }
  if(form.matches('[data-credential-form]')){
    const id=form.dataset.id; const body={name:form.name.value.trim(),apiKey:form.apiKey.value,enabled:form.enabled.checked,maxConcurrentSessions:Number(form.maxConcurrentSessions.value||1)};
    await api(id?`/api/admin/credentials/${id}`:'/api/admin/credentials',{method:id?'PUT':'POST',body:JSON.stringify(body)}); closeModal();await refreshCore();renderWorkspace();toast('AI-ключ сохранён');return;
  }
 }catch(ex){if(error){error.textContent=ex.message;error.classList.remove('d-none');}else toast(ex.message,'error');if(submit)submit.disabled=false;}
}

function emptyCard(title,text){return `<article class="settings-card"><div class="empty-inline"><b>${esc(title)}</b><span>${esc(text)}</span></div></article>`;}
function short(value,max=120){const s=String(value??'').replace(/\s+/g,' ').trim();return s.length<=max?s:s.slice(0,max-1)+'…';}
function formatChars(value){const n=String(value??'').length;return n?`${n.toLocaleString('ru-RU')} симв.`:'пусто';}
function attr(value){return esc(value).replace(/`/g,'&#96;');}
function toast(message,type='ok'){const root=$('toast-root');const el=document.createElement('div');el.className=`toast ${type==='error'?'is-error':''}`;el.textContent=message;root.appendChild(el);requestAnimationFrame(()=>el.classList.add('is-visible'));setTimeout(()=>{el.classList.remove('is-visible');setTimeout(()=>el.remove(),250);},3200);}
