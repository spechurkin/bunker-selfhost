package ru.bunker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomService {
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;

    public RoomService(@Value("${bunker.room-ttl-hours:12}") long hours) {
        ttl = Duration.ofHours(hours);
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String validName(String name) {
        var cleaned = name == null ? "" : name.trim();
        RulePack.require(!cleaned.isEmpty() && cleaned.length() <= 32 && cleaned.chars().noneMatch(Character::isISOControl), "Введите имя длиной от 1 до 32 символов.");
        return cleaned;
    }

    public synchronized Ticket create(String name, String rulesetId, RulePack customPack) {
        RulePack.require(rooms.size() < 500, "Сервер заполнен. Попробуйте позже.");
        var pack = "classic".equals(rulesetId) ? RulePack.classic() : customPack;
        RulePack.require(pack != null, "Выберите набор правил.");
        if (!"classic".equals(rulesetId))
            RulePack.require(!"classic".equals(pack.id()), "ID classic зарезервирован сервером.");
        pack.validate(false, 0);
        String code;
        do {
            code = randomCode();
        } while (rooms.containsKey(code));
        var room = new GameRoom(code, pack);
        var p = new GameRoom.Player(validName(name));
        room.players.put(p.id, p);
        room.hostId = p.id;
        rooms.put(code, room);
        return ticket(room, p);
    }

    public Ticket join(String code, String name) {
        var room = room(code);
        synchronized (room) {
            room.requirePhase(GameRoom.Phase.LOBBY);
            RulePack.require(room.players.size() < room.pack.rules().maxPlayers(), "Комната заполнена.");
            var p = new GameRoom.Player(validName(name));
            RulePack.require(room.players.values().stream().noneMatch(other -> other.name.equalsIgnoreCase(p.name)), "Это имя уже занято в комнате.");
            room.players.put(p.id, p);
            changed(room);
            return ticket(room, p);
        }
    }

    public Map<String, Object> view(String code, String token) {
        var room = room(code);
        var session = session(code, token);
        synchronized (room) {
            room.player(session.playerId);
            return projection(room, session.playerId);
        }
    }

    public Map<String, Object> act(String code, String token, Action action) {
        var room = room(code);
        var session = session(code, token);
        synchronized (room) {
            var p = room.player(session.playerId);
            RulePack.require(action != null && action.type != null, "Не задано действие.");
            boolean admin = room.hostId.equals(p.id);
            if (Set.of("start", "next", "pause", "resume", "finish", "kick", "transfer").contains(action.type))
                if (!admin) throw new GameException(403, "Только администратор может управлять полем.");
            if (room.paused && !Set.of("resume", "finish", "transfer").contains(action.type))
                throw new GameException(409, "Игра приостановлена.");
            switch (action.type) {
                case "start" -> room.start();
                case "next" -> room.next();
                case "pause" -> {
                    RulePack.require(room.phase != GameRoom.Phase.FINISHED, "Игра завершена.");
                    room.paused = true;
                }
                case "resume" -> room.paused = false;
                case "finish" -> {
                    room.setPhase(GameRoom.Phase.FINISHED);
                    room.paused = false;
                }
                case "reveal-bunker" -> {
                    RulePack.require(admin || p.id.equals(room.currentPlayerId), "Карту бункера открывает активный игрок или администратор.");
                    room.revealBunker(action.index == null ? -1 : action.index);
                }
                case "reveal" -> room.reveal(p.id, action.category);
                case "vote" -> room.vote(p.id, action.targets);
                case "ability" -> room.useAbility(p.id, action.category, action.targetId, action.index, action.abilityId);
                case "kick" -> {
                    room.requirePhase(GameRoom.Phase.LOBBY);
                    RulePack.require(!p.id.equals(action.targetId), "Передайте управление, чтобы выйти из комнаты.");
                    room.player(action.targetId);
                    room.players.remove(action.targetId);
                    sessions.entrySet().removeIf(e -> e.getValue().code.equals(room.code) && e.getValue().playerId.equals(action.targetId));
                }
                case "transfer" -> {
                    room.player(action.targetId);
                    room.hostId = action.targetId;
                }
                default -> throw new GameException(400, "Неизвестное действие.");
            }
            changed(room);
            return projection(room, p.id);
        }
    }

    private Map<String, Object> projection(GameRoom r, String viewer) {
        var result = new LinkedHashMap<String, Object>();
        result.put("code", r.code);
        result.put("revision", r.revision);
        result.put("phase", r.phase);
        result.put("round", r.round);
        result.put("paused", r.paused);
        result.put("hostId", r.hostId);
        result.put("currentPlayerId", r.currentPlayerId);
        result.put("viewerId", viewer);
        result.put("pack", Map.of("id", r.pack.id(), "name", r.pack.name(), "rules", r.pack.rules(), "abilitiesEnabled", r.pack.abilities().enabled()));
        result.put("phaseStarted", r.phaseStarted.toString());
        result.put("catastrophe", r.catastrophe);
        result.put("bunker", java.util.stream.IntStream.range(0, r.bunker.size()).mapToObj(i -> {
            var b = new LinkedHashMap<String, Object>();
            b.put("index", i);
            b.put("revealed", r.openBunker.contains(i));
            b.put("card", r.openBunker.contains(i) ? r.bunker.get(i) : null);
            b.put("removed", r.removedBunker.get(i));
            return b;
        }).toList());
        result.put("players", r.players.values().stream().map(p -> {
            var v = new LinkedHashMap<String, Object>();
            v.put("id", p.id);
            v.put("name", p.name);
            v.put("exiled", p.exiled);
            v.put("sheet", r.pack.categories().stream().map(c -> {
                var t = new LinkedHashMap<String, Object>();
                t.put("category", c.id());
                t.put("name", c.name());
                t.put("revealed", p.revealed.contains(c.id()));
                t.put("available", p.sheet.containsKey(c.id()));
                boolean visible = viewer.equals(p.id) || p.revealed.contains(c.id()) || r.phase == GameRoom.Phase.FINISHED;
                t.put("card", visible ? p.sheet.get(c.id()) : null);
                return t;
            }).toList());
            if (viewer.equals(p.id)) {
                if (r.pack.abilities().enabled()) {
                    v.put("ability", p.ability);
                    v.put("abilityUses", p.abilityUses);
                    v.put("abilities", r.ownedAbilities(p).stream().map(a -> {
                        var ability = new LinkedHashMap<String, Object>();
                        ability.put("card", a);
                        ability.put("uses", r.uses(p, a));
                        ability.put("unavailable", r.abilityUnavailable(p, a));
                        return ability;
                    }).toList());
                }
                v.put("ballotCount", r.ballotCount(p));
                v.put("turnRevealed", r.turnsDone.contains(p.id));
                v.put("forcedSelfVote", r.forcedVoters.contains(p.id));
            }
            return v;
        }).toList());
        result.put("vote", Map.of("number", r.voteNumber, "runoff", r.runoff, "eligible", List.copyOf(r.eligible),
                "candidates", List.copyOf(r.candidates), "received", (int) r.eligible.stream().filter(r.ballots::containsKey).count(), "required", r.eligible.size(),
                "submitted", r.ballots.containsKey(viewer), "allowedTargets", r.candidates.stream().filter(id -> r.canVoteFor(viewer, id)).toList()));
        result.put("history", List.copyOf(r.history));
        result.put("abilityHistory", List.copyOf(r.abilityHistory));
        result.put("revealedProtections", r.players.values().stream().filter(p -> p.exiled).flatMap(p -> r.ownedAbilities(p).stream()
                .filter(a -> a.effect().equals("protect_vote")).map(a -> Map.of("playerId", p.id, "ability", a))).toList());
        result.put("activeThreats", List.copyOf(r.activeThreats));
        result.put("forcedCategory", r.forcedCategoryRound == r.round ? Objects.toString(r.forcedCategory, "") : "");
        result.put("silence", r.silenceRound == r.round && Set.of(GameRoom.Phase.EXPLORATION, GameRoom.Phase.REVEAL, GameRoom.Phase.DISCUSSION).contains(r.phase));
        return result;
    }

    private Ticket ticket(GameRoom r, GameRoom.Player p) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(r.code, p.id));
        return new Ticket(r.code, p.id, token);
    }

    private Session session(String code, String token) {
        var s = token == null ? null : sessions.get(token);
        if (s == null || !s.code.equals(normalize(code)))
            throw new GameException(401, "Сессия отсутствует или истекла. Войдите в комнату заново.");
        return s;
    }

    private GameRoom room(String code) {
        var room = rooms.get(normalize(code));
        if (room == null || room.touched.plus(ttl).isBefore(Instant.now()))
            throw new GameException(404, "Комната не найдена или срок её жизни истёк.");
        return room;
    }

    private String randomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(chars.charAt(random.nextInt(chars.length())));
        return code.toString();
    }

    private void changed(GameRoom r) {
        r.revision++;
        r.touched = Instant.now();
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void expire() {
        var cutoff = Instant.now().minus(ttl);
        rooms.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().touched.isBefore(cutoff);
            }
        });
        sessions.entrySet().removeIf(e -> !rooms.containsKey(e.getValue().code));
    }

    record Session(String code, String playerId) {
    }

    public record Ticket(String code, String playerId, String token) {
    }

    public record Action(String type, String category, Integer index, List<String> targets, String targetId, String abilityId) {
        public Action(String type, String category, Integer index, List<String> targets, String targetId) {
            this(type, category, index, targets, targetId, null);
        }
    }
}
