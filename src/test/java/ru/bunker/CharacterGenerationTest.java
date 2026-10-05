package ru.bunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CharacterGenerationTest {
    static RulePack pack(RulePack.CharacterGeneration g, boolean abilities) {
        var base = GameRulesTest.pack(abilities, 1, Map.of("4", List.of(1)));
        var categories = base.categories().stream().map(c -> c.id().equals("trait1")
                ? new RulePack.Category(c.id(), "Биология", List.of()) : c).toList();
        return new RulePack(base.format(), base.schemaVersion(), base.id(), base.name(), base.description(), base.rules(),
                categories, base.catastrophes(), base.bunker(), base.abilities(), base.threats(), g);
    }

    static GameRoom room(RulePack pack) {
        var r = new GameRoom("GEN234", pack);
        for (int i = 0; i < 4; i++) {
            var p = new GameRoom.Player("Игрок " + i);
            r.players.put(p.id, p);
            if (i == 0) r.hostId = p.id;
        }
        r.start();
        return r;
    }

    static RulePack.CharacterGeneration config(double female, double lgbt, int min, int max) {
        return new RulePack.CharacterGeneration("trait1", List.of("Эльф", "Орк", "Гном"), female, lgbt, min, max);
    }

    @Test
    void percentagesAtZeroAndHundredAndFixedAgeAreExact() {
        for (var female : List.of(0.0, 100.0)) for (var lgbt : List.of(0.0, 100.0)) {
            var g = new RulePack.CharacterGeneration("trait1", List.of("Эльф"), female, lgbt, 4000, 4000);
            var r = room(pack(g, false));
            var ids = new java.util.HashSet<String>();
            for (var p : r.players.values()) {
                var card = p.sheet.get("trait1");
                assertTrue(ids.add(card.id()));
                assertEquals("Эльф", card.attributes().get("species"));
                assertEquals(female == 100 ? "female" : "male", card.attributes().get("sex"));
                assertEquals(lgbt == 100 ? "lgbt" : "straight", card.attributes().get("orientation"));
                assertEquals("4000", card.attributes().get("age"));
                assertTrue(card.text().contains("4000")); assertFalse(p.revealed.contains("trait1"));
            }
        }
    }

    @Test
    void childfreeEndpointsAreIndependentOfSexAndOrientation() {
        for (var childfree : List.of(0.0, 100.0))
            for (var female : List.of(0.0, 100.0)) for (var lgbt : List.of(0.0, 100.0)) {
                var g = new RulePack.CharacterGeneration("trait1", List.of("Эльф"), female, lgbt, 18, 18, childfree);
                for (var p : room(pack(g, false)).players.values()) {
                    var card = p.sheet.get("trait1");
                    assertEquals(String.valueOf(childfree == 100), card.attributes().get("childfree"));
                    assertTrue(card.text().contains("Чайлдфри: " + (childfree == 100 ? "да" : "нет")));
                    assertFalse(card.attributes().containsKey("fertility"));
                    assertFalse(card.text().contains("Бесплодие"));
                }
            }
    }

    @Test
    void generatedTraitsUseConfiguredSpeciesAgeRangeAndApproximateProbability() {
        int total = 2400, female = 0, lgbt = 0, childfree = 0;
        var species = new java.util.HashSet<String>();
        var ages = new java.util.HashSet<Integer>();
        var pack = pack(new RulePack.CharacterGeneration("trait1", List.of("Эльф", "Орк", "Гном"),
                50.0, 3.8, 18, 20, 25.0), false);
        for (int batch = 0; batch < total / 4; batch++) for (var p : room(pack).players.values()) {
            var a = p.sheet.get("trait1").attributes();
            if (a.get("sex").equals("female")) female++;
            if (a.get("orientation").equals("lgbt")) lgbt++;
            else assertEquals("straight", a.get("orientation"));
            if (a.get("childfree").equals("true")) childfree++;
            else assertEquals("false", a.get("childfree"));
            species.add(a.get("species"));
            int age = Integer.parseInt(a.get("age")); assertTrue(age >= 18 && age <= 20); ages.add(age);
        }
        // Broad bounds detect percent/fraction mistakes without requiring an exact party quota.
        assertTrue(female > total * .4 && female < total * .6);
        assertTrue(lgbt > total * .015 && lgbt < total * .07);
        assertTrue(childfree > total * .18 && childfree < total * .32);
        assertEquals(java.util.Set.of("Эльф", "Орк", "Гном"), species);
        assertEquals(java.util.Set.of(18, 19, 20), ages);
    }

    @Test
    void validatesRangesRequiredFieldsAndOnlyAllowsCustomPacks() {
        for (var g : List.of(config(-1, 3.8, 18, 80), config(101, 3.8, 18, 80), config(50, -1, 18, 80),
                config(50, 101, 18, 80), config(Double.NaN, 3.8, 18, 80), config(50, Double.POSITIVE_INFINITY, 18, 80),
                config(50, 3.8, 81, 80), config(50, 3.8, -1, 80), config(50, 3.8, 18, 100000),
                new RulePack.CharacterGeneration("trait1", List.of(), 50.0, 3.8, 18, 80),
                new RulePack.CharacterGeneration("trait1", List.of("Эльф", "эльф"), 50.0, 3.8, 18, 80),
                new RulePack.CharacterGeneration("missing", List.of("Эльф"), 50.0, 3.8, 18, 80),
                new RulePack.CharacterGeneration("trait1", List.of("Эльф"), null, 3.8, 18, 80),
                new RulePack.CharacterGeneration("trait1", List.of("Эльф"), 50.0, 3.8, null, 80),
                new RulePack.CharacterGeneration("trait1", List.of("Эльф"), 50.0, 3.8, 18, 80, -1.0),
                new RulePack.CharacterGeneration("trait1", List.of("Эльф"), 50.0, 3.8, 18, 80, 101.0),
                new RulePack.CharacterGeneration("trait1", List.of("Эльф"), 50.0, 3.8, 18, 80, Double.NaN),
                new RulePack.CharacterGeneration("trait1", List.of("Эльф"), 50.0, 3.8, 18, 80, Double.POSITIVE_INFINITY)))
            assertThrows(GameException.class, () -> pack(g, false).validate(false, 0));
        var classic = RulePack.classic();
        assertNull(classic.characterGeneration());
        var altered = new RulePack(classic.format(), classic.schemaVersion(), classic.id(), classic.name(), classic.description(),
                classic.rules(), classic.categories(), classic.catastrophes(), classic.bunker(), classic.abilities(), classic.threats(),
                new RulePack.CharacterGeneration("biology", List.of("Эльф"), 50.0, 3.8, 18, 80));
        assertThrows(GameException.class, () -> altered.validate(false, 0));
    }

    @Test
    void emptyGeneratedDeckWorksButOrdinaryEmptyDeckStillCannotStartAndRedrawRegenerates() {
        var custom = pack(new RulePack.CharacterGeneration("trait1", List.of("Эльф"), 100.0, 0.0, 21, 21), true);
        assertDoesNotThrow(() -> custom.validate(true, 4));
        var ordinary = new RulePack(custom.format(), custom.schemaVersion(), custom.id(), custom.name(), custom.description(),
                custom.rules(), custom.categories(), custom.catastrophes(), custom.bunker(), custom.abilities(), custom.threats());
        assertThrows(GameException.class, () -> ordinary.validate(true, 4));
        var r = room(custom); var p = r.player(r.hostId);
        var target = new ArrayList<>(r.players.values()).get(1);
        target.revealed.add("trait1");
        var old = target.sheet.get("trait1");
        p.ability = new RulePack.Ability("redraw-biology", "Перерождение", "", "redraw", 1,
                new RulePack.AbilityOptions("any", "other", "active", "active", "trait1", "open", null, null, null, null, false));
        r.useAbility(p.id, "trait1", target.id);
        assertNotEquals(old.id(), target.sheet.get("trait1").id());
        assertEquals(old.attributes(), target.sheet.get("trait1").attributes());
        assertTrue(target.revealed.contains("trait1")); assertEquals(1, p.abilityUses);
    }

    @SuppressWarnings("unchecked")
    @Test
    void generatedBiologyStaysPrivateAndConfigurationRoundTripsWithoutChangingClassic() throws Exception {
        var mapper = new ObjectMapper(); var custom = pack(new RulePack.CharacterGeneration("trait1", List.of("Эльф"),
                100.0, 100.0, 22, 22, 100.0), true);
        assertEquals(custom, mapper.readValue(mapper.writeValueAsString(custom), RulePack.class));
        assertFalse(mapper.valueToTree(RulePack.classic()).has("characterGeneration"));
        var service = new RoomService(12);
        var host = service.create("Хост", "custom", custom);
        var ts = new ArrayList<RoomService.Ticket>(); ts.add(host);
        for (int i = 1; i < 4; i++) ts.add(service.join(host.code(), "Игрок " + i));
        service.act(host.code(), host.token(), new RoomService.Action("start", null, null, null, null));
        var view = service.view(host.code(), host.token());
        var players = (List<Map<String, Object>>) view.get("players");
        for (var p : players) {
            var biology = ((List<Map<String, Object>>)p.get("sheet")).stream().filter(t -> t.get("category").equals("trait1")).findFirst().orElseThrow();
            assertEquals(p.get("id").equals(host.playerId()), biology.get("card") != null);
        }
        service.act(host.code(), ts.get(1).token(), new RoomService.Action("ability", "trait1", null, null, null));
        var revealed = (List<Map<String, Object>>) service.view(host.code(), host.token()).get("players");
        var other = revealed.stream().filter(p -> p.get("id").equals(ts.get(1).playerId())).findFirst().orElseThrow();
        assertNotNull(((List<Map<String, Object>>) other.get("sheet")).get(1).get("card"));
        var legacy = mapper.valueToTree(GameRulesTest.pack(false, 1, Map.of("4", List.of(1))));
        assertNull(mapper.treeToValue(legacy, RulePack.class).characterGeneration());
    }

    @Test
    void olderGeneratorWithoutChildfreeDefaultsToZero() throws Exception {
        var mapper = new ObjectMapper();
        var json = mapper.valueToTree(pack(config(50, 3.8, 18, 80), false));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("characterGeneration")).remove("childfreePercent");
        var restored = mapper.treeToValue(json, RulePack.class);
        assertEquals(0.0, restored.characterGeneration().childfreePercent());
        assertDoesNotThrow(() -> restored.validate(true, 4));
        for (var p : room(restored).players.values())
            assertEquals("false", p.sheet.get("trait1").attributes().get("childfree"));
    }

    @Test
    void eachSpeciesUsesItsOwnAgeSexOrientationAndChildfreeParameters() {
        var elf = new RulePack.SpeciesGeneration("Эльф", 100.0, 100.0, 2500, 2500, 100.0);
        var orc = new RulePack.SpeciesGeneration("Орк", 0.0, 0.0, 18, 21, 0.0);
        var custom = pack(new RulePack.CharacterGeneration("trait1", List.of(elf, orc)), false);
        var seen = new java.util.HashSet<String>();
        for (int batch = 0; batch < 60; batch++) for (var p : room(custom).players.values()) {
            var a = p.sheet.get("trait1").attributes();
            seen.add(a.get("species"));
            if (a.get("species").equals("Эльф")) {
                assertEquals("2500", a.get("age")); assertEquals("female", a.get("sex"));
                assertEquals("lgbt", a.get("orientation")); assertEquals("true", a.get("childfree"));
            } else {
                assertEquals("Орк", a.get("species")); int age = Integer.parseInt(a.get("age"));
                assertTrue(age >= 18 && age <= 21); assertEquals("male", a.get("sex"));
                assertEquals("straight", a.get("orientation")); assertEquals("false", a.get("childfree"));
            }
        }
        assertEquals(java.util.Set.of("Эльф", "Орк"), seen);
    }

    @Test
    void profilesRoundTripAndLegacyParametersExpandWithoutChangingTheirMeaning() throws Exception {
        var mapper = new ObjectMapper();
        var g = new RulePack.CharacterGeneration("trait1", List.of(
                new RulePack.SpeciesGeneration("Гном", 30.0, 1.0, 40, 450, 5.0)));
        var custom = pack(g, false);
        assertEquals(custom, mapper.readValue(mapper.writeValueAsString(custom), RulePack.class));
        var json = mapper.valueToTree(g);
        assertTrue(json.has("speciesRules")); assertFalse(json.has("minAge")); assertFalse(json.has("femalePercent"));
        for (var profile : config(25, 9, 100, 500).profiles()) {
            assertEquals(100, profile.minAge()); assertEquals(500, profile.maxAge());
            assertEquals(25.0, profile.femalePercent()); assertEquals(9.0, profile.lgbtPercent());
            assertEquals(0.0, profile.childfreePercent());
        }
        var old = mapper.readTree(mapper.writeValueAsString(g));
        ((com.fasterxml.jackson.databind.node.ObjectNode)old.get("speciesRules").get(0)).remove("childfreePercent");
        assertEquals(0.0, mapper.treeToValue(old, RulePack.CharacterGeneration.class).profiles().getFirst().childfreePercent());
    }

    @Test
    void invalidOrAmbiguousSpeciesProfilesCannotStart() {
        for (var profile : List.of(
                new RulePack.SpeciesGeneration("", 50.0, 3.8, 18, 80, 0.0),
                new RulePack.SpeciesGeneration("Эльф\nОрк", 50.0, 3.8, 18, 80, 0.0),
                new RulePack.SpeciesGeneration(" Эльф", 50.0, 3.8, 18, 80, 0.0),
                new RulePack.SpeciesGeneration("Эльф", -1.0, 3.8, 18, 80, 0.0),
                new RulePack.SpeciesGeneration("Эльф", 50.0, 101.0, 18, 80, 0.0),
                new RulePack.SpeciesGeneration("Эльф", 50.0, 3.8, 81, 80, 0.0),
                new RulePack.SpeciesGeneration("Эльф", 50.0, 3.8, 18, 100000, 0.0),
                new RulePack.SpeciesGeneration("Эльф", 50.0, 3.8, null, 80, 0.0),
                new RulePack.SpeciesGeneration("Эльф", 50.0, null, 18, 80, 0.0),
                new RulePack.SpeciesGeneration("Эльф", 50.0, 3.8, 18, 80, Double.NaN),
                new RulePack.SpeciesGeneration("Эльф", Double.POSITIVE_INFINITY, 3.8, 18, 80, 0.0)))
            assertThrows(GameException.class, () -> pack(new RulePack.CharacterGeneration("trait1", List.of(profile)), false).validate(true, 4));
        var good = new RulePack.SpeciesGeneration("Эльф", 50.0, 3.8, 18, 80, 0.0);
        var duplicate = new RulePack.SpeciesGeneration("эльф", 50.0, 3.8, 18, 80, 0.0);
        assertThrows(GameException.class, () -> pack(new RulePack.CharacterGeneration("trait1", List.of(good, duplicate)), false).validate(true, 4));
        assertThrows(GameException.class, () -> pack(new RulePack.CharacterGeneration("trait1", List.of()), false).validate(true, 4));
        var mixed = new RulePack.CharacterGeneration("trait1", List.of("Эльф"), 50.0, 3.8, 18, 80, 0.0, List.of(good));
        assertThrows(GameException.class, () -> pack(mixed, false).validate(true, 4));
    }

    @Test
    void targetedRedrawUsesSpeciesProfileAndKeepsBiologyPrivate() {
        var elf = new RulePack.SpeciesGeneration("Эльф", 100.0, 0.0, 300, 300, 100.0);
        var r = room(pack(new RulePack.CharacterGeneration("trait1", List.of(elf)), true));
        var host = r.player(r.hostId); var target = new ArrayList<>(r.players.values()).get(1);
        var old = target.sheet.get("trait1");
        host.ability = new RulePack.Ability("redraw-species", "Перерождение", "", "redraw", 1,
                new RulePack.AbilityOptions("any", "other", "active", "active", "trait1", "closed", null, null, null, null, false));
        r.useAbility(host.id, "trait1", target.id);
        assertNotEquals(old.id(), target.sheet.get("trait1").id());
        assertEquals(old.attributes(), target.sheet.get("trait1").attributes());
        assertFalse(target.revealed.contains("trait1")); assertEquals(1, host.abilityUses);
    }
}
