package ru.bunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ClassicPackTest {
    private static Stream<RulePack.Card> cards(RulePack pack) {
        return Stream.of(pack.categories().stream().flatMap(c -> c.cards().stream()),
                pack.catastrophes().stream(), pack.bunker().stream(), pack.threats().stream()).flatMap(s -> s);
    }

    @Test
    void allPdfDecksAndMixedPageCardsArePresentWithoutRepeatedProfessionPage() {
        var pack = RulePack.classic();
        var counts = Map.of("profession", 50, "biology", 30, "health", 30,
                "hobby", 30, "baggage", 30, "facts", 50);
        assertEquals(counts.keySet(), pack.categories().stream().map(RulePack.Category::id).collect(java.util.stream.Collectors.toSet()));
        for (var c : pack.categories()) assertEquals(counts.get(c.id()), c.cards().size(), c.name());
        assertEquals(20, pack.catastrophes().size());
        assertEquals(30, pack.bunker().size());
        assertEquals(30, pack.abilities().cards().size());
        assertEquals(11, pack.threats().size());
        var ids = Stream.concat(cards(pack).map(RulePack.Card::id), pack.abilities().cards().stream().map(RulePack.Ability::id)).toList();
        assertEquals(311, ids.size());
        assertEquals(311, new HashSet<>(ids).size());
        assertTrue(ids.stream().allMatch(id -> id.matches("classic-p\\d{2}-c0[1-9]")));
        assertTrue(ids.stream().noneMatch(id -> id.startsWith("classic-p22-") || id.startsWith("classic-p37-")));
        assertTrue(pack.categories().stream().filter(c -> c.id().equals("hobby")).findFirst().orElseThrow().cards().stream()
                .anyMatch(c -> c.id().equals("classic-p17-c01") && c.title().equals("Холодное оружие")));
        assertTrue(pack.catastrophes().stream().anyMatch(c -> c.id().equals("classic-p17-c03") && c.title().equals("Ядерная война")));
        assertTrue(pack.threats().stream().anyMatch(c -> c.id().equals("classic-p28-c05") && c.title().equals("Стресс-вирус")));
        assertEquals(5, pack.abilities().cards().stream().filter(c -> c.title().equals("Давайте начистоту")).count());
        assertEquals(5, pack.abilities().cards().stream().filter(c -> c.title().equals("Обмен карт")).count());
        assertTrue(pack.bunker().stream().filter(c -> c.title().equals("Вместе на 10 лет")).findFirst().orElseThrow().text().contains("дополнительную угрозу"));
    }

    @Test
    void originalDecksCanDealEverySupportedPlayerCountWithoutRepeatingCards() {
        var pack = RulePack.classic();
        for (int n = 4; n <= 16; n++) {
            var room = new GameRoom("PDF234", pack);
            for (int i = 0; i < n; i++) {
                var player = new GameRoom.Player("Игрок " + i);
                room.players.put(player.id, player);
                if (i == 0) room.hostId = player.id;
            }
            room.start();
            assertEquals(GameRoom.Phase.EXPLORATION, room.phase);
            assertTrue(pack.catastrophes().contains(room.catastrophe));
            assertEquals(5, room.bunker.size());
            assertEquals(5, new HashSet<>(room.bunker).size());
            assertTrue(pack.bunker().containsAll(room.bunker));
            for (var c : pack.categories()) {
                var dealt = room.players.values().stream().map(p -> p.sheet.get(c.id())).toList();
                assertEquals(n, new HashSet<>(dealt).size());
                assertTrue(c.cards().containsAll(dealt));
            }
            assertEquals(n, room.players.values().stream().map(p -> p.ability.id()).distinct().count());
            assertTrue(room.players.values().stream().allMatch(p -> p.sheet.size() == 6 && !p.ability.effect().equals("manual") && p.ability.uses() == 1));
        }
    }

    @Test
    void portablePackRoundTripsBuiltInCardsAndOlderPacksStillImport() throws Exception {
        var mapper = new ObjectMapper();
        var classic = RulePack.classic();
        var template = mapper.valueToTree(classic);
        ((com.fasterxml.jackson.databind.node.ObjectNode) template).put("id", "classic-template");
        var exported = mapper.readValue(mapper.writeValueAsBytes(template), RulePack.class);
        exported.validate(true, 16);
        assertEquals("classic-template", exported.id());
        assertEquals(classic.rules(), exported.rules());
        assertEquals(classic.categories(), exported.categories());
        assertEquals(classic.catastrophes(), exported.catastrophes());
        assertEquals(classic.bunker(), exported.bunker());
        assertEquals(classic.threats(), exported.threats());
        assertEquals(classic.abilities(), exported.abilities());
        var legacy = mapper.valueToTree(exported);
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("threats");
        var imported = mapper.treeToValue(legacy, RulePack.class);
        assertEquals(List.of(), imported.threats());
        assertDoesNotThrow(() -> imported.validate(true, 16));
        var invalid = new RulePack(exported.format(), exported.schemaVersion(), exported.id(), exported.name(), exported.description(),
                exported.rules(), exported.categories(), exported.catastrophes(), exported.bunker(), exported.abilities(),
                List.of(exported.bunker().getFirst()));
        assertThrows(GameException.class, () -> invalid.validate(false, 0), "Угрозы участвуют в проверке глобально уникальных ID.");
    }
}
