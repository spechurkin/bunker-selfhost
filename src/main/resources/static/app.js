const app = document.querySelector('#app');
const $ = (s) => document.querySelector(s);
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const clone = (v) => structuredClone(v);
const uid = (prefix = 'card') => `${prefix}_${crypto.randomUUID?.() ?? Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('')}`;
const MAX_FILE = 2 * 1024 * 1024;
let classic, library = [], draft, deckKey = 'profession', cardId = null, ticket, room, poll, noticeTimer;
let preview = false, previewRound = 2, previewPhase = 'REVEAL', selectedPack = 'classic', connectionLost = false;
const abilitySelections = new Map();
const generationDrafts = new Map();
const speciesSelections = new Map();
const cardListPositions = new Map();
const phaseNames = {
    LOBBY: 'Сбор игроков',
    EXPLORATION: 'Исследование',
    REVEAL: 'Открытие карт',
    DISCUSSION: 'Обсуждение',
    VOTING: 'Голосование',
    TIE_DEFENSE: 'Защита кандидатов',
    VOTE_RESULT: 'Результат голосования',
    FINISHED: 'Финал'
};
const effects = {
    reveal_self: 'Открыть свою характеристику',
    reveal_target: 'Открыть характеристику другого игрока',
    redraw_self: 'Заменить свою карту из резерва',
    extra_ballot: 'Выдать дополнительные голоса выбранному игроку',
    reveal: 'Открыть характеристику',
    redraw: 'Заменить характеристику из колоды',
    replace: 'Задать новую характеристику (биография, возраст, здоровье…)',
    discard: 'Сбросить характеристику',
    swap: 'Обменяться характеристиками',
    shuffle: 'Перемешать и перераздать характеристики',
    steal: 'Забрать характеристику себе',
    vote_weight: 'Изменить вес голоса',
    cancel_vote: 'Не учитывать голос игрока',
    double_against: 'Умножить голоса против игрока; самому не голосовать',
    forbid_vote: 'Запретить игроку голосовать против владельца карты',
    revote: 'Переголосовать за другого кандидата',
    force_reveal: 'Назначить обязательную характеристику в этом раунде',
    silence: 'Объявить молчание до голосования',
    bunker_redraw: 'Заменить открытую карту бункера',
    bunker_discard: 'Сбросить открытую карту бункера',
    bunker_steal: 'Забрать открытую карту бункера изгнанным',
    add_threat: 'Добавить угрозу для финала',
    protect_vote: 'Обязать себя голосовать против себя при изгнании защищаемого',
    manual: 'Объявить применение; выполнить текст за столом'
};
const timings = {any: 'В любой момент игры', before_vote: 'До голосования', during_vote: 'При обсуждении, голосовании и защите кандидатов', after_vote: 'После голосования, до продолжения игры'};
const targetNames = {self: 'На себя', other: 'На выбранного другого игрока', any: 'На выбранного игрока (можно себя)', neighbor: 'На выбранного игрока слева или справа', all: 'На всех подходящих игроков', none: 'На общее поле / без выбора игрока'};
const playerStates = {active: 'Неизгнанный', exiled: 'Изгнанный', any: 'Любой'};
const visibilityNames = {any: 'Открытая или закрытая', open: 'Только открытая', closed: 'Только закрытая'};
const durations = {vote: 'Одно голосование, включая повтор при ничьей', round: 'До конца раунда', game: 'До конца игры'};
const conditions = {none: 'Нет', left: 'Изгнан игрок слева', right: 'Изгнан игрок справа', first_revealed: 'Изгнан первым открывший характеристику', youngest: 'Изгнан младший с открытым возрастом', oldest: 'Изгнан старший с открытым возрастом'};
const traitEffects = new Set(['reveal', 'reveal_self', 'reveal_target', 'redraw', 'redraw_self', 'replace', 'discard', 'swap', 'shuffle', 'steal', 'force_reveal']);
const fieldEffects = new Set(['revote', 'force_reveal', 'silence', 'bunker_redraw', 'bunker_discard', 'bunker_steal', 'add_threat', 'protect_vote']);
function abilityOptions(card) {
    return {timing: card.effect === 'extra_ballot' ? 'before_vote' : 'any', target: card.effect === 'reveal_target' ? 'other' : 'self', actor: 'active', targetState: 'active', category: '', visibility: 'any', duration: 'vote', condition: 'none', value: 2, compensation: false, ...card.options};
}
function targetChoices(effect) {
    if (['reveal_self', 'redraw_self'].includes(effect)) return ['self'];
    if (fieldEffects.has(effect)) return ['none'];
    if (effect === 'shuffle') return ['all'];
    if (['swap', 'steal', 'forbid_vote', 'double_against', 'reveal_target'].includes(effect)) return ['other', 'any', 'neighbor'];
    if (effect === 'manual') return Object.keys(targetNames);
    return ['self', 'other', 'any', 'neighbor', 'all'];
}
function selectField(id, label, names, value) {
    return `<label class="field"><span>${esc(label)}</span><select id="${id}">${Object.entries(names).map(([key, name]) => `<option value="${esc(key)}" ${key === value ? 'selected' : ''}>${esc(name)}</option>`).join('')}</select></label>`;
}

function notify(message) {
    const n = $('#notice');
    n.textContent = message;
    n.hidden = false;
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => n.hidden = true, 6000);
}

async function api(path, method = 'GET', data) {
    const response = await fetch(`/api${path}`, {
        method,
        headers: {'Content-Type': 'application/json', ...(ticket ? {'Authorization': `Bearer ${ticket.token}`} : {})}, ...(data === undefined ? {} : {body: JSON.stringify(data)})
    });
    const body = await response.json();
    if (!response.ok) {
        const error = new Error(body.message ?? 'Сервер не смог выполнить запрос.');
        error.status = response.status;
        throw error;
    }
    return body;
}

const dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open('bunker-local-packs', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('packs', {keyPath: 'id'});
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
});

async function store(mode, operation) {
    const db = await dbPromise;
    return new Promise((resolve, reject) => {
        const tx = db.transaction('packs', mode), request = operation(tx.objectStore('packs'));
        let result;
        request.onsuccess = () => result = request.result;
        tx.oncomplete = () => resolve(result);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    });
}

const refreshLibrary = async () => library = await store('readonly', s => s.getAll());

function newPack() {
    const p = clone(classic);
    p.id = uid('pack');
    p.name = 'Мой набор правил';
    p.description = '';
    return p;
}

function currentPack() {
    return selectedPack === 'classic' ? classic : library.find(p => p.id === selectedPack);
}

function packForSave() {
    const p = clone(draft);
    if (!p.abilities.enabled) p.abilities.cards = [];
    return p;
}

function renderMenu() {
    if (!currentPack()) selectedPack = 'classic';
    const pack = currentPack() ?? classic;
    app.innerHTML = `<section class="hero"><div class="hero-copy"><div class="eyebrow">За дверью начинается новая жизнь</div><h1>Убедите их.<span>Вы нужны.</span>Выживите.</h1><p class="lead">Мир снаружи больше не будет прежним. Внутри хватит места лишь половине. Откройте свои карты — и докажите, что именно вы должны попасть в бункер.</p><div class="hero-stats"><div><b>5</b><small>раундов до финала</small></div><div><b>6</b><small>характеристик</small></div><div><b>½</b><small>получит место</small></div></div></div><div><section class="entry"><div class="entry-top"><h2>У двери бункера</h2><span class="stamp">Вход свободный</span></div><div class="entry-body"><label class="field"><span>Как вас называть?</span><input id="nickname" placeholder="Ваше имя или псевдоним" maxlength="32" autocomplete="nickname"></label><label class="field"><span>Набор правил</span><select id="ruleset"><option value="classic">Классические правила</option>${library.map(p => `<option value="${esc(p.id)}" ${p.id === selectedPack ? 'selected' : ''}>${esc(p.name)}</option>`).join('')}</select></label><button class="button wide" id="create-room">Создать комнату <span aria-hidden="true">↗</span></button><div class="divider">ИЛИ ПРИСОЕДИНИТЬСЯ</div><label class="field"><span>Код комнаты</span><div class="join-row"><input id="room-code" placeholder="A7X4K2" maxlength="6" autocomplete="off"><button class="button secondary" id="join-room">Войти →</button></div></label></div><div class="entry-note"><strong>Только имя. Только эта игра.</strong><br>Пароли, регистрация и постоянные профили не нужны. ${pack.id === 'classic' ? 'Классические колоды заполнены: 311 карточек третьего издания.' : 'Набор передаётся серверу только при создании комнаты.'}</div></section><button class="demo-link" id="preview">Посмотреть игровое поле →</button></div></section><section class="intro-bottom"><div class="intro-item"><span class="intro-number">01</span><div><h3>Соберите своих</h3><p>Создайте комнату и поделитесь кодом. Создатель управляет ходом игры.</p></div></div><div class="intro-item"><span class="intro-number">02</span><div><h3>Открывайте постепенно</h3><p>Чужие закрытые карты остаются тайной. Даже для администратора.</p></div></div><div class="intro-item"><span class="intro-number">03</span><div><h3>Выберите, кто останется</h3><p>Обсудите кандидатов. Один бюллетень в каждом голосовании. Особые условия могут менять правила.</p></div></div></section>`;
    if (ticket && room) {
        $('#preview').insertAdjacentHTML('beforebegin', `<button class="demo-link" id="resume-room">Вернуться в комнату ${esc(ticket.code)} →</button>`);
        $('#resume-room').onclick = () => { preview = false; location.hash = 'field'; };
    }
    $('#ruleset').value = selectedPack;
    $('#ruleset').onchange = e => selectedPack = e.target.value;
    $('#create-room').onclick = async () => {
        try {
            const p = currentPack();
            const t = await api('/rooms', 'POST', {
                name: $('#nickname').value,
                rulesetId: p.id === 'classic' ? 'classic' : 'custom',
                pack: p.id === 'classic' ? null : p
            });
            await enter(t);
        } catch (e) {
            notify(e.message);
        }
    };
    $('#join-room').onclick = async () => {
        try {
            await enter(await api(`/rooms/${encodeURIComponent($('#room-code').value.trim())}/join`, 'POST', {name: $('#nickname').value}));
        } catch (e) {
            notify(e.message);
        }
    };
    $('#preview').onclick = () => {
        preview = true;
        location.hash = 'field';
    };
}

async function enter(t) {
    ticket = t;
    sessionStorage.setItem('bunker-session', JSON.stringify(t));
    preview = false;
    room = await api(`/rooms/${ticket.code}`);
    location.hash = 'field';
    if (location.hash === '#field') route();
}

async function fetchRoom() {
    if (!ticket || preview) return;
    try {
        const next = await api(`/rooms/${ticket.code}`);
        if (connectionLost) {
            notify('Связь с комнатой восстановлена.');
            connectionLost = false;
        }
        if (!room || next.revision !== room.revision) {
            room = next;
            if (location.hash === '#field') renderField();
        }
    } catch (e) {
        if ([401, 403, 404].includes(e.status)) {
            clearInterval(poll);
            sessionStorage.removeItem('bunker-session');
            ticket = null;
            room = null;
            location.hash = 'menu';
            notify(e.message);
        } else if (!connectionLost) {
            connectionLost = true;
            notify('Связь с сервером прервалась. Повторяем подключение.');
        }
    }
}

async function act(action) {
    try {
        room = await api(`/rooms/${ticket.code}/actions`, 'POST', action);
        renderField();
    } catch (e) {
        notify(e.message);
    }
}

function demonstration() {
    const names = ['Вы', 'Алекс', 'Мира', 'Никита', 'Саша', 'Лера', 'Денис'];
    const labels = ['Инженер', '32 года', 'Без хронических болезней', 'Садоводство', 'Набор инструментов', 'Умеет очищать воду'];
    const players = names.map((name, i) => ({
        id: `demo-${i}`,
        name,
        exiled: false,
        sheet: classic.categories.map((c, j) => ({
            category: c.id,
            name: c.name,
            revealed: j === 0 || (i === 2 && j === 3),
            card: previewPhase === 'FINISHED' || i === 0 || j === 0 || (i === 2 && j === 3) ? {
                id: `d${i}${j}`,
                title: j === 0 && i !== 0 ? ['Врач', 'Учитель', 'Электрик', 'Повар', 'Агроном', 'Механик'][i - 1] : labels[j],
                text: 'Демонстрационная карта, не из оригинальной колоды.'
            } : null
        })), ...(i === 0 ? {
            ability: {
                id: 'demo-ability',
                title: 'Запасной план',
                text: 'Открыть одну дополнительную характеристику своего персонажа.',
                effect: 'reveal_self',
                uses: 1
            }, abilityUses: 0, ballotCount: 1
        } : {})
    }));
    return {
        code: 'МАКЕТ',
        viewerId: 'demo-0',
        hostId: 'demo-0',
        currentPlayerId: 'demo-0',
        phase: previewPhase,
        round: previewRound,
        paused: false,
        pack: {id: 'classic', name: 'Классические правила', rules: classic.rules, abilitiesEnabled: true},
        players,
        catastrophe: {
            title: 'Мир после катастрофы',
            text: 'Здесь появится сценарий из колоды катастроф. Сейчас показана схема расположения карточек.'
        },
        bunker: Array.from({length: 5}, (_, i) => ({
            index: i,
            revealed: i < previewRound,
            card: i < previewRound ? {
                title: ['Запасы', 'Жилой отсек', 'Оборудование', 'Окружение', 'Состояние'][i],
                text: 'Демонстрационный сектор бункера.'
            } : null
        })),
        vote: {
            number: 1,
            eligible: players.map(p => p.id),
            candidates: players.map(p => p.id),
            received: 3,
            required: 7,
            submitted: false,
            runoff: false
        },
        history: []
    };
}

function renderField() {
    const r = preview ? demonstration() : room;
    if (!r) {
        app.innerHTML = '<div class="error-page"><h2>Вы ещё не в комнате</h2><p>Создайте игру или присоединитесь по коду.</p><a href="#menu" class="button">В главное меню</a></div>';
        return;
    }
    const me = r.players.find(p => p.id === r.viewerId), admin = r.hostId === r.viewerId;
    const phases = ['EXPLORATION', 'REVEAL', 'DISCUSSION', 'VOTING', 'FINISHED'];
    const phaseIndex = ['TIE_DEFENSE', 'VOTE_RESULT'].includes(r.phase) ? 3 : phases.indexOf(r.phase);
    app.innerHTML = `${preview ? `<div class="preview-banner"><span><strong>Макет игрового поля.</strong> Имена и карты — примеры для проверки интерфейса.</span><div class="row"><select id="demo-phase" aria-label="Этап макета"><option value="REVEAL">Открытие карт</option><option value="VOTING">Голосование</option><option value="FINISHED">Финал</option></select><button class="button small secondary" id="exit-preview">Закрыть макет</button></div></div>` : ''}<div class="board-top"><div><h1>${r.phase === 'LOBBY' ? 'Собираемся у двери' : phaseNames[r.phase]}</h1><small>${esc(r.pack.name)}${r.paused ? ' / ИГРА НА ПАУЗЕ' : ''}</small></div><div><small>Код комнаты</small><b class="room-code">${esc(r.code)}</b></div><div class="actions">${preview ? '' : '<button class="button secondary small" id="copy-code">Копировать код</button>'}<a class="button secondary small" href="#menu">В меню</a></div></div>${r.phase === 'LOBBY' ? lobby(r, admin) : `<div class="phase-track">${phases.map((p, i) => `<div class="phase-step ${i === phaseIndex ? 'active' : ''}"><span>0${i + 1}</span>${phaseNames[p]}</div>`).join('')}</div><div class="game-layout"><section class="field-area"><div class="panel dark"><div class="eyebrow">Катастрофа</div><h2>${esc(r.catastrophe?.title ?? 'Игра завершена до раздачи')}</h2><p class="muted">${esc(r.catastrophe?.text ?? '')}</p></div><div class="row between sheet"><h2>Исследование бункера</h2><span class="tag">Раунд ${r.round} / ${r.pack.rules.roundCount}</span></div><div class="bunker-grid">${r.bunker.map(b => `<button class="bunker-card ${b.revealed ? '' : 'closed'}" data-bunker="${b.index}" ${b.revealed || r.phase !== 'EXPLORATION' || (!admin && r.currentPlayerId !== me.id) || r.paused ? 'disabled' : ''}>${b.revealed ? `<small>СЕКТОР 0${b.index + 1}</small><b>${esc(b.card.title)}</b>${b.removed ? `<small>${b.removed === 'exiled' ? 'У ИЗГНАННЫХ' : 'СБРОШЕНО'}</small>` : ''}<p>${esc(b.card.text)}</p>` : `<img src="/assets/mark.svg" alt=""><b>Бункер</b><small>СЕКТОР 0${b.index + 1} · ЗАКРЫТО</small>`}</button>`).join('')}</div>${abilityAnnouncements(r)}${votePanel(r, me)}<section class="sheet"><div class="sheet-head"><h2>Ваш персонаж</h2><span class="tag ${me.exiled ? 'exiled' : ''}">${me.exiled ? 'Лист заблокирован' : 'Видно только вам'}</span></div><div class="trait-grid">${me.sheet.map(t => trait(t, r, me)).join('')}</div>${r.pack.abilitiesEnabled ? abilityPanel(r, me) : ''}</section><section class="sheet"><div class="sheet-head"><h2>За общим столом</h2><span class="tag">Только открытые карты</span></div>${r.players.filter(p => p.id !== me.id).map(p => `<div class="public-player"><div class="row between"><b>${esc(p.name)}</b><span class="tag ${p.exiled ? 'exiled' : ''}">${p.exiled ? 'Изгнан' : 'Кандидат в бункер'}</span></div><div class="public-cards">${p.sheet.map(t => `<div class="public-trait"><small>${esc(t.name)}</small>${t.card ? `${esc(t.card.title)}${t.card.text ? `<p>${esc(t.card.text)}</p>` : ''}` : t.available === false ? 'Характеристика отсутствует' : 'Закрыто'}</div>`).join('')}</div></div>`).join('')}</section>${r.history.length ? `<section class="sheet"><h2>Решения группы</h2>${r.history.map(h => `<div class="history"><b>Раунд ${h.round} · голосование ${h.vote}${h.runoff ? ' · повторное' : ''}${h.superseded ? ' · отменено спецкартой' : ''}</b>${h.exiledId ? `Изгнан: ${esc(r.players.find(p => p.id === h.exiledId)?.name)}${h.randomTieBreak ? ' (случайный выбор при повторной ничьей)' : ''}` : 'Ничья — повторное голосование.'}<p>${Object.entries(h.counts).map(([id, n]) => `${esc(r.players.find(p => p.id === id)?.name)}: ${n}`).join(' / ')}</p></div>`).join('')}</section>` : ''}</section><aside>${sidePanel(r, me, admin)}</aside></div>`}`;
    if (preview) {
        $('#demo-phase').value = previewPhase;
        $('#demo-phase').onchange = e => {
            previewPhase = e.target.value;
            renderField();
        };
        $('#exit-preview').onclick = () => {
            preview = false;
            location.hash = 'menu';
        };
    }
    if ($('#copy-code')) $('#copy-code').onclick = async () => {
        try {
            await navigator.clipboard.writeText(r.code);
            notify('Код комнаты скопирован.');
        } catch {
            notify(`Код комнаты: ${r.code}`);
        }
    };
    app.querySelectorAll('[data-action]').forEach(b => b.onclick = () => preview ? notify('Это макет. Создайте комнату для настоящей игры.') : act({type: b.dataset.action}));
    app.querySelectorAll('[data-bunker]').forEach(b => b.onclick = () => act({
        type: 'reveal-bunker',
        index: Number(b.dataset.bunker)
    }));
    app.querySelectorAll('[data-reveal]').forEach(b => b.onclick = () => preview ? notify('В игре эта карта станет видна всей комнате.') : act({
        type: 'reveal',
        category: b.dataset.reveal
    }));
    app.querySelectorAll('[data-kick]').forEach(b => b.onclick = () => act({type: 'kick', targetId: b.dataset.kick}));
    if ($('#cast-vote')) $('#cast-vote').onclick = () => {
        const targets = Array.from(app.querySelectorAll('[data-vote-choice]')).map(s => s.value);
        if (preview) notify('В игре бюллетень отправится один раз, без возможности изменения.'); else act({
            type: 'vote',
            targets
        });
    };
    app.querySelectorAll('[data-use-ability]').forEach(b => {
        const panel = b.closest('.ability');
        const update = () => {
            const target = panel.querySelector('[data-ability-target]');
            b.disabled = b.dataset.abilityBlocked === 'true' || !!target && !target.value;
        };
        panel.querySelectorAll('select').forEach(input => input.onchange = () => {
            abilitySelections.set(panel.dataset.abilityKey, {
                category: panel.querySelector('[data-ability-category]')?.value,
                target: panel.querySelector('[data-ability-target]')?.value,
                index: panel.querySelector('[data-ability-index]')?.value
            });
            update();
        });
        update();
        b.onclick = () => {
            if (preview) notify('В игре эффект проверит и применит сервер.');
            else act({type: 'ability', abilityId: b.dataset.useAbility,
                category: panel.querySelector('[data-ability-category]')?.value,
                targetId: panel.querySelector('[data-ability-target]')?.value,
                index: panel.querySelector('[data-ability-index]') ? Number(panel.querySelector('[data-ability-index]').value) : undefined});
        };
    });
    if ($('#transfer-host')) $('#transfer-host').onclick = () => act({
        type: 'transfer',
        targetId: $('#new-host').value
    });
}

function lobby(r, admin) {
    const n = r.players.length, schedule = r.pack.rules.votingSchedule[String(n)];
    return `<div class="lobby-grid"><section class="panel"><div class="row between"><h2>Участники</h2><span class="tag">${n} / ${r.pack.rules.maxPlayers}</span></div>${r.players.map((p, i) => `<div class="lobby-player"><div class="row"><span class="player-avatar">${String(i + 1).padStart(2, '0')}</span><div><b>${esc(p.name)}</b>${p.id === r.hostId ? '<small> · администратор</small>' : ''}</div></div>${admin && p.id !== r.hostId ? `<button class="button small secondary" data-kick="${p.id}">Удалить</button>` : ''}</div>`).join('')}<p class="muted">Поделитесь кодом комнаты. Вход открыт до начала игры.</p></section><aside class="panel"><h2>Перед стартом</h2><p class="muted">${esc(r.pack.name)}</p><div class="history"><b>Открытие карт</b>${r.pack.rules.roundCount} раундов. ${r.pack.abilitiesEnabled ? 'Особые условия включены.' : 'Без особых условий.'}</div>${schedule ? `<h3>Голосования для ${n} игроков</h3><table class="schedule"><tbody>${schedule.map((v, i) => `<tr><td>Раунд ${i + 1}</td><td>${v || '—'}</td></tr>`).join('')}</tbody></table><p class="muted">Мест: ${n - schedule.reduce((a, b) => a + b, 0)}</p>` : `<p class="muted">Для старта нужно минимум ${r.pack.rules.minPlayers} игрока.</p>`}${admin ? `<button class="button wide" data-action="start" ${n < r.pack.rules.minPlayers ? 'disabled' : ''}>Начать игру →</button>` : '<p class="muted">Администратор начнёт игру, когда все соберутся.</p>'}${r.pack.id === 'classic' ? '<p class="entry-note">Классические карточки готовы к раздаче. Спецкарты применяются один раз за игру согласно их условиям.</p>' : ''}</aside></div>`;
}

function trait(t, r, me) {
    const can = !me.exiled && !me.turnRevealed && !r.paused && r.phase === 'REVEAL' && r.currentPlayerId === me.id && !t.revealed && t.card && (!r.forcedCategory || me.sheet.some(s => s.category === r.forcedCategory && (s.revealed || !s.card)) || t.category === r.forcedCategory) && (r.forcedCategory || r.round !== 1 || !r.pack.rules.firstRevealCategory || me.sheet.some(s => s.category === r.pack.rules.firstRevealCategory && (s.revealed || s.available === false)) || t.category === r.pack.rules.firstRevealCategory);
    return `<article class="trait ${t.revealed ? 'open' : ''}"><div class="trait-top"><span>${esc(t.name)}</span><span>${t.revealed ? 'Открыто' : 'Закрыто'}</span></div><div class="trait-body"><b>${esc(t.card?.title ?? 'Характеристика отсутствует')}</b><p>${esc(t.card?.text ?? '')}</p>${can ? `<button class="button small secondary" data-reveal="${esc(t.category)}">Открыть всем ↗</button>` : ''}</div></article>`;
}

function abilityPanel(r, me) {
    if (!me.ability) return '<div class="ability"><span class="tag">Особые условия</span><p>Карта будет выдана при раздаче.</p></div>';
    return (me.abilities ?? [{card: me.ability, uses: me.abilityUses, unavailable: null}]).map(entry => {
        const a = entry.card, o = abilityOptions(a), used = entry.uses >= 1;
        const key = `${r.code}/${me.id}/${a.id}`, selection = abilitySelections.get(key) ?? {};
        const ids = r.players.map(p => p.id), position = ids.indexOf(me.id);
        const neighbors = [ids[(position + 1) % ids.length], ids[(position + ids.length - 1) % ids.length]];
        const targets = r.players.filter(p => (o.targetState === 'any' || p.exiled === (o.targetState === 'exiled'))
            && (o.target !== 'other' || p.id !== me.id) && (o.target !== 'neighbor' || neighbors.includes(p.id)));
        const needsTarget = ['other', 'any', 'neighbor'].includes(o.target);
        const selectedTarget = targets.some(p => p.id === selection.target) ? selection.target : '';
        const availableBunker = r.bunker.filter(b => b.revealed && !b.removed);
        const bunkerEffect = a.effect.startsWith('bunker_');
        const reason = entry.unavailable ?? (r.paused ? 'Игра приостановлена.' : r.phase === 'FINISHED' ? 'Игра завершена.' : null);
        const blocked = !!(used || reason || needsTarget && !targets.length || bunkerEffect && !availableBunker.length);
        return `<section class="ability" data-ability-key="${esc(key)}"><div class="row between"><span class="tag on">Спец. возможность</span><small>${used ? 'Использовано' : 'Один раз за игру'}</small></div><h3>${esc(a.title)}</h3><p>${esc(a.text)}</p><p class="inline-note">${esc(timings[o.timing])} · ${esc(targetNames[o.target])}${o.actor === 'exiled' ? ' · Только после своего изгнания' : ''}</p>${traitEffects.has(a.effect) ? `<label class="field"><span>Что изменяет?</span><select data-ability-category ${o.category ? 'disabled' : ''}>${me.sheet.filter(t => !o.category || t.category === o.category).map(t => `<option value="${esc(t.category)}" ${selection.category === t.category ? 'selected' : ''}>${esc(t.name)}</option>`).join('')}</select></label>` : ''}${needsTarget ? `<label class="field"><span>На кого применить?</span><select data-ability-target><option value="" ${!selectedTarget ? 'selected' : ''}>Выберите конкретного игрока</option>${targets.map(p => `<option value="${p.id}" ${selectedTarget === p.id ? 'selected' : ''}>${esc(p.name)}${p.id === me.id ? ' · вы' : ''}</option>`).join('')}</select></label>` : ''}${bunkerEffect ? `<label class="field"><span>Открытая карта бункера</span><select data-ability-index>${availableBunker.map(b => `<option value="${b.index}" ${selection.index === String(b.index) ? 'selected' : ''}>${esc(b.card.title)}</option>`).join('')}</select></label>` : ''}${a.effect === 'manual' ? '<p class="inline-note">Применение будет объявлено всем. Участники выполняют текст карты за столом.</p>' : ''}${reason ? `<p class="inline-note">${esc(reason)}</p>` : ''}${a.effect === 'protect_vote' ? '' : `<button class="button small" data-use-ability="${esc(a.id)}" data-ability-blocked="${blocked}" ${blocked || needsTarget && !selectedTarget ? 'disabled' : ''}>${used ? 'Использовано' : 'Применить способность'}</button>`}</section>`;
    }).join('');
}
function timingChoices(effect) {
    if (effect === 'protect_vote') return ['any'];
    if (['extra_ballot', 'force_reveal', 'silence'].includes(effect)) return ['any', 'before_vote'];
    if (['vote_weight', 'cancel_vote', 'double_against'].includes(effect)) return ['any', 'before_vote', 'during_vote'];
    if (effect === 'revote') return ['any', 'during_vote', 'after_vote'];
    return Object.keys(timings);
}

function abilityAnnouncements(r) {
    const events = [...(r.abilityHistory ?? []), ...(r.revealedProtections ?? [])];
    return `${r.silence ? '<div class="panel"><b>Молчание до голосования</b><p>В этом раунде общайтесь жестами и пантомимой.</p></div>' : ''}${r.forcedCategory ? `<div class="panel"><b>Прямой вопрос</b><p>В свой ход откройте: ${esc(r.players[0]?.sheet.find(t => t.category === r.forcedCategory)?.name)} — если карта ещё закрыта.</p></div>` : ''}${events.length ? `<details class="sheet"><summary>Применённые спецкарты · ${events.length}</summary>${events.map(e => `<div class="history"><b>${esc(r.players.find(p => p.id === e.playerId)?.name)} · ${esc(e.ability.title)}</b><p>${esc(e.ability.text)}</p>${e.targets?.length ? `<small>Цель: ${e.targets.map(id => esc(r.players.find(p => p.id === id)?.name)).join(', ')}</small>` : ''}</div>`).join('')}</details>` : ''}${r.activeThreats?.length ? `<section class="panel dark"><h3>Дополнительные угрозы для финала</h3>${r.activeThreats.map(t => `<b>${esc(t.title)}</b><p>${esc(t.text)}</p>`).join('')}</section>` : ''}`;
}

function votePanel(r, me) {
    if (r.phase === 'FINISHED') return '<section class="vote-panel"><h2>Дверь закрыта</h2><p>Все характеристики раскрыты. Обсудите, какую группу удалось собрать.</p></section>';
    if (!['DISCUSSION', 'VOTING', 'TIE_DEFENSE', 'VOTE_RESULT'].includes(r.phase)) return '';
    const allowed = r.vote.eligible.includes(me.id);
    return `<section class="vote-panel"><div class="row between"><h2>${phaseNames[r.phase]}</h2><span class="tag">${r.vote.runoff ? 'Повторное' : `№ ${r.vote.number}`}</span></div>${r.phase === 'VOTING' ? `<p>Принято бюллетеней: ${r.vote.received} / ${r.vote.required}. Выбор других игроков будет виден после завершения.</p>${allowed && !r.vote.submitted ? `${Array.from({length: me.ballotCount}, (_, i) => `<label class="field"><span>Кандидат${me.ballotCount > 1 ? ` · голос ${i + 1}` : ''}</span><select data-vote-choice>${(r.vote.allowedTargets ?? r.vote.candidates.filter(id => r.pack.rules.allowSelfVote || id !== me.id)).map(id => `<option value="${id}">${esc(r.players.find(p => p.id === id)?.name)}</option>`).join('')}</select></label>`).join('')}<button class="button light" id="cast-vote" ${r.paused ? 'disabled' : ''}>Отдать голос · без изменения</button>` : `<p>${r.vote.submitted ? 'Ваш бюллетень принят. Дождитесь остальных.' : 'Вы наблюдаете за голосованием.'}</p>`}` : `<p>${r.phase === 'DISCUSSION' ? `Обсудите кандидатов. На обсуждение по набору правил: ${r.pack.rules.discussionSeconds} секунд.` : r.phase === 'TIE_DEFENSE' ? 'Кандидаты с одинаковым результатом защищают себя. Затем начнётся новый бюллетень только между ними.' : 'Голосование завершено. Результат сохранён в решениях группы.'}</p>`}</section>`;
}

function sidePanel(r, me, admin) {
    const active = r.players.find(p => p.id === r.currentPlayerId);
    const actionLabel = {
        REVEAL: 'Закончить выступление →',
        DISCUSSION: 'Открыть голосование →',
        TIE_DEFENSE: 'Повторить голосование →',
        VOTE_RESULT: 'Продолжить игру →'
    };
    return `<section class="players-panel"><div class="players-title"><span>Участники</span><span>${r.players.filter(p => !p.exiled).length} / ${r.players.length}</span></div>${r.players.map((p, i) => `<div class="player-row ${p.id === r.currentPlayerId ? 'current' : ''}"><span class="player-avatar">${String(i + 1).padStart(2, '0')}</span><div><b>${esc(p.name)}${p.id === me.id ? ' · вы' : ''}</b><small>${p.id === r.hostId ? 'Администратор' : p.exiled ? 'Изгнан' : p.sheet.filter(t => t.revealed).length + ' карт открыто'}</small></div>${p.id === r.currentPlayerId ? '<span class="tag on">Ход</span>' : ''}</div>`).join('')}<div class="side-section"><h3>Сейчас за столом</h3><b>${esc(active?.name ?? phaseNames[r.phase])}</b><p>${r.phase === 'REVEAL' ? `Одна характеристика за раунд. Выступление: ${r.pack.rules.speechSeconds} секунд.` : 'Следуйте этапам игры. Закрытые чужие карты недоступны.'}</p></div>${admin ? `<div class="side-section"><h3>Управление полем</h3><div class="admin-buttons">${actionLabel[r.phase] ? `<button class="button small" data-action="next" ${r.paused ? 'disabled' : ''}>${actionLabel[r.phase]}</button>` : ''}${r.phase !== 'FINISHED' ? `<button class="button small secondary" data-action="${r.paused ? 'resume' : 'pause'}">${r.paused ? 'Продолжить' : 'Пауза'}</button><button class="button small secondary" id="finish-game">Завершить досрочно</button>` : ''}</div><p>Управление этапами не открывает скрытые карты.</p>${!preview ? `<label class="field"><span>Передать управление</span><select id="new-host">${r.players.filter(p => p.id !== me.id).map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select></label><button class="button small secondary" id="transfer-host" ${r.players.length < 2 ? 'disabled' : ''}>Передать</button>` : ''}</div>` : ''}</section>`;
}

function scheduleTable(pack, count = 7) {
    const values = pack.rules.votingSchedule[String(count)] ?? [];
    return `<table class="schedule"><thead><tr><th>Раунд</th>${values.map((_, i) => `<th>${i + 1}</th>`).join('')}<th>Мест</th></tr></thead><tbody><tr><td>Голосований</td>${values.map(v => `<td>${v || '—'}</td>`).join('')}<td>${count - values.reduce((a, b) => a + b, 0)}</td></tr></tbody></table>`;
}

function renderBlueprint(count = 7) {
    app.innerHTML = `<div class="section-head"><div><div class="eyebrow">Схема работы и игры</div><h1>От имени до финала</h1><p>По правилам третьего издания. Особые условия включены в классику. Все решения и права доступа проверяет сервер.</p></div><a class="button secondary" href="#menu">Создать комнату →</a></div><div class="flow"><div class="flow-node"><div class="num">01 / ВХОД</div><b>Имя и код комнаты</b><p>Временная сессия. Создатель становится администратором. Остальные — игроками.</p></div><div class="flow-node"><div class="num">02 / РАЗДАЧА</div><b>6 карт + особое условие</b><p>По одной из каждой категории. Катастрофа открыта, 5 карт бункера закрыты.</p></div><div class="flow-node"><div class="num">03 / РАУНД</div><b>Бункер → персонажи</b><p>Открывается сектор. Каждый по очереди открывает одну характеристику; сначала профессию.</p></div><div class="flow-node"><div class="num">04 / ВЫБОР</div><b>Обсуждение → голос</b><p>По таблице раундов. Один бюллетень. Ничья → повтор; ещё одна ничья → случайный выбор.</p></div><div class="flow-node"><div class="num">05 / ФИНАЛ</div><b>Кто войдёт в бункер?</b><p>После пятого раунда все карты раскрываются. Изгнанные остаются участниками обсуждения.</p></div></div><section class="panel"><div class="row between"><h2>Когда голосуем?</h2><label class="row">Игроков <select id="schedule-count" class="rules-select" aria-label="Количество игроков">${Array.from({length: 13}, (_, i) => `<option value="${i + 4}" ${i + 4 === count ? 'selected' : ''}>${i + 4}</option>`).join('')}</select></label></div>${scheduleTable(classic, count)}<p class="muted">Таблица из правил, стр. 5. Каждый цикл голосования изгоняет одного игрока; повторный бюллетень при ничьей относится к тому же циклу.</p></section><div class="diagram-grid"><section class="panel"><h2>Что кому видно</h2><div class="diagram-line"><b>Свой лист</b><span>все характеристики + условие</span></div><div class="diagram-line"><b>Чужой лист</b><span>только открытые характеристики</span></div><div class="diagram-line"><b>Администратор</b><span>те же ограничения видимости</span></div><p>После изгнания лист блокируется. Последний изгнанный голосует от лица изгнанных согласно стр. 4; менять характеристики он не может. В финале все листы раскрывает сервер.</p></section><section class="panel dark"><h2>Где живут данные</h2><div class="diagram-line"><b>Браузер</b><span>личные наборы правил</span></div><div class="diagram-line"><b>.bunker</b><span>переносимый файл набора</span></div><div class="diagram-line"><b>Java / Docker</b><span>состояние текущей комнаты</span></div><p class="muted">Набор передаётся серверу при создании комнаты. Другим игрокам скачивать его не нужно. После перезапуска сервера временные комнаты исчезают.</p></section></div><section class="panel"><h2>Особые условия и личные наборы</h2><p>В классике каждый получает одно особое условие. В собственном наборе можно отключить их целиком, менять колоды, категории, число раундов, расписание и количество голосов. Условия применяются независимо от очереди, если текст карты и состояние игры это разрешают.</p><p class="muted">Классические колоды заполнены по карточкам третьего издания. Спецкарты меняют характеристики, бункер и голосование по своему тексту; активные угрозы отображаются для финального обсуждения. Режим «История выживания» и вариант на 2–3 игроков пока не реализованы.</p><a class="button secondary" href="#builder">Открыть конструктор →</a></section>`;
    $('#schedule-count').onchange = e => renderBlueprint(Number(e.target.value));
}

function getDeck() {
    if (deckKey === 'threats') return draft.threats ??= [];
    if (deckKey === 'catastrophes' || deckKey === 'bunker') return draft[deckKey];
    if (deckKey === 'abilities') return draft.abilities.cards;
    return draft.categories.find(c => c.id === deckKey)?.cards ?? [];
}

function deckName() {
    return {
        catastrophes: 'Катастрофы',
        bunker: 'Бункер',
        threats: 'Угрозы',
        abilities: 'Особые условия'
    }[deckKey] ?? draft.categories.find(c => c.id === deckKey)?.name ?? '';
}

function abilityEditor(card) {
    const o = abilityOptions(card);
    const targets = Object.fromEntries(targetChoices(card.effect).map(k => [k, targetNames[k]]));
    const categories = {'': 'Выбрать при применении', ...Object.fromEntries(draft.categories.map(c => [c.id, c.name]))};
    return `${selectField('card-effect', 'Что делает способность?', effects, card.effect)}<p class="inline-note">Каждая карта применяется один раз за игру.</p><div class="builder-meta">${selectField('ability-timing', 'Когда применить?', Object.fromEntries(timingChoices(card.effect).map(k => [k, timings[k]])), o.timing)}${selectField('ability-target', 'На кого применить?', targets, o.target)}${selectField('ability-actor', 'Кто может применить?', card.effect === 'protect_vote' ? {any: 'Автоматически, независимо от владельца'} : playerStates, o.actor)}${selectField('ability-target-state', 'Состояние цели', playerStates, o.targetState)}${traitEffects.has(card.effect) || card.effect === 'protect_vote' ? selectField('ability-category', 'Что изменяет / отслеживает?', categories, o.category) : ''}${traitEffects.has(card.effect) ? selectField('ability-visibility', 'Какие характеристики затрагивает?', ['reveal', 'reveal_self', 'reveal_target'].includes(card.effect) ? {any: visibilityNames.any, closed: visibilityNames.closed} : visibilityNames, o.visibility) : ''}${['vote_weight', 'cancel_vote', 'double_against', 'forbid_vote', 'extra_ballot'].includes(card.effect) ? selectField('ability-duration', 'Как долго действует?', durations, o.duration) : ''}${['vote_weight', 'double_against', 'extra_ballot'].includes(card.effect) ? `<label class="field"><span>Вес голоса / число бюллетеней</span><input id="ability-value" type="number" min="${card.effect === 'extra_ballot' ? 2 : 1}" max="5" value="${o.value}"></label>` : ''}${card.effect === 'protect_vote' ? selectField('ability-condition', 'Когда обязать голосовать против себя?', Object.fromEntries(Object.entries(conditions).filter(([k]) => k !== 'none')), o.condition) : ''}</div>${card.effect === 'steal' ? `<label class="toggle"><div><b>Выдать пострадавшему дополнительную спецкарту</b></div><input id="ability-compensation" type="checkbox" ${o.compensation ? 'checked' : ''}></label>` : ''}${card.effect === 'replace' ? `<h3>Новая характеристика</h3><label class="field"><span>Название</span><input id="replacement-title" value="${esc(o.replacement?.title ?? '')}" maxlength="200"></label><label class="field"><span>Описание / биография</span><textarea id="replacement-text" maxlength="4000">${esc(o.replacement?.text ?? '')}</textarea></label><label class="field"><span>Возраст, если карта его содержит</span><input id="replacement-age" type="number" min="0" max="99999" value="${esc(o.replacement?.attributes?.age ?? '')}"></label>` : ''}<p class="inline-note">Название характеристики берётся из вашего набора: можно добавить возраст, биографию и другие категории. Для защиты младшего или старшего заполните возраст в соответствующих картах.</p>`;
}

function cardDeckEditor(deck, card) {
    return `<div class="card-editor"><div><div class="row between"><b>${esc(deckName())}</b><button class="button small secondary" id="add-card">Новая карта +</button></div>${deck.length ? `<div class="card-list" data-scroll-key="${esc(JSON.stringify([draft.id, deckKey]))}">${deck.map(c => `<button class="card-list-item ${c.id === cardId ? 'selected' : ''}" data-card="${esc(c.id)}">${esc(c.title)} <span>↗</span></button>`).join('')}</div>` : '<p class="empty-deck">Колода пока пуста. Добавьте свои карты или загрузите готовый набор.</p>'}${card ? `<label class="field"><span>Название карты</span><input id="card-title" value="${esc(card.title)}" maxlength="200"></label><label class="field"><span>Текст карты</span><textarea id="card-text" maxlength="4000">${esc(card.text)}</textarea></label>${deckKey === 'abilities' ? abilityEditor(card) : `<label class="field"><span>Возраст, если карта его содержит</span><input id="card-age" type="number" min="0" max="99999" value="${esc(card.attributes?.age ?? '')}"></label>`}<div class="actions"><button class="button small" id="save-card">Сохранить карту</button><button class="button small secondary" id="remove-card">Удалить карту</button></div>` : ''}</div><div class="card-preview"><div class="brush">${esc(deckName())}</div><h3 id="preview-title">${esc(card?.title ?? 'Ваша следующая карта')}</h3><p id="preview-text">${esc(card?.text ?? 'Название и описание появятся здесь. В набор можно добавить свои карты или импортировать файл .bunker.')}</p></div></div>`;
}

function biologyCategory(pack) {
    return pack.characterGeneration?.category ?? generationDrafts.get(pack.id)?.category
        ?? pack.categories.find(c => c.id === 'biology' || c.name.toLowerCase() === 'биология')?.id;
}

function speciesDefaults(name = 'Новый вид') {
    // D&D 2014 / TTG Club: humans live less than a century, gnomes almost 500 years.
    const ages = {Эльф: [18, 750], Орк: [12, 50], Гном: [18, 499], Человек: [18, 99]};
    const [minAge, maxAge] = Object.hasOwn(ages, name) ? ages[name] : [18, 80];
    return {species: name, femalePercent: 50, lgbtPercent: 3.8, minAge, maxAge, childfreePercent: 0};
}

function generationSettings(pack) {
    const saved = pack.characterGeneration ?? generationDrafts.get(pack.id);
    const rules = saved?.speciesRules?.map(profile => ({...profile, childfreePercent: profile.childfreePercent ?? 0})) ?? (saved?.species ? saved.species.map(name => ({
        ...speciesDefaults(name), femalePercent: saved.femalePercent, lgbtPercent: saved.lgbtPercent,
        minAge: saved.minAge, maxAge: saved.maxAge, childfreePercent: saved.childfreePercent ?? 0
    })) : ['Эльф', 'Орк', 'Гном', 'Человек'].map(name => speciesDefaults(name)));
    const result = {category: saved?.category ?? biologyCategory(pack), speciesRules: rules};
    generationDrafts.set(pack.id, result);
    return result;
}

function selectedSpecies(pack, g) {
    return Math.min(speciesSelections.get(pack.id) ?? 0, Math.max(0, g.speciesRules.length - 1));
}

function biologyEditor(pack, deck, card) {
    const enabled = !!pack.characterGeneration;
    const mode = selectField('biology-mode', 'Как формировать биологию?', {
        cards: 'Раздавать готовые карточки', species: 'Создавать персонажей по настройкам видов'
    }, enabled ? 'species' : 'cards');
    if (!enabled) return `<div class="biology-editor">${mode}${cardDeckEditor(deck, card)}</div>`;
    const settings = generationSettings(pack), index = selectedSpecies(pack, settings), g = settings.speciesRules[index];
    const percent = value => Number((100 - value).toFixed(1));
    const probability = (key, title, value, remainder, output, accessibleTitle) => `
        <div class="generation-probability">
            <div class="generation-probability-head">
                <label for="generation-${key}">${title}</label>
                <div class="generation-percent-value"><input id="generation-${key}" aria-label="${accessibleTitle} в процентах" ${key === 'lgbt' ? 'aria-describedby="lgbt-note"' : ''} type="number" min="0" max="100" step="0.1" value="${value}"><span aria-hidden="true">%</span></div>
            </div>
            <input id="generation-${key}-range" type="range" aria-label="Вероятность: ${accessibleTitle}" ${key === 'lgbt' ? 'aria-describedby="lgbt-note"' : ''} min="0" max="100" step="0.1" value="${value}">
            <p>${remainder}: <output id="${output}">${percent(value)}</output>%</p>
        </div>`;
    return `<div class="biology-editor">
        ${mode}
        <div class="row between biology-species-heading"><b>Виды персонажей</b><button class="button small secondary" id="add-species">Добавить вид +</button></div>
        <div class="card-list biology-species-list" data-scroll-key="${esc(JSON.stringify([pack.id, deckKey, 'species']))}">
            ${settings.speciesRules.map((profile, i) => `<button class="card-list-item ${i === index ? 'selected' : ''}" data-species-index="${i}" aria-pressed="${i === index}">${esc(profile.species)}<span>${i === index ? 'Выбран' : '↗'}</span></button>`).join('')}
        </div>
        <div class="generation-editor">
            <div class="generation-body">
                <div class="generation-columns">
                    <div class="generation-basics">
                        <label class="field"><span>Название вида</span><input id="species-name" maxlength="64" value="${esc(g.species)}"></label>
                        <div>
                            <div class="generation-age">
                                <label class="field"><span>Возраст от</span><input id="generation-min-age" type="number" min="0" max="99999" step="1" value="${g.minAge}"></label>
                                <label class="field"><span>Возраст до</span><input id="generation-max-age" type="number" min="0" max="99999" step="1" value="${g.maxAge}"></label>
                            </div>
                            <p class="generation-hint">Диапазон только для этого вида, обе границы включены.</p>
                        </div>
                        <p class="generation-hint">При раздаче выбирается вид, затем его возраст, пол, ориентация и признак чайлдфри по заданным здесь параметрам.</p>
                        <button class="button small secondary" id="remove-species" ${settings.speciesRules.length === 1 ? 'disabled' : ''}>Удалить этот вид</button>
                    </div>
                    <div class="generation-probability-column">
                        <div class="generation-probabilities-heading"><b>Вероятности для этого вида</b></div>
                        <div class="generation-probabilities">
                            ${probability('female', 'Женский пол', g.femalePercent, 'Мужской', 'generation-male', 'Женский пол')}
                            ${probability('lgbt', 'ЛГБТ<sup>*</sup>', g.lgbtPercent, 'Натурал', 'generation-straight', 'ЛГБТ')}
                            ${probability('childfree', 'Чайлдфри', g.childfreePercent, 'Не чайлдфри', 'generation-childfree-no', 'Чайлдфри')}
                        </div>
                    </div>
                </div>
                <div class="generation-notes"><p>Виды равновероятны. Вероятности применяются независимо для каждого персонажа выбранного вида. Пол: мужской или женский; ориентация: ЛГБТ<sup>*</sup> или натурал.</p></div>
            </div>
        </div>
    </div>`;
}

function commitBiology() {
    if (!$('#biology-mode')) return;
    const g = generationSettings(draft);
    if ($('#species-name')) {
        const profile = g.speciesRules[selectedSpecies(draft, g)];
        profile.species = $('#species-name').value.trim();
        for (const [id, field] of [['female', 'femalePercent'], ['lgbt', 'lgbtPercent'], ['childfree', 'childfreePercent'], ['min-age', 'minAge'], ['max-age', 'maxAge']]) {
            const value = $(`#generation-${id}`).value;
            profile[field] = value === '' ? null : Number(value);
        }
    }
    generationDrafts.set(draft.id, g);
    if ($('#biology-mode').value === 'species') draft.characterGeneration = g;
    else delete draft.characterGeneration;
}

function renderBuilder() {
    const previousList = app.querySelector('.card-list');
    if (previousList) cardListPositions.set(previousList.dataset.scrollKey, previousList.scrollTop);
    const pagePosition = app.querySelector('.builder-form') ? {left: window.scrollX, top: window.scrollY} : null;
    if (!draft) draft = newPack();
    const readOnly = draft.id === 'classic', deck = getDeck(), card = deck.find(c => c.id === cardId);
    const generation = draft.characterGeneration ? generationSettings(draft) : null;
    const tabs = [...draft.categories.map(c => ({id: c.id, name: c.name,
        count: generation?.category === c.id ? `${generation.speciesRules.length} вид.` : c.cards.length})), {
        id: 'catastrophes',
        name: 'Катастрофы',
        count: draft.catastrophes.length
    }, {id: 'bunker', name: 'Бункер', count: draft.bunker.length}, {id: 'threats', name: 'Угрозы', count: draft.threats?.length ?? 0}, ...(draft.abilities.enabled ? [{
        id: 'abilities',
        name: 'Особые условия',
        count: draft.abilities.cards.length
    }] : [])];
    app.innerHTML = `<div class="section-head"><div><div class="eyebrow">Конструктор наборов правил</div><h1>Ваш сценарий выживания</h1><p>Наполните колоды, настройте раунды и особые условия. Личные наборы хранятся в этом браузере и переносятся файлом .bunker.</p></div><div class="actions"><button class="button secondary" id="import-pack">Загрузить .bunker</button><input type="file" accept=".bunker" id="import-file" hidden><button class="button" id="new-pack">Новый набор +</button></div></div><div class="builder-layout"><aside class="library"><h3>Моя библиотека</h3>${[classic, ...library].map(p => `<button class="pack-link ${p.id === draft.id ? 'selected' : ''}" data-pack="${esc(p.id)}"><b>${esc(p.name)}</b><small>${p.id === 'classic' ? 'Встроенный набор' : 'Локальный набор · .bunker'}</small></button>`).join('')}${!readOnly && !library.some(p => p.id === draft.id) ? '<div class="pack-link selected"><b>Новый набор</b><small>Черновик · ещё не сохранён</small></div>' : ''}<p>Библиотека не отправляется на сервер. При создании игры передаётся только выбранный набор.</p><button class="button small secondary" id="clone-pack">Создать копию</button>${!readOnly && library.some(p => p.id === draft.id) ? '<button class="button small secondary" id="delete-pack">Удалить набор</button>' : ''}</aside><section class="builder-form"><fieldset ${readOnly ? 'disabled' : ''} class="builder-fieldset"><div class="builder-meta"><label class="field"><span>Название набора</span><input id="pack-name" value="${esc(draft.name)}" maxlength="100"></label><label class="field"><span>Первая открываемая категория</span><select id="first-category"><option value="">Любая категория</option>${draft.categories.map(c => `<option value="${esc(c.id)}" ${c.id === draft.rules.firstRevealCategory ? 'selected' : ''}>${esc(c.name)}</option>`).join('')}</select></label><label class="field full"><span>Описание</span><textarea id="pack-description" maxlength="4000">${esc(draft.description)}</textarea></label></div><h2>Ход игры</h2><div class="builder-meta"><label class="field"><span>Раундов</span><input type="number" min="1" max="20" id="round-count" value="${draft.rules.roundCount}"></label><label class="field"><span>Голосов в одном бюллетене</span><input type="number" min="1" max="5" id="votes-per-voter" value="${draft.rules.votesPerVoter}"></label><label class="field"><span>Минимум игроков</span><input type="number" min="4" max="16" id="min-players" value="${draft.rules.minPlayers}"></label><label class="field"><span>Максимум игроков</span><input type="number" min="4" max="16" id="max-players" value="${draft.rules.maxPlayers}"></label><label class="field"><span>Выступление · секунд</span><input type="number" min="0" max="600" id="speech-seconds" value="${draft.rules.speechSeconds}"></label><label class="field"><span>Обсуждение · секунд</span><input type="number" min="0" max="600" id="discussion-seconds" value="${draft.rules.discussionSeconds}"></label></div><label class="toggle"><div><b>Последний изгнанный голосует</b><p>Один представитель изгнанных; его лист остаётся заблокирован.</p></div><input type="checkbox" id="last-exiled-votes" ${draft.rules.lastExiledVotes ? 'checked' : ''}></label><label class="toggle"><div><b>Разрешить голосование за себя</b></div><input type="checkbox" id="allow-self-vote" ${draft.rules.allowSelfVote ? 'checked' : ''}></label><details><summary>Расписание голосований по числу игроков</summary><div class="table-scroll"><table class="schedule"><thead><tr><th>Игроков</th>${Array.from({length: draft.rules.roundCount}, (_, i) => `<th>Раунд ${i + 1}</th>`).join('')}</tr></thead><tbody>${Object.entries(draft.rules.votingSchedule).filter(([n]) => Number(n) >= draft.rules.minPlayers && Number(n) <= draft.rules.maxPlayers).sort((a, b) => Number(a[0]) - Number(b[0])).map(([n, values]) => `<tr><td>${n}</td>${values.map((v, i) => `<td><input type="number" min="0" max="4" value="${v}" data-schedule="${n}" data-round="${i}" aria-label="Голосований для ${n} игроков в раунде ${i + 1}"></td>`).join('')}</tr>`).join('')}</tbody></table></div></details><label class="toggle"><div><b>Особые условия</b><p>В классике включены. В вашем наборе можно полностью отключить.</p></div><input type="checkbox" id="abilities-enabled" ${draft.abilities.enabled ? 'checked' : ''}></label><div class="row between decks-head"><h2>Колоды и характеристики</h2><div class="actions"><button class="button small secondary" id="add-category">Добавить категорию +</button>${draft.categories.some(c => c.id === deckKey) ? '<button class="button small secondary" id="rename-category">Переименовать</button><button class="button small secondary" id="remove-category">Удалить категорию</button>' : ''}</div></div><div class="decks-tabs">${tabs.map(t => `<button class="deck-tab ${t.id === deckKey ? 'selected' : ''}" data-deck="${esc(t.id)}">${esc(t.name)} <small>${t.count}</small></button>`).join('')}</div>${!readOnly && deckKey === biologyCategory(draft) ? biologyEditor(draft, deck, card) : cardDeckEditor(deck, card)}</fieldset><div class="builder-save"><p class="inline-note">${readOnly ? 'Встроенная классика доступна для просмотра. Для изменений создайте личную копию.' : 'Черновик можно сохранить без карточек. Для старта сервер проверит достаточность каждой колоды.'}</p><div class="actions">${readOnly ? '' : '<button class="button secondary" id="save-pack">Сохранить в браузере</button>'}<button class="button" id="export-pack">Скачать .bunker ↓</button></div></div></section></div><footer class="builder-legal-note" id="lgbt-note" aria-label="Правовая информация редактора"><p><sup>*</sup> Решением Верховного Суда РФ от 30 ноября 2023 года «Международное общественное движение ЛГБТ» и его структурные подразделения признаны экстремистской организацией; их деятельность в России запрещена. <a href="https://minjust.gov.ru/ru/documents/7822/" target="_blank" rel="noopener noreferrer">Перечень Минюста России</a>.</p><p>Сведения об ориентации используются как характеристики вымышленных игровых персонажей. <a href="https://publication.pravo.gov.ru/Document/View/0001201306300001" target="_blank" rel="noopener noreferrer">Федеральный закон от 29.06.2013 № 135-ФЗ</a>.</p></footer>`;
    // Просмотр встроенного набора допускает переключение колод, без изменения содержимого.
    if (readOnly) {
        $('.builder-fieldset').disabled = false;
        app.querySelectorAll('.builder-fieldset input,.builder-fieldset textarea,.builder-fieldset select,.builder-fieldset button:not([data-deck]):not([data-card])').forEach(c => c.disabled = true);
    }
    bindBuilder(readOnly);
    const list = app.querySelector('.card-list');
    if (list) list.scrollTop = cardListPositions.get(list.dataset.scrollKey) ?? 0;
    if (pagePosition) window.scrollTo({...pagePosition, behavior: 'instant'});
}

function commitMeta() {
    if (!$('#pack-name') || draft.id === 'classic') return;
    draft.name = $('#pack-name').value;
    draft.description = $('#pack-description').value;
    draft.rules.firstRevealCategory = $('#first-category').value;
    for (const [id, key] of [['round-count', 'roundCount'], ['votes-per-voter', 'votesPerVoter'], ['min-players', 'minPlayers'], ['max-players', 'maxPlayers'], ['speech-seconds', 'speechSeconds'], ['discussion-seconds', 'discussionSeconds']]) draft.rules[key] = Number($(`#${id}`).value);
    draft.rules.lastExiledVotes = $('#last-exiled-votes').checked;
    draft.rules.allowSelfVote = $('#allow-self-vote').checked;
    draft.abilities.enabled = $('#abilities-enabled').checked;
    commitBiology();
    app.querySelectorAll('[data-schedule]').forEach(input => draft.rules.votingSchedule[input.dataset.schedule][Number(input.dataset.round)] = Number(input.value));
}

function commitCard() {
    const card = getDeck().find(c => c.id === cardId);
    if (!card || !$('#card-title') || draft.id === 'classic') return;
    card.title = $('#card-title').value;
    card.text = $('#card-text').value;
    if (deckKey === 'abilities') {
        card.effect = $('#card-effect').value;
        card.uses = 1;
        const o = abilityOptions(card);
        for (const key of ['timing', 'target', 'actor', 'targetState', 'category', 'visibility', 'duration', 'condition']) {
            const id = key === 'targetState' ? 'target-state' : key;
            const input = $(`#ability-${id}`);
            if (input) o[key] = input.value;
        }
        if (!targetChoices(card.effect).includes(o.target)) o.target = targetChoices(card.effect)[0];
        if (!timingChoices(card.effect).includes(o.timing)) o.timing = timingChoices(card.effect)[0];
        if (card.effect === 'protect_vote') o.actor = 'any';
        if (['reveal', 'reveal_self', 'reveal_target'].includes(card.effect) && o.visibility === 'open') o.visibility = 'closed';
        if (card.effect !== 'protect_vote') o.condition = 'none';
        else if (o.condition === 'none') o.condition = 'left';
        o.compensation = card.effect === 'steal' && !!$('#ability-compensation')?.checked;
        if ($('#ability-value')) o.value = Number($('#ability-value').value);
        if (card.effect === 'replace') {
            const attributes = {...o.replacement?.attributes};
            const age = $('#replacement-age')?.value;
            if (age) attributes.age = age; else delete attributes.age;
            o.replacement = {id: o.replacement?.id ?? uid('replacement'), title: $('#replacement-title')?.value ?? '', text: $('#replacement-text')?.value ?? '', attributes};
        } else delete o.replacement;
        card.options = o;
    } else if ($('#card-age')) {
        card.attributes = {...card.attributes};
        if ($('#card-age').value) card.attributes.age = $('#card-age').value;
        else delete card.attributes.age;
    }
}

function commitBuilder() {
    commitCard();
    commitMeta();
}

function normalizeSchedule() {
    const n = Math.max(1, Math.min(20, draft.rules.roundCount));
    draft.rules.roundCount = n;
    for (let p = 4; p <= 16; p++) {
        const previous = draft.rules.votingSchedule[String(p)] ?? [];
        draft.rules.votingSchedule[String(p)] = Array.from({length: n}, (_, i) => previous[i] ?? 0);
    }
}

async function validateAndSave() {
    commitBuilder();
    const p = packForSave();
    await api('/packs/validate', 'POST', p);
    await store('readwrite', s => s.put(p));
    await refreshLibrary();
    return p;
}

function bindBuilder(readOnly) {
    app.querySelectorAll('[data-pack]').forEach(b => b.onclick = () => {
        commitBuilder();
        draft = clone([classic, ...library].find(p => p.id === b.dataset.pack));
        deckKey = draft.categories[0].id;
        cardId = null;
        renderBuilder();
    });
    $('#new-pack').onclick = () => {
        draft = newPack();
        deckKey = draft.categories[0].id;
        cardId = null;
        renderBuilder();
    };
    $('#clone-pack').onclick = () => {
        commitBuilder();
        draft = clone(draft);
        draft.id = uid('pack');
        draft.name += ' · копия';
        renderBuilder();
    };
    if ($('#delete-pack')) $('#delete-pack').onclick = async () => {
        if (confirm(`Удалить локальный набор «${draft.name}»?`)) {
            await store('readwrite', s => s.delete(draft.id));
            await refreshLibrary();
            draft = newPack();
            renderBuilder();
        }
    };
    app.querySelectorAll('[data-deck]').forEach(b => b.onclick = () => {
        commitBuilder();
        deckKey = b.dataset.deck;
        cardId = null;
        renderBuilder();
    });
    app.querySelectorAll('[data-card]').forEach(b => b.onclick = () => {
        commitBuilder();
        cardId = b.dataset.card;
        renderBuilder();
        $('.card-list-item.selected')?.focus({preventScroll: true});
    });
    $('#import-pack').onclick = () => $('#import-file').click();
    $('#import-file').onchange = async e => {
        try {
            const file = e.target.files[0];
            if (!file) return;
            if (!file.name.toLowerCase().endsWith('.bunker')) throw new Error('Выберите файл .bunker.');
            if (file.size > MAX_FILE) throw new Error('Размер файла должен быть не больше 2 МБ.');
            const p = JSON.parse(await file.text());
            await api('/packs/validate', 'POST', p);
            if (library.some(x => x.id === p.id) && !confirm('Набор с этим ID существует. Заменить его?')) return;
            await store('readwrite', s => s.put(p));
            await refreshLibrary();
            draft = p;
            deckKey = p.categories[0].id;
            cardId = null;
            renderBuilder();
            notify('Набор добавлен в локальную библиотеку.');
        } catch (error) {
            notify(error.message);
        }
    };
    $('#export-pack').onclick = async () => {
        try {
            const p = readOnly ? {
                ...clone(classic),
                id: 'classic-template',
                name: 'Классические правила · шаблон'
            } : await validateAndSave();
            const blob = new Blob([JSON.stringify(p, null, 2)], {type: 'application/json;charset=utf-8'});
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `${p.id}.bunker`;
            a.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            notify('Файл .bunker готов.');
        } catch (e) {
            notify(e.message);
        }
    };
    if (readOnly) return;
    if ($('#biology-mode')) $('#biology-mode').onchange = () => { commitBuilder(); cardId = null; renderBuilder(); };
    app.querySelectorAll('[data-species-index]').forEach(button => button.onclick = () => {
        commitBuilder();
        speciesSelections.set(draft.id, Number(button.dataset.speciesIndex));
        renderBuilder();
        app.querySelector('[data-species-index].selected')?.focus({preventScroll: true});
    });
    if ($('#add-species')) $('#add-species').onclick = () => {
        commitBuilder();
        const g = generationSettings(draft);
        if (g.speciesRules.length >= 30) return notify('В одном наборе допускается до 30 видов.');
        let name = 'Новый вид', suffix = 2;
        while (g.speciesRules.some(p => p.species.toLowerCase() === name.toLowerCase())) name = `Новый вид ${suffix++}`;
        g.speciesRules.push(speciesDefaults(name));
        draft.characterGeneration = g;
        speciesSelections.set(draft.id, g.speciesRules.length - 1);
        renderBuilder();
        $('#species-name').focus({preventScroll: true});
    };
    if ($('#remove-species')) $('#remove-species').onclick = () => {
        commitBuilder();
        const g = generationSettings(draft), index = selectedSpecies(draft, g);
        if (g.speciesRules.length <= 1) return;
        g.speciesRules.splice(index, 1);
        draft.characterGeneration = g;
        speciesSelections.set(draft.id, Math.min(index, g.speciesRules.length - 1));
        renderBuilder();
    };
    if ($('#species-name')) $('#species-name').oninput = e => {
        app.querySelector('[data-species-index].selected').firstChild.textContent = e.target.value;
    };
    for (const [key, output] of [['female', 'generation-male'], ['lgbt', 'generation-straight'], ['childfree', 'generation-childfree-no']]) {
        for (const id of [`generation-${key}`, `generation-${key}-range`]) if ($(`#${id}`)) $(`#${id}`).oninput = e => {
            $(`#generation-${key}`).value = e.target.value;
            $(`#generation-${key}-range`).value = e.target.value;
            $(`#${output}`).textContent = Number((100 - Number(e.target.value)).toFixed(1));
        };
    }
    if ($('#card-effect')) $('#card-effect').onchange = () => { commitBuilder(); renderBuilder(); };
    $('#save-pack').onclick = async () => {
        try {
            await validateAndSave();
            renderBuilder();
            notify('Набор сохранён в этом браузере.');
        } catch (e) {
            notify(e.message);
        }
    };
    $('#abilities-enabled').onchange = () => {
        commitBuilder();
        if (!draft.abilities.enabled && deckKey === 'abilities') deckKey = draft.categories[0]?.id ?? 'bunker';
        renderBuilder();
    };
    for (const id of ['round-count', 'min-players', 'max-players']) $(`#${id}`).onchange = () => {
        commitBuilder();
        normalizeSchedule();
        renderBuilder();
    };
    if ($('#add-card')) $('#add-card').onclick = () => {
        commitBuilder();
        const c = {
            id: uid(deckKey),
            title: 'Новая карта',
            text: '', ...(deckKey === 'abilities' ? {effect: 'reveal_self', uses: 1} : {})
        };
        getDeck().push(c);
        cardId = c.id;
        renderBuilder();
    };
    if ($('#save-card')) $('#save-card').onclick = () => {
        commitBuilder();
        renderBuilder();
        notify('Карта обновлена в черновике. Сохраните набор.');
    };
    if ($('#remove-card')) $('#remove-card').onclick = () => {
        const index = getDeck().findIndex(c => c.id === cardId);
        if (index >= 0) getDeck().splice(index, 1);
        cardId = null;
        renderBuilder();
    };
    if ($('#card-title')) $('#card-title').oninput = e => $('#preview-title').textContent = e.target.value;
    if ($('#card-text')) $('#card-text').oninput = e => $('#preview-text').textContent = e.target.value;
    $('#add-category').onclick = () => {
        commitBuilder();
        const name = prompt('Название новой характеристики:');
        if (!name?.trim()) return;
        const c = {id: uid('category'), name: name.trim(), cards: []};
        draft.categories.push(c);
        deckKey = c.id;
        cardId = null;
        renderBuilder();
    };
    if ($('#rename-category')) $('#rename-category').onclick = () => {
        commitBuilder();
        const category = draft.categories.find(c => c.id === deckKey);
        const name = prompt('Название характеристики:', category.name);
        if (name?.trim()) {
            category.name = name.trim();
            renderBuilder();
        }
    };
    if ($('#remove-category')) $('#remove-category').onclick = () => {
        commitBuilder();
        if (!confirm('Удалить категорию со всеми её картами?')) return;
        draft.categories = draft.categories.filter(c => c.id !== deckKey);
        if (draft.characterGeneration?.category === deckKey || generationDrafts.get(draft.id)?.category === deckKey) {
            delete draft.characterGeneration; generationDrafts.delete(draft.id); speciesSelections.delete(draft.id);
        }
        if (draft.rules.firstRevealCategory === deckKey) draft.rules.firstRevealCategory = '';
        deckKey = draft.categories[0]?.id ?? 'bunker';
        cardId = null;
        renderBuilder();
    };
}

function route() {
    if ($('#pack-name')) commitBuilder();
    clearInterval(poll);
    const page = location.hash.slice(1) || 'menu';
    document.querySelectorAll('[data-nav]').forEach(a => a.classList.toggle('active', a.dataset.nav === page));
    if (page === 'builder') renderBuilder(); else if (page === 'blueprint') renderBlueprint(); else if (page === 'field') {
        renderField();
        if (!preview && ticket) {
            fetchRoom();
            poll = setInterval(fetchRoom, 1500);
        }
    } else renderMenu();
}

// Событие делегируется: кнопка остаётся рабочей после обновления состояния комнаты.
app.addEventListener('click', e => {
    if (e.target.closest('#finish-game')) {
        if (preview) notify('Это макет игрового поля.'); else if (confirm('Завершить игру досрочно и открыть все характеристики?')) act({type: 'finish'});
    }
});
window.addEventListener('hashchange', () => {
    route();
    window.scrollTo(0, 0);
});
try {
    classic = await api('/rules/classic');
    try {
        await refreshLibrary();
    } catch {
        notify('Локальная библиотека недоступна в этом режиме браузера.');
    }
    try {
        ticket = JSON.parse(sessionStorage.getItem('bunker-session'));
        if (ticket) room = await api(`/rooms/${ticket.code}`);
    } catch {
        ticket = null;
        sessionStorage.removeItem('bunker-session');
    }
    route();
} catch (e) {
    app.innerHTML = '<div class="error-page"><h2>Сервер ещё не запущен</h2><p>Запустите приложение через Docker Compose или Java, затем обновите страницу.</p><button class="button" id="reload">Повторить подключение</button></div>';
    $('#reload').onclick = () => location.reload();
}
