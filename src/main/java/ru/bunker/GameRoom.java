package ru.bunker;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Все обращения выполняются под блокировкой комнаты в RoomService.
 */
final class GameRoom {
    final String code;
    final RulePack pack;
    final Map<String, Player> players = new LinkedHashMap<>();
    final List<RulePack.Card> bunker = new ArrayList<>();
    final Set<Integer> openBunker = new HashSet<>();
    final Map<String, Deque<RulePack.Card>> remaining = new HashMap<>();
    final Map<String, List<String>> ballots = new LinkedHashMap<>();
    final Set<String> eligible = new LinkedHashSet<>(), candidates = new LinkedHashSet<>(), turnsDone = new HashSet<>();
    final List<Map<String, Object>> history = new ArrayList<>();
    final List<Map<String, Object>> abilityHistory = new ArrayList<>();
    final List<RulePack.Card> activeThreats = new ArrayList<>();
    final Map<Integer, String> removedBunker = new HashMap<>();
    final Deque<RulePack.Card> bunkerReserve = new ArrayDeque<>();
    final Deque<RulePack.Ability> abilityReserve = new ArrayDeque<>();
    final Map<String, List<String>> revealOrder = new HashMap<>();
    final List<VoteModifier> modifiers = new ArrayList<>();
    final List<Protection> pendingProtections = new ArrayList<>();
    final Map<String, List<String>> revoteForbidden = new HashMap<>();
    final Set<String> forcedVoters = new HashSet<>();
    String forcedCategory;
    int forcedCategoryRound, silenceRound;
    String previousExiledId;
    int voteSerial = 1;
    final SecureRandom random = new SecureRandom();
    RulePack.Card catastrophe;
    String hostId, currentPlayerId, starterId, lastExiledId;
    Phase phase = Phase.LOBBY;
    boolean paused, runoff;
    int round, voteNumber;
    long revision;
    volatile Instant touched = Instant.now();
    Instant phaseStarted = Instant.now();

    GameRoom(String code, RulePack pack) {
        this.code = code;
        this.pack = pack;
    }

    Player player(String id) {
        var p = players.get(id);
        if (p == null) throw new GameException(403, "Вы не участник этой комнаты.");
        return p;
    }

    void start() {
        requirePhase(Phase.LOBBY);
        pack.validate(true, players.size());
        for (var category : pack.categories()) {
            if (pack.generatesCategory(category.id())) {
                for (var p : players.values()) p.sheet.put(category.id(), generateBiology());
                remaining.put(category.id(), new ArrayDeque<>());
                continue;
            }
            var deck = new ArrayList<>(category.cards());
            Collections.shuffle(deck, random);
            for (var p : players.values()) p.sheet.put(category.id(), deck.removeLast());
            remaining.put(category.id(), new ArrayDeque<>(deck));
        }
        catastrophe = pack.catastrophes().get(random.nextInt(pack.catastrophes().size()));
        var bunkerDeck = new ArrayList<>(pack.bunker());
        Collections.shuffle(bunkerDeck, random);
        bunker.addAll(bunkerDeck.subList(0, pack.rules().roundCount()));
        bunkerReserve.addAll(bunkerDeck.subList(pack.rules().roundCount(), bunkerDeck.size()));
        if (pack.abilities().enabled()) {
            var abilities = new ArrayList<>(pack.abilities().cards());
            Collections.shuffle(abilities, random);
            for (var p : players.values()) p.ability = abilities.removeLast();
            abilityReserve.addAll(abilities);
        }
        round = 1;
        starterId = hostId;
        currentPlayerId = starterId;
        setPhase(Phase.EXPLORATION);
    }

    private RulePack.Card generateBiology() {
        var profiles = pack.characterGeneration().profiles();
        var g = profiles.get(random.nextInt(profiles.size()));
        String species = g.species();
        boolean female = random.nextDouble() * 100 < g.femalePercent();
        boolean lgbt = random.nextDouble() * 100 < g.lgbtPercent();
        boolean childfree = random.nextDouble() * 100 < g.childfreePercent();
        int age = g.minAge() + random.nextInt(g.maxAge() - g.minAge() + 1);
        return new RulePack.Card("generated-" + UUID.randomUUID(), species + " · " + (female ? "Женщина" : "Мужчина"),
                "Возраст: " + age + "\nОриентация: " + (lgbt ? "ЛГБТ" : "Натурал")
                        + "\nЧайлдфри: " + (childfree ? "да" : "нет"),
                Map.of("species", species, "sex", female ? "female" : "male", "orientation", lgbt ? "lgbt" : "straight", "age", String.valueOf(age),
                        "childfree", String.valueOf(childfree)));
    }

    void revealBunker(int index) {
        requirePhase(Phase.EXPLORATION);
        RulePack.require(index >= 0 && index < bunker.size() && !openBunker.contains(index), "Выберите закрытую карту бункера.");
        openBunker.add(index);
        turnsDone.clear();
        setPhase(Phase.REVEAL);
    }

    void reveal(String playerId, String category) {
        requirePhase(Phase.REVEAL);
        var p = activePlayer(playerId);
        RulePack.require(playerId.equals(currentPlayerId), "Дождитесь своего хода.");
        RulePack.require(!turnsDone.contains(playerId), "Вы уже открыли карту в этом раунде.");
        if (forcedCategoryRound == round && forcedCategory != null && !p.revealed.contains(forcedCategory) && p.sheet.containsKey(forcedCategory))
            RulePack.require(forcedCategory.equals(category), "По особому условию откройте выбранную характеристику.");
        else if (round == 1 && p.sheet.containsKey(pack.rules().firstRevealCategory()) && !p.revealed.contains(pack.rules().firstRevealCategory()))
            RulePack.require(pack.rules().firstRevealCategory().equals(category), "В первом раунде откройте «" + pack.rules().firstRevealCategory() + "».");
        revealCard(p, category);
        turnsDone.add(playerId);
    }

    void next() {
        switch (phase) {
            case REVEAL -> {
                RulePack.require(turnsDone.contains(currentPlayerId) || player(currentPlayerId).sheet.keySet().stream().allMatch(player(currentPlayerId).revealed::contains), "Активный игрок должен открыть одну характеристику.");
                turnsDone.add(currentPlayerId);
                if (players.values().stream().filter(p -> !p.exiled).allMatch(p -> turnsDone.contains(p.id))) {
                    voteNumber = 1;
                    if (votesThisRound() > 0) setPhase(Phase.DISCUSSION);
                    else nextRound();
                } else {
                    currentPlayerId = nextActive(currentPlayerId);
                    phaseStarted = Instant.now();
                }
            }
            case DISCUSSION -> beginBallot(false);
            case TIE_DEFENSE -> beginBallot(true);
            case VOTE_RESULT -> {
                commitProtections();
                if (voteNumber < votesThisRound()) {
                    voteNumber++;
                    setPhase(Phase.DISCUSSION);
                } else nextRound();
            }
            default -> throw new GameException(409, "Сейчас нельзя перейти дальше. Завершите текущий этап.");
        }
    }

    int votesThisRound() {
        return pack.rules().votingSchedule().get(String.valueOf(players.size())).get(round - 1);
    }

    private void nextRound() {
        if (round == pack.rules().roundCount()) {
            setPhase(Phase.FINISHED);
            currentPlayerId = null;
            return;
        }
        round++;
        starterId = nextActive(starterId);
        currentPlayerId = starterId;
        setPhase(Phase.EXPLORATION);
    }

    private void beginBallot(boolean tie) {
        ballots.clear();
        eligible.clear();
        for (var p : players.values())
            if ((!p.exiled || (pack.rules().lastExiledVotes() && p.id.equals(lastExiledId))) && modifierValue("no_vote", p.id) == 0) eligible.add(p.id);
        if (!tie) {
            candidates.clear();
            players.values().stream().filter(p -> !p.exiled).forEach(p -> candidates.add(p.id));
        }
        runoff = tie;
        if (!tie) forcedVoters.clear();
        for (var p : players.values()) if (eligible.contains(p.id) && !p.exiled && p.forcedSelfVote) {
            forcedVoters.add(p.id);
            p.forcedSelfVote = false;
        }
        forcedVoters.stream().filter(eligible::contains).forEach(candidates::add);
        setPhase(Phase.VOTING);
    }

    void vote(String voterId, List<String> targets) {
        requirePhase(Phase.VOTING);
        var p = player(voterId);
        RulePack.require(eligible.contains(voterId), "У вас нет права голоса в этом голосовании.");
        if (ballots.containsKey(voterId)) throw new GameException(409, "Ваш голос уже принят. Изменить его нельзя.");
        RulePack.require(targets != null && targets.size() == ballotCount(p), "Отправьте ровно положенное число голосов.");
        RulePack.require(targets.stream().allMatch(candidates::contains), "Голосовать можно только за доступных кандидатов.");
        RulePack.require(targets.stream().allMatch(id -> canVoteFor(voterId, id)), "Этот кандидат недоступен по правилам или особому условию.");
        ballots.put(voterId, List.copyOf(targets));
        if (eligible.stream().allMatch(ballots::containsKey)) finishBallot();
    }

    private void finishBallot() {
        var counts = new LinkedHashMap<String, Integer>();
        candidates.forEach(id -> counts.put(id, 0));
        ballots.forEach((voter, votes) -> {
            if (modifierValue("cancel_vote", voter) > 0 || modifierValue("no_vote", voter) > 0) return;
            int weight = Math.max(1, modifierValue("vote_weight", voter));
            votes.forEach(id -> counts.merge(id, weight * Math.max(1, modifierValue("double_against", id)), Integer::sum));
        });
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        var tied = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("round", round);
        result.put("vote", voteNumber);
        result.put("runoff", runoff);
        result.put("counts", counts);
        result.put("ballots", new LinkedHashMap<>(ballots));
        if (tied.size() > 1 && !runoff) {
            candidates.clear();
            candidates.addAll(tied);
            result.put("tied", tied);
            history.add(result);
            setPhase(Phase.TIE_DEFENSE);
            return;
        }
        var loser = tied.get(random.nextInt(tied.size()));
        previousExiledId = lastExiledId;
        pendingProtections.clear();
        for (var owner : players.values()) for (var ability : ownedAbilities(owner)) {
            if (ability.effect().equals("protect_vote") && uses(owner, ability) == 0
                    && protectionTargets(owner, ability).contains(loser)) pendingProtections.add(new Protection(owner.id, ability));
        }
        player(loser).exiled = true;
        lastExiledId = loser;
        result.put("exiledId", loser);
        result.put("randomTieBreak", tied.size() > 1);
        history.add(result);
        setPhase(Phase.VOTE_RESULT);
    }

    void useAbility(String playerId, String category, String targetId) {
        useAbility(playerId, category, targetId, null, null);
    }

    void useAbility(String playerId, String category, String targetId, Integer index, String abilityId) {
        var p = player(playerId);
        var a = ownedAbilities(p).stream().filter(c -> abilityId == null ? c == p.ability : c.id().equals(abilityId))
                .findFirst().orElseThrow(() -> new GameException(400, "Эта возможность вам не принадлежит."));
        String unavailable = abilityUnavailable(p, a);
        RulePack.require(unavailable == null, unavailable == null ? "" : unavailable);
        var o = a.options();
        String chosenCategory = o.category().isEmpty() ? category : o.category();
        if (!o.category().isEmpty() && category != null && !category.isEmpty())
            RulePack.require(o.category().equals(category), "Карта изменяет другую характеристику.");
        var targets = abilityTargets(p, a, targetId, chosenCategory);
        RulePack.require(o.target().equals("none") || !targets.isEmpty(), "Нет подходящих игроков.");
        var effect = a.effect();
        if (Set.of("reveal", "reveal_self", "reveal_target", "redraw", "redraw_self", "replace", "discard", "swap", "shuffle", "steal").contains(effect)) {
            RulePack.require(!targets.isEmpty(), "Нет подходящих игроков с этой характеристикой.");
            RulePack.require(chosenCategory != null && pack.categories().stream().anyMatch(c -> c.id().equals(chosenCategory)), "Выберите характеристику.");
            for (var t : targets) checkTrait(t, chosenCategory, o.visibility());
            if (Set.of("swap", "steal").contains(effect)) {
                RulePack.require(targets.size() == 1 && targets.getFirst() != p, "Выберите другого игрока.");
                if (effect.equals("swap")) checkTrait(p, chosenCategory, o.visibility());
            }
        }
        switch (effect) {
            case "reveal", "reveal_self", "reveal_target" -> {
                RulePack.require(targets.stream().allMatch(t -> !t.revealed.contains(chosenCategory)), "Эта характеристика уже открыта.");
                targets.forEach(t -> revealCard(t, chosenCategory));
            }
            case "redraw", "redraw_self" -> {
                if (pack.generatesCategory(chosenCategory)) targets.forEach(t -> t.sheet.put(chosenCategory, generateBiology()));
                else {
                    var deck = remaining.get(chosenCategory);
                    RulePack.require(deck != null && deck.size() >= targets.size(), "В этой колоде недостаточно запасных карт.");
                    targets.forEach(t -> t.sheet.put(chosenCategory, deck.removeFirst()));
                }
            }
            case "replace" -> targets.forEach(t -> t.sheet.put(chosenCategory, o.replacement()));
            case "discard" -> targets.forEach(t -> t.sheet.remove(chosenCategory));
            case "swap" -> {
                var t = targets.getFirst();
                var old = p.sheet.get(chosenCategory);
                p.sheet.put(chosenCategory, t.sheet.get(chosenCategory));
                t.sheet.put(chosenCategory, old);
            }
            case "shuffle" -> {
                RulePack.require(targets.size() >= 2, "Для перераздачи нужны хотя бы две подходящие открытые карты.");
                var cards = new ArrayList<>(targets.stream().map(t -> t.sheet.get(chosenCategory)).toList());
                Collections.shuffle(cards, random);
                for (var t : targets) t.sheet.put(chosenCategory, cards.removeLast());
            }
            case "steal" -> {
                var t = targets.getFirst();
                RulePack.require(!o.compensation() || !abilityReserve.isEmpty(), "В колоде нет особых условий для компенсации.");
                p.sheet.put(chosenCategory, t.sheet.remove(chosenCategory));
                if (t.revealed.contains(chosenCategory)) p.revealed.add(chosenCategory);
                if (o.compensation()) t.extraAbilities.add(abilityReserve.removeFirst());
            }
            case "extra_ballot" -> {
                RulePack.require(phase != Phase.VOTING && phase != Phase.TIE_DEFENSE && phase != Phase.VOTE_RESULT,
                        "Дополнительный бюллетень нужно получить до голосования.");
                targets.forEach(t -> modifiers.add(new VoteModifier("extra_ballot", p.id, t.id, o.duration(), round, voteSerial, o.value())));
            }
            case "vote_weight", "cancel_vote", "double_against", "forbid_vote" -> {
                if (effect.equals("forbid_vote")) {
                    var t = targets.getFirst();
                    RulePack.require(!ballots.getOrDefault(t.id, List.of()).contains(p.id), "Игрок уже подал голос против вас; примените карту в следующем голосовании.");
                    modifiers.add(new VoteModifier(effect, t.id, p.id, o.duration(), round, voteSerial, o.value()));
                } else {
                    RulePack.require(phase != Phase.VOTE_RESULT, "Результат уже объявлен; для нового выбора используйте переголосование.");
                    targets.forEach(t -> modifiers.add(new VoteModifier(effect, p.id, t.id, o.duration(), round, voteSerial, o.value())));
                    if (effect.equals("double_against")) {
                        modifiers.add(new VoteModifier("no_vote", p.id, p.id, o.duration(), round, voteSerial, 1));
                        eligible.remove(p.id);
                        if (phase == Phase.VOTING && eligible.stream().allMatch(ballots::containsKey)) finishBallot();
                    }
                }
            }
            case "revote" -> restartBallot();
            case "force_reveal" -> {
                RulePack.require(chosenCategory != null && pack.categories().stream().anyMatch(c -> c.id().equals(chosenCategory)), "Выберите характеристику.");
                RulePack.require(phase == Phase.EXPLORATION || phase == Phase.REVEAL, "В этом раунде открытие характеристик уже завершено.");
                forcedCategory = chosenCategory;
                forcedCategoryRound = round;
            }
            case "silence" -> silenceRound = round;
            case "bunker_redraw", "bunker_discard", "bunker_steal" -> {
                RulePack.require(index != null && index >= 0 && index < bunker.size() && openBunker.contains(index) && !removedBunker.containsKey(index), "Выберите доступную открытую карту бункера.");
                if (effect.equals("bunker_redraw")) {
                    RulePack.require(!bunkerReserve.isEmpty(), "В колоде бункера нет запасных карт.");
                    bunker.set(index, bunkerReserve.removeFirst());
                } else removedBunker.put(index, effect.equals("bunker_steal") ? "exiled" : "discarded");
            }
            case "add_threat" -> activeThreats.add(new RulePack.Card(a.id(), a.title(), a.text()));
            case "manual" -> { /* Public acknowledgement; narrative effects are resolved by the group. */ }
            case "protect_vote" -> throw new GameException(400, "Это условие срабатывает автоматически после изгнания указанного игрока.");
            default -> throw new GameException(400, "Эффект не поддерживается.");
        }
        incrementUses(p, a);
        var event = new LinkedHashMap<String, Object>();
        event.put("round", round);
        event.put("phase", phase);
        event.put("playerId", p.id);
        event.put("ability", a);
        event.put("targets", targets.stream().map(t -> t.id).toList());
        if (chosenCategory != null) event.put("category", chosenCategory);
        if (index != null) event.put("index", index);
        abilityHistory.add(event);
    }

    List<RulePack.Ability> ownedAbilities(Player p) {
        var list = new ArrayList<RulePack.Ability>();
        if (p.ability != null) list.add(p.ability);
        list.addAll(p.extraAbilities);
        return list;
    }

    int uses(Player p, RulePack.Ability a) { return a == p.ability ? p.abilityUses : p.extraUses.getOrDefault(a.id(), 0); }
    private void incrementUses(Player p, RulePack.Ability a) {
        if (a == p.ability) p.abilityUses++;
        else p.extraUses.merge(a.id(), 1, Integer::sum);
    }

    String abilityUnavailable(Player p, RulePack.Ability a) {
        if (!pack.abilities().enabled() || phase == Phase.LOBBY || phase == Phase.FINISHED) return "Возможности доступны во время игры.";
        if (paused) return "Игра приостановлена.";
        if (uses(p, a) >= 1) return "Возможность уже использована.";
        if (a.effect().equals("protect_vote")) return "Срабатывает автоматически после изгнания указанного игрока; карта остаётся тайной до вашего изгнания.";
        if (!stateMatches(p, a.options().actor())) return a.options().actor().equals("exiled") ? "Можно применить только после своего изгнания." : "Изгнанный игрок не может применить эту карту.";
        boolean timing = switch (a.options().timing()) {
            case "before_vote" -> Set.of(Phase.EXPLORATION, Phase.REVEAL, Phase.DISCUSSION).contains(phase);
            case "during_vote" -> Set.of(Phase.DISCUSSION, Phase.VOTING, Phase.TIE_DEFENSE).contains(phase);
            case "after_vote" -> phase == Phase.VOTE_RESULT;
            default -> true;
        };
        if (!timing) return "Сейчас не подходящий момент по условиям карты.";
        if (a.effect().equals("force_reveal") && phase != Phase.EXPLORATION && phase != Phase.REVEAL) return "Открытие характеристик в этом раунде завершено.";
        if (a.effect().equals("silence") && !Set.of(Phase.EXPLORATION, Phase.REVEAL, Phase.DISCUSSION).contains(phase)) return "Молчание объявляется до голосования.";
        if (a.effect().equals("revote") && !Set.of(Phase.VOTING, Phase.TIE_DEFENSE, Phase.VOTE_RESULT).contains(phase)) return "Сначала начните голосование.";
        if (Set.of("vote_weight", "cancel_vote", "double_against", "extra_ballot").contains(a.effect()) && phase == Phase.VOTE_RESULT) return "Голосование уже завершено.";
        if (a.effect().equals("extra_ballot") && (phase == Phase.VOTING || phase == Phase.TIE_DEFENSE)) return "Дополнительный бюллетень нужно получить до голосования.";
        return null;
    }

    static boolean stateMatches(Player p, String state) { return state.equals("any") || p.exiled == state.equals("exiled"); }

    List<Player> abilityTargets(Player owner, RulePack.Ability a, String targetId, String category) {
        var o = a.options();
        if (o.target().equals("none")) return List.of();
        if (o.target().equals("all")) return players.values().stream().filter(t -> stateMatches(t, o.targetState()))
                .filter(t -> category == null || category.isEmpty() || t.sheet.containsKey(category) && visibilityMatches(t, category, o.visibility())).toList();
        if (!o.target().equals("self"))
            RulePack.require(targetId != null && !targetId.isBlank() && players.containsKey(targetId), "Выберите конкретного игрока из этой комнаты.");
        var target = o.target().equals("self") ? owner : player(targetId);
        RulePack.require(!o.target().equals("self") || targetId == null || targetId.isEmpty() || targetId.equals(owner.id), "Эта карта применяется к себе.");
        RulePack.require(stateMatches(target, o.targetState()), "Состояние выбранного игрока не подходит.");
        RulePack.require(!o.target().equals("other") || target != owner, "Выберите другого игрока.");
        RulePack.require(!o.target().equals("neighbor") || neighbor(owner.id, -1).equals(target.id) || neighbor(owner.id, 1).equals(target.id), "Выберите игрока слева или справа.");
        return List.of(target);
    }

    private boolean visibilityMatches(Player p, String category, String visibility) {
        return visibility.equals("any") || p.revealed.contains(category) == visibility.equals("open");
    }
    private void checkTrait(Player p, String category, String visibility) {
        RulePack.require(p.sheet.containsKey(category), "У игрока нет этой характеристики.");
        RulePack.require(visibilityMatches(p, category, visibility), "Карта требует другого состояния характеристики: " + visibility + ".");
    }

    private String neighbor(String id, int direction) {
        var ids = new ArrayList<>(players.keySet());
        return ids.get(Math.floorMod(ids.indexOf(id) + direction, ids.size()));
    }

    boolean canVoteFor(String voter, String target) {
        if (!candidates.contains(target)) return false;
        if (forcedVoters.contains(voter)) return voter.equals(target);
        if (!pack.rules().allowSelfVote() && voter.equals(target)) return false;
        if (revoteForbidden.getOrDefault(voter, List.of()).contains(target)) return false;
        return modifiers.stream().noneMatch(m -> m.kind.equals("forbid_vote") && m.owner.equals(voter) && m.target.equals(target) && m.applies(this));
    }

    private int modifierValue(String kind, String target) {
        return modifiers.stream().filter(m -> m.kind.equals(kind) && m.target.equals(target) && m.applies(this))
                .mapToInt(VoteModifier::value).max().orElse(0);
    }

    int ballotCount(Player p) {
        return pack.rules().votesPerVoter() + modifiers.stream().filter(m -> m.kind.equals("extra_ballot") && m.target.equals(p.id) && m.applies(this))
                .mapToInt(m -> m.value - 1).sum();
    }

    private void restartBallot() {
        RulePack.require(!ballots.isEmpty(), "Ещё нет бюллетеней для переголосования.");
        var previous = new LinkedHashMap<>(ballots);
        var restoredCandidates = new LinkedHashSet<String>();
        players.values().stream().filter(t -> !t.exiled || phase == Phase.VOTE_RESULT && t.id.equals(lastExiledId)).forEach(t -> restoredCandidates.add(t.id));
        for (var entry : previous.entrySet()) {
            if (forcedVoters.contains(entry.getKey())) continue;
            RulePack.require(restoredCandidates.stream().anyMatch(id -> !entry.getValue().contains(id)
                    && (pack.rules().allowSelfVote() || !id.equals(entry.getKey()))
                    && modifiers.stream().noneMatch(m -> m.kind.equals("forbid_vote") && m.owner.equals(entry.getKey()) && m.target.equals(id) && m.applies(this))),
                    "Для одного из игроков нет другого доступного кандидата.");
        }
        if (phase == Phase.VOTE_RESULT) {
            player(lastExiledId).exiled = false;
            lastExiledId = previousExiledId;
            pendingProtections.clear();
        }
        if (!history.isEmpty() && (phase == Phase.VOTE_RESULT || phase == Phase.TIE_DEFENSE)) history.getLast().put("superseded", true);
        revoteForbidden.clear();
        revoteForbidden.putAll(previous);
        candidates.clear(); candidates.addAll(restoredCandidates);
        ballots.clear();
        runoff = false;
        setPhase(Phase.VOTING);
    }

    private Set<String> protectionTargets(Player owner, RulePack.Ability a) {
        var o = a.options();
        return switch (o.condition()) {
            case "left" -> Set.of(neighbor(owner.id, -1));
            case "right" -> Set.of(neighbor(owner.id, 1));
            case "first_revealed" -> {
                var order = revealOrder.getOrDefault(o.category(), List.of());
                yield order.isEmpty() ? Set.of() : Set.of(order.getFirst());
            }
            case "youngest", "oldest" -> {
                var ages = new HashMap<String, Integer>();
                for (var t : players.values()) if (!t.exiled && t.revealed.contains(o.category()) && t.sheet.containsKey(o.category())) {
                    var age = t.sheet.get(o.category()).attributes().get("age");
                    if (age != null && age.matches("\\d{1,5}")) ages.put(t.id, Integer.parseInt(age));
                }
                int extreme = o.condition().equals("youngest") ? ages.values().stream().mapToInt(Integer::intValue).min().orElse(-1)
                        : ages.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
                yield ages.entrySet().stream().filter(e -> e.getValue() == extreme).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
            }
            default -> Set.of();
        };
    }

    private void commitProtections() {
        for (var protection : pendingProtections) {
            var owner = player(protection.owner);
            owner.forcedSelfVote = true;
            incrementUses(owner, protection.ability);
        }
        pendingProtections.clear();
        revoteForbidden.clear();
        voteSerial++;
    }

    record VoteModifier(String kind, String owner, String target, String duration, int round, int vote, int value) {
        boolean applies(GameRoom r) { return duration.equals("game") || duration.equals("round") && round == r.round || duration.equals("vote") && vote == r.voteSerial; }
    }
    record Protection(String owner, RulePack.Ability ability) {}

    private void revealCard(Player p, String category) {
        RulePack.require(p.sheet.containsKey(category) && !p.revealed.contains(category), "Эта характеристика уже открыта или отсутствует.");
        p.revealed.add(category);
        revealOrder.computeIfAbsent(category, key -> new ArrayList<>());
        if (!revealOrder.get(category).contains(p.id)) revealOrder.get(category).add(p.id);
    }

    private Player activePlayer(String id) {
        var p = player(id);
        RulePack.require(!p.exiled, "Лист изгнанного игрока заблокирован.");
        return p;
    }

    String nextActive(String after) {
        var ids = new ArrayList<>(players.keySet());
        int start = ids.indexOf(after);
        for (int i = 1; i <= ids.size(); i++) {
            var id = ids.get((start + i) % ids.size());
            if (!player(id).exiled) return id;
        }
        throw new GameException(409, "Не осталось активных игроков.");
    }

    void setPhase(Phase phase) {
        this.phase = phase;
        phaseStarted = Instant.now();
    }

    void requirePhase(Phase expected) {
        if (phase != expected) throw new GameException(409, "Действие недоступно на этапе " + phase + ".");
    }

    enum Phase {LOBBY, EXPLORATION, REVEAL, DISCUSSION, VOTING, TIE_DEFENSE, VOTE_RESULT, FINISHED}

    static final class Player {
        final String id = UUID.randomUUID().toString();
        final String name;
        final Map<String, RulePack.Card> sheet = new LinkedHashMap<>();
        final Set<String> revealed = new HashSet<>();
        boolean exiled;
        RulePack.Ability ability;
        int abilityUses;
        boolean forcedSelfVote;
        final List<RulePack.Ability> extraAbilities = new ArrayList<>();
        final Map<String, Integer> extraUses = new HashMap<>();

        Player(String name) {
            this.name = name;
        }
    }
}
