package ru.bunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AbilityTest {
    static GameRoom classicRoom() {
        var r = new GameRoom("AB2345", RulePack.classic());
        for (int i = 0; i < 4; i++) {
            var p = new GameRoom.Player("Игрок " + i);
            r.players.put(p.id, p);
            if (i == 0) r.hostId = p.id;
        }
        r.start();
        int i = 0;
        for (var p : r.players.values()) p.ability = new RulePack.Ability("narrative" + i++, "Условие", "", "manual", 1);
        return r;
    }

    static RulePack.Ability ability(String suffix) {
        return RulePack.classic().abilities().cards().stream().filter(a -> a.id().equals("classic-" + suffix)).findFirst().orElseThrow();
    }

    static GameRoom.Player equip(GameRoom r, String suffix) {
        var p = r.player(r.hostId);
        p.ability = ability(suffix); p.abilityUses = 0;
        return p;
    }

    static List<GameRoom.Player> players(GameRoom r) { return new ArrayList<>(r.players.values()); }

    static void ballot(GameRoom r, String victim) {
        if (r.phase != GameRoom.Phase.VOTING) { r.setPhase(GameRoom.Phase.DISCUSSION); r.next(); }
        for (var id : List.copyOf(r.eligible)) r.vote(id, List.of(victim));
    }

    @Test
    void allThirtyPrintedCardsHaveWorkingEffectsAndOnlyOneUse() {
        for (var a : RulePack.classic().abilities().cards()) {
            if (a.effect().equals("protect_vote")) continue; // Passive cards are covered below.
            var r = classicRoom(); var ps = players(r); var owner = ps.getFirst();
            owner.ability = a; owner.abilityUses = 0;
            ps.forEach(p -> p.revealed.addAll(p.sheet.keySet()));
            r.openBunker.add(0);
            if (a.options().actor().equals("exiled")) owner.exiled = true;
            if (a.options().timing().equals("during_vote")) r.setPhase(GameRoom.Phase.DISCUSSION);
            if (a.effect().equals("revote")) ballot(r, ps.get(1).id);
            var category = a.options().category().isEmpty() ? "health" : a.options().category();
            var target = List.of("other", "any", "neighbor").contains(a.options().target()) ? ps.get(1).id : null;
            assertDoesNotThrow(() -> r.useAbility(owner.id, category, target, 0, a.id()), a.title());
            assertEquals(1, owner.abilityUses, a.id());
            assertEquals(1, r.abilityHistory.size(), a.id());
            assertThrows(GameException.class, () -> r.useAbility(owner.id, category, target, 0, a.id()), a.id());
        }
    }

    @Test
    void anyTimeDoesNotDependOnTurnAndExiledCardsRequireExile() {
        for (var phase : List.of(GameRoom.Phase.EXPLORATION, GameRoom.Phase.REVEAL, GameRoom.Phase.DISCUSSION,
                GameRoom.Phase.VOTING, GameRoom.Phase.TIE_DEFENSE, GameRoom.Phase.VOTE_RESULT)) {
            var r = classicRoom(); var p = equip(r, "p14-c06"); r.openBunker.add(0); r.setPhase(phase);
            r.currentPlayerId = players(r).get(1).id;
            var old = r.bunker.getFirst(); r.useAbility(p.id, null, null, 0, null);
            assertNotEquals(old, r.bunker.getFirst());
        }
        for (var id : List.of("p14-c04", "p18-c02", "p20-c01")) {
            var r = classicRoom(); var p = equip(r, id); r.openBunker.add(0);
            assertThrows(GameException.class, () -> r.useAbility(p.id, null, null, 0, null));
            assertEquals(0, p.abilityUses);
            p.exiled = true;
            r.useAbility(p.id, null, null, 0, null);
            if (id.equals("p20-c01")) assertEquals(p.ability.text(), r.activeThreats.getFirst().text());
            else assertEquals(id.equals("p14-c04") ? "exiled" : "discarded", r.removedBunker.get(0));
        }
    }

    @Test
    void swapChecksNeighborAndVisibilityWithoutConsumingFailedActions() {
        var r = classicRoom(); var p = equip(r, "p19-c01"); var ps = players(r);
        ps.forEach(t -> t.revealed.add("baggage"));
        var own = p.sheet.get("baggage"); var other = ps.get(1).sheet.get("baggage");
        assertThrows(GameException.class, () -> r.useAbility(p.id, "baggage", ps.get(2).id));
        assertThrows(GameException.class, () -> r.useAbility(p.id, "health", ps.get(1).id));
        ps.get(1).revealed.remove("baggage");
        assertThrows(GameException.class, () -> r.useAbility(p.id, "baggage", ps.get(1).id));
        assertEquals(0, p.abilityUses); assertEquals(own, p.sheet.get("baggage"));
        ps.get(1).revealed.add("baggage");
        r.useAbility(p.id, "baggage", ps.get(1).id);
        assertEquals(other, p.sheet.get("baggage")); assertEquals(own, ps.get(1).sheet.get("baggage"));
    }

    @Test
    void shufflePreservesClosedAndExiledCardsAndTheOpenMultiset() {
        var r = classicRoom(); var p = equip(r, "p18-c05"); var ps = players(r);
        ps.get(0).revealed.add("health"); ps.get(1).revealed.add("health"); ps.get(3).revealed.add("health"); ps.get(3).exiled = true;
        var open = Set.of(ps.get(0).sheet.get("health"), ps.get(1).sheet.get("health"));
        var closed = ps.get(2).sheet.get("health"); var exiled = ps.get(3).sheet.get("health");
        r.useAbility(p.id, "health", null);
        assertEquals(open, Set.of(ps.get(0).sheet.get("health"), ps.get(1).sheet.get("health")));
        assertEquals(closed, ps.get(2).sheet.get("health")); assertEquals(exiled, ps.get(3).sheet.get("health"));
    }

    @Test
    void stealGrantsAnAdditionalSingleUseCardWithoutReplacingExistingAbility() {
        var r = classicRoom(); var p = equip(r, "p19-c04"); var target = players(r).get(1);
        var oldAbility = target.ability; var baggage = target.sheet.get("baggage");
        r.useAbility(p.id, "baggage", target.id);
        assertEquals(baggage, p.sheet.get("baggage")); assertFalse(target.sheet.containsKey("baggage"));
        assertEquals(oldAbility, target.ability); assertEquals(1, target.extraAbilities.size());
        var extra = new RulePack.Ability("extra", "Вручную", "Объявление", "manual", 1);
        target.extraAbilities.clear(); target.extraAbilities.add(extra);
        r.useAbility(target.id, null, null, null, "extra");
        assertEquals(1, r.uses(target, extra));
        assertThrows(GameException.class, () -> r.useAbility(target.id, null, null, null, "extra"));
        assertThrows(GameException.class, () -> r.useAbility(p.id, null, null, null, "extra"));
    }

    @Test
    void loudVoiceChangesWeightNotBallotLengthAndExpires() {
        var r = classicRoom(); var p = equip(r, "p14-c07"); var target = players(r).get(1);
        assertThrows(GameException.class, () -> r.useAbility(p.id, null, null));
        r.setPhase(GameRoom.Phase.DISCUSSION); r.next();
        r.vote(p.id, List.of(target.id));
        r.useAbility(p.id, null, null); // Also allowed after the owner's ballot, while voting remains open.
        for (var other : players(r).subList(1, 4)) r.vote(other.id, List.of(target.id));
        assertEquals(5, ((Map<?, ?>)r.history.getLast().get("counts")).get(target.id));
        r.next();
        ballot(r, players(r).get(2).id);
        assertEquals(4, ((Map<?, ?>)r.history.getLast().get("counts")).get(players(r).get(2).id));
    }

    @Test
    void discreditAndCompromatChangeActualTallyAndParticipation() {
        var r = classicRoom(); var p = equip(r, "p18-c03"); var target = players(r).get(1);
        r.setPhase(GameRoom.Phase.DISCUSSION); r.next(); r.vote(target.id, List.of(target.id));
        r.useAbility(p.id, null, target.id);
        for (var other : players(r)) if (other != target) r.vote(other.id, List.of(target.id));
        assertEquals(3, ((Map<?, ?>)r.history.getLast().get("counts")).get(target.id));
        r = classicRoom(); p = equip(r, "p19-c06"); target = players(r).get(1);
        r.setPhase(GameRoom.Phase.DISCUSSION); r.useAbility(p.id, null, target.id); r.next();
        assertFalse(r.eligible.contains(p.id));
        for (var id : List.copyOf(r.eligible)) r.vote(id, List.of(target.id));
        assertEquals(6, ((Map<?, ?>)r.history.getLast().get("counts")).get(target.id));
    }

    @Test
    void planBRestoresExiledPlayerAndRequiresDifferentCandidates() {
        var r = classicRoom(); var p = equip(r, "p20-c05"); var ps = players(r);
        ballot(r, ps.get(1).id);
        assertTrue(ps.get(1).exiled);
        r.useAbility(p.id, null, null);
        assertFalse(ps.get(1).exiled); assertNull(r.lastExiledId);
        assertEquals(GameRoom.Phase.VOTING, r.phase);
        assertEquals(true, r.history.getFirst().get("superseded"));
        assertThrows(GameException.class, () -> r.vote(p.id, List.of(ps.get(1).id)));
        for (var id : List.copyOf(r.eligible)) r.vote(id, List.of(ps.get(2).id));
        assertTrue(ps.get(2).exiled); assertFalse(ps.get(1).exiled);
    }

    @Test
    void protectionConditionsTriggerNextVoteAndStaySingleUse() {
        for (var suffix : List.of("p18-c01", "p18-c07", "p18-c08", "p18-c09", "p19-c05")) {
            var r = classicRoom(); var p = equip(r, suffix); var ps = players(r);
            var victim = suffix.equals("p18-c01") ? ps.get(3) : ps.get(1);
            // Reveal age cards with an unambiguous youngest/oldest; closed ages must not participate.
            for (int i = 0; i < 2; i++) {
                ps.get(i).sheet.put("biology", new RulePack.Card("age" + i, "Возраст", "", Map.of("age", i == 0 ? "40" : suffix.equals("p19-c05") ? "70" : "18")));
                ps.get(i).revealed.add("biology");
            }
            if (suffix.equals("p18-c07")) {
                var original = victim.ability;
                victim.ability = new RulePack.Ability("open", "Открыть", "", "reveal_self", 1);
                r.useAbility(victim.id, "health", null); victim.ability = original; victim.abilityUses = 0;
            }
            ballot(r, victim.id); assertEquals(0, p.abilityUses);
            r.next(); assertEquals(1, p.abilityUses); assertTrue(p.forcedSelfVote, suffix);
            r.setPhase(GameRoom.Phase.DISCUSSION); r.next();
            assertTrue(r.canVoteFor(p.id, p.id));
            assertFalse(r.canVoteFor(p.id, ps.get(2).id));
            assertThrows(GameException.class, () -> r.vote(p.id, List.of(ps.get(2).id)));
            r.vote(p.id, List.of(p.id));
            for (var id : List.copyOf(r.eligible)) if (!id.equals(p.id)) r.vote(id, List.of(ps.get(2).id));
            r.next(); assertEquals(1, p.abilityUses); assertFalse(p.forcedSelfVote);
        }
    }

    @Test
    void directQuestionOverridesFirstCategoryAndDoesNotConsumeNormalTurnWhenUsed() {
        var r = classicRoom(); var p = equip(r, "p20-c03"); var other = players(r).get(1);
        r.useAbility(p.id, "health", null); r.revealBunker(0);
        assertThrows(GameException.class, () -> r.reveal(p.id, "profession"));
        r.reveal(p.id, "health"); r.next();
        assertEquals(other.id, r.currentPlayerId);
        r.reveal(other.id, "health");
    }

    @Test
    void customExtraBallotsHonorDurationAndForbidVoteRestrictsCandidates() {
        var r = classicRoom(); var p = r.player(r.hostId); var ps = players(r);
        p.ability = new RulePack.Ability("extra", "Голос", "", "extra_ballot", 1,
                new RulePack.AbilityOptions("before_vote", "self", "any", "any", "", "any", "game", null, 2, null, false));
        r.useAbility(p.id, null, null);
        assertEquals(2, r.ballotCount(p));
        r.setPhase(GameRoom.Phase.DISCUSSION); r.next();
        r.vote(p.id, List.of(ps.get(1).id, ps.get(1).id));
        for (var other : ps.subList(1, 4)) r.vote(other.id, List.of(ps.get(1).id));
        r.next(); assertEquals(2, r.ballotCount(p));
        p.ability = ability("p14-c05"); p.abilityUses = 0;
        r.useAbility(p.id, null, ps.get(2).id);
        r.setPhase(GameRoom.Phase.DISCUSSION); r.next();
        assertFalse(r.canVoteFor(ps.get(2).id, p.id));
        r.vote(ps.get(2).id, List.of(ps.get(3).id));
        assertEquals(1, p.abilityUses);
    }

    @Test
    void planBDoesNotTriggerProtectionForCancelledExileAndPauseNeverConsumesCard() {
        var r = classicRoom(); var owner = equip(r, "p18-c08"); var ps = players(r);
        ps.get(2).ability = ability("p20-c05");
        ballot(r, ps.get(1).id);
        r.useAbility(ps.get(2).id, null, null);
        for (var id : List.copyOf(r.eligible)) r.vote(id, List.of(ps.get(3).id));
        r.next(); assertEquals(0, owner.abilityUses); assertFalse(owner.forcedSelfVote);
        owner.ability = ability("p14-c06"); r.openBunker.add(0); r.paused = true;
        assertThrows(GameException.class, () -> r.useAbility(owner.id, null, null, 0, null));
        assertEquals(0, owner.abilityUses);
    }

    @Test
    void targetedExtraBallotsAffectOnlyChosenPlayerAndRejectMissingOrForbiddenTargets() throws Exception {
        var mapper = new ObjectMapper();
        var tree = mapper.valueToTree(GameRulesTest.pack(true, 1, Map.of("4", List.of(1))));
        var ability = (com.fasterxml.jackson.databind.node.ObjectNode) tree.get("abilities").get("cards").get(0);
        ability.put("effect", "extra_ballot");
        ability.set("options", mapper.createObjectNode().put("target", "other").put("timing", "before_vote")
                .put("targetState", "active").put("duration", "game").put("value", 3));
        var pack = mapper.treeToValue(tree, RulePack.class);
        pack.validate(true, 4);
        var r = classicRoom(); var ps = players(r); var owner = ps.getFirst(); var target = ps.get(2);
        owner.ability = pack.abilities().cards().getFirst();
        for (var invalid : Arrays.asList(null, "", "someone-else", owner.id)) {
            assertThrows(GameException.class, () -> r.useAbility(owner.id, null, invalid));
            assertEquals(0, owner.abilityUses); assertEquals(0, r.modifiers.size());
        }
        target.exiled = true;
        assertThrows(GameException.class, () -> r.useAbility(owner.id, null, target.id));
        assertEquals(0, owner.abilityUses); target.exiled = false;
        r.useAbility(owner.id, null, target.id);
        assertEquals(1, r.ballotCount(owner)); assertEquals(3, r.ballotCount(target));
        assertEquals(1, r.ballotCount(ps.get(1))); assertEquals(1, r.ballotCount(ps.get(3)));
        assertEquals(List.of(target.id), r.abilityHistory.getLast().get("targets"));
        assertThrows(GameException.class, () -> r.useAbility(owner.id, null, ps.get(1).id));
        assertEquals(1, r.ballotCount(ps.get(1)));
        r.setPhase(GameRoom.Phase.DISCUSSION); r.next();
        assertThrows(GameException.class, () -> r.vote(target.id, List.of(ps.get(1).id)));
        r.vote(target.id, List.of(ps.get(1).id, ps.get(1).id, ps.get(1).id));
        for (var other : ps) if (other != target) r.vote(other.id, List.of(ps.get(1).id));
        assertEquals(6, ((Map<?, ?>) r.history.getLast().get("counts")).get(ps.get(1).id));
    }

    @Test
    void customPackSupportsArbitraryCategoriesTimingReplacementAndLegacyDefaults() throws Exception {
        var mapper = new ObjectMapper();
        var tree = mapper.valueToTree(GameRulesTest.pack(true, 1, Map.of("4", List.of(1))));
        var card = (com.fasterxml.jackson.databind.node.ObjectNode)tree.get("abilities").get("cards").get(0);
        card.put("effect", "replace");
        var options = mapper.createObjectNode().put("timing", "after_vote").put("target", "any").put("category", "trait1").put("targetState", "any");
        options.set("replacement", mapper.valueToTree(new RulePack.Card("new-biography", "Новая биография", "Описание", Map.of("age", "52"))));
        card.set("options", options);
        var pack = mapper.treeToValue(tree, RulePack.class); pack.validate(true, 4);
        var r = new GameRoom("CUSTOM", pack);
        for (int i = 0; i < 4; i++) { var p = new GameRoom.Player("P" + i); r.players.put(p.id, p); if (i == 0) r.hostId = p.id; }
        r.start(); var p = r.player(r.hostId); p.ability = pack.abilities().cards().getFirst();
        var target = players(r).get(1);
        assertThrows(GameException.class, () -> r.useAbility(p.id, "trait1", target.id)); assertEquals(0, p.abilityUses);
        ballot(r, target.id); r.useAbility(p.id, "trait1", target.id);
        assertEquals("52", target.sheet.get("trait1").attributes().get("age"));
        assertEquals("Описание", target.sheet.get("trait1").text());
        assertThrows(GameException.class, () -> r.useAbility(p.id, "trait1", target.id));
        var legacy = mapper.readValue("{\"id\":\"old\",\"title\":\"old\",\"text\":\"\",\"effect\":\"reveal_self\",\"uses\":1}", RulePack.Ability.class);
        assertEquals("any", legacy.options().timing()); assertEquals("self", legacy.options().target());
        card.put("uses", 2);
        assertThrows(GameException.class, () -> mapper.treeToValue(tree, RulePack.class).validate(false, 0));
    }
}
