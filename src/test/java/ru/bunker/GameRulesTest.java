package ru.bunker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class GameRulesTest {
    static RulePack pack(boolean abilities, int rounds, Map<String, List<Integer>> schedule) {
        var categories = IntStream.range(0, rounds + 1).mapToObj(i -> new RulePack.Category(i == 0 ? "profession" : "trait" + i, "Категория " + i,
                IntStream.range(0, 8).mapToObj(j -> new RulePack.Card("c" + i + "_" + j, "ЗАКРЫТАЯ_КАРТА_" + i + "_" + j, "Секрет " + j)).toList())).toList();
        return new RulePack("bunker", 1, "fixture", "Тестовый набор", "Синтетические данные только для тестов",
                new RulePack.Rules(rounds, 4, 4, "profession", 30, 60, 1, true, true, schedule), categories,
                List.of(new RulePack.Card("cat1", "Катастрофа", "Тест")),
                IntStream.range(0, rounds).mapToObj(i -> new RulePack.Card("b" + i, "Бункер " + i, "Тест")).toList(),
                new RulePack.Abilities(abilities, abilities ? IntStream.range(0, 4).mapToObj(i -> new RulePack.Ability("a" + i, "Условие", "Откройте карту", "reveal_self", 1)).toList() : List.of()));
    }

    static GameRoom room(int rounds, List<Integer> schedule) {
        var r = new GameRoom("ABC234", pack(false, rounds, Map.of("4", schedule)));
        for (int i = 0; i < 4; i++) {
            var p = new GameRoom.Player("Игрок " + i);
            r.players.put(p.id, p);
            if (i == 0) r.hostId = p.id;
        }
        r.start();
        return r;
    }

    static void revealCircle(GameRoom r, String category) {
        while (r.phase == GameRoom.Phase.REVEAL) {
            r.reveal(r.currentPlayerId, category);
            r.next();
        }
    }

    private static boolean attemptVote(RoomService s, RoomService.Ticket t, RoomService.Action a) {
        try {
            s.act(t.code(), t.token(), a);
            return true;
        } catch (GameException e) {
            assertEquals(409, e.status());
            return false;
        }
    }

    @Test
    void classicHasSpecialConditionsAndExactPrintedSchedule() {
        assertTrue(RulePack.classic().abilities().enabled());
        assertEquals(1, RulePack.classic().rules().votesPerVoter());
        assertEquals(List.of(0, 0, 0, 1, 1), RulePack.CLASSIC_SCHEDULE.get("4"));
        assertEquals(List.of(0, 1, 1, 1, 1), RulePack.CLASSIC_SCHEDULE.get("7"));
        assertEquals(List.of(0, 2, 2, 2, 2), RulePack.CLASSIC_SCHEDULE.get("16"));
        for (int n = 4; n <= 16; n++)
            assertEquals((n + 1) / 2, RulePack.CLASSIC_SCHEDULE.get("" + n).stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void scheduledVoteCannotBeSkippedAndExiledSheetIsLocked() {
        var r = room(2, List.of(1, 1));
        r.revealBunker(0);
        revealCircle(r, "profession");
        assertEquals(GameRoom.Phase.DISCUSSION, r.phase);
        r.next();
        assertThrows(GameException.class, r::next);
        var ids = new ArrayList<>(r.players.keySet());
        for (var id : ids) r.vote(id, List.of(ids.get(1)));
        assertTrue(r.player(ids.get(1)).exiled);
        r.next();
        assertEquals(GameRoom.Phase.EXPLORATION, r.phase);
        r.revealBunker(1);
        assertThrows(GameException.class, () -> r.reveal(ids.get(1), "trait1"));
        revealCircle(r, "trait1");
        r.next();
        assertTrue(r.eligible.contains(ids.get(1)), "Последний изгнанный сохраняет один голос согласно PDF.");
    }

    @Test
    void tiesGetOneNewBallotThenRandomExileAmongTiedOnly() {
        var r = room(1, List.of(1));
        r.revealBunker(0);
        revealCircle(r, "profession");
        r.next();
        var ids = new ArrayList<>(r.players.keySet());
        for (int i = 0; i < 4; i++) r.vote(ids.get(i), List.of(ids.get(i % 2)));
        assertEquals(GameRoom.Phase.TIE_DEFENSE, r.phase);
        assertEquals(Set.of(ids.get(0), ids.get(1)), r.candidates);
        r.next();
        for (int i = 0; i < 4; i++) r.vote(ids.get(i), List.of(ids.get(i % 2)));
        assertEquals(GameRoom.Phase.VOTE_RESULT, r.phase);
        assertTrue(Set.of(ids.get(0), ids.get(1)).contains(r.lastExiledId));
        assertEquals(1, r.players.values().stream().filter(p -> p.exiled).count());
        r.next();
        assertEquals(GameRoom.Phase.FINISHED, r.phase);
    }

    @Test
    void duplicateAndConcurrentVotesAreRejectedAtomically() throws Exception {
        var service = new RoomService(12);
        var host = service.create("Хост", "custom", pack(false, 1, Map.of("4", List.of(1))));
        var tickets = new ArrayList<RoomService.Ticket>();
        tickets.add(host);
        for (int i = 1; i < 4; i++) tickets.add(service.join(host.code(), "Игрок " + i));
        service.act(host.code(), host.token(), new RoomService.Action("start", null, null, null, null));
        service.act(host.code(), host.token(), new RoomService.Action("reveal-bunker", null, 0, null, null));
        for (var t : tickets) {
            service.act(host.code(), t.token(), new RoomService.Action("reveal", "profession", null, null, null));
            service.act(host.code(), host.token(), new RoomService.Action("next", null, null, null, null));
        }
        service.act(host.code(), host.token(), new RoomService.Action("next", null, null, null, null));
        var vote = new RoomService.Action("vote", null, null, List.of(tickets.get(1).playerId()), null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.<Callable<Boolean>>of(() -> attemptVote(service, host, vote), () -> attemptVote(service, host, vote));
            var results = executor.invokeAll(tasks);
            assertEquals(1, results.stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).count());
        }
        assertEquals(1, ((Map<?, ?>) service.view(host.code(), host.token()).get("vote")).get("received"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void hostAndPlayerReceiveNoOtherPrivateCardsOrOpenBallots() {
        var s = new RoomService(12);
        var host = s.create("Хост", "custom", pack(true, 1, Map.of("4", List.of(1))));
        var ts = new ArrayList<RoomService.Ticket>();
        ts.add(host);
        for (int i = 1; i < 4; i++) ts.add(s.join(host.code(), "Игрок " + i));
        s.act(host.code(), host.token(), new RoomService.Action("start", null, null, null, null));
        var v = s.view(host.code(), host.token());
        var ps = (List<Map<String, Object>>) v.get("players");
        for (var p : ps) {
            var sheet = (List<Map<String, Object>>) p.get("sheet");
            if (p.get("id").equals(host.playerId())) {
                assertNotNull(sheet.getFirst().get("card"));
                assertNotNull(p.get("ability"));
            } else {
                assertTrue(sheet.stream().allMatch(t -> t.get("card") == null));
                assertFalse(p.containsKey("ability"));
            }
        }
        assertThrows(GameException.class, () -> s.act(host.code(), ts.get(1).token(), new RoomService.Action("finish", null, null, null, null)));
        assertThrows(GameException.class, () -> s.view(host.code(), "forged"));
        s.act(host.code(), host.token(), new RoomService.Action("reveal-bunker", null, 0, null, null));
        for (var t : ts) {
            s.act(host.code(), t.token(), new RoomService.Action("reveal", "profession", null, null, null));
            s.act(host.code(), host.token(), new RoomService.Action("next", null, null, null, null));
        }
        s.act(host.code(), host.token(), new RoomService.Action("next", null, null, null, null));
        s.act(host.code(), host.token(), new RoomService.Action("vote", null, null, List.of(ts.get(1).playerId()), null));
        var otherView = s.view(host.code(), ts.get(2).token());
        assertFalse(((Map<?, ?>) otherView.get("vote")).containsKey("ballots"));
        assertTrue(((List<?>) otherView.get("history")).isEmpty());
    }

    @Test
    void specialConditionCanBeUsedOutOfTurnAndDisabledPackHasNone() {
        var s = new RoomService(12);
        var host = s.create("Хост", "custom", pack(true, 1, Map.of("4", List.of(1))));
        var other = s.join(host.code(), "Другой");
        s.join(host.code(), "Третий");
        s.join(host.code(), "Четвёртый");
        s.act(host.code(), host.token(), new RoomService.Action("start", null, null, null, null));
        s.act(host.code(), other.token(), new RoomService.Action("ability", "trait1", null, null, null));
        assertThrows(GameException.class, () -> s.act(host.code(), other.token(), new RoomService.Action("ability", "profession", null, null, null)));
        var r = room(1, List.of(1));
        assertThrows(GameException.class, () -> r.useAbility(r.hostId, "trait1", null));
    }

    @Test
    void classicDealsPdfCardsAndReservedIdCannotBeOverridden() {
        var s = new RoomService(12);
        var t = s.create("Хост", "classic", null);
        for (int i = 1; i < 4; i++) s.join(t.code(), "Игрок " + i);
        assertDoesNotThrow(() -> s.act(t.code(), t.token(), new RoomService.Action("start", null, null, null, null)));
        assertEquals(GameRoom.Phase.EXPLORATION, s.view(t.code(), t.token()).get("phase"));
        assertThrows(GameException.class, () -> s.create("Другой", "custom", RulePack.classic()));
    }
}
