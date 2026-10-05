package ru.bunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Данные .bunker, без исполняемого кода. Встроенная классика выбирается только сервером.
 */
public record RulePack(String format, int schemaVersion, String id, String name, String description,
                       Rules rules, List<Category> categories, List<Card> catastrophes,
                       List<Card> bunker, Abilities abilities, List<Card> threats,
                       @JsonInclude(JsonInclude.Include.NON_NULL) CharacterGeneration characterGeneration) {
    public RulePack {
        // Optional in older v1 files; threats are a shared deck, never a character trait.
        threats = threats == null ? List.of() : List.copyOf(threats);
    }

    public RulePack(String format, int schemaVersion, String id, String name, String description,
                    Rules rules, List<Category> categories, List<Card> catastrophes,
                    List<Card> bunker, Abilities abilities, List<Card> threats) {
        this(format, schemaVersion, id, name, description, rules, categories, catastrophes, bunker, abilities, threats, null);
    }

    public RulePack(String format, int schemaVersion, String id, String name, String description,
                    Rules rules, List<Category> categories, List<Card> catastrophes,
                    List<Card> bunker, Abilities abilities) {
        this(format, schemaVersion, id, name, description, rules, categories, catastrophes, bunker, abilities, List.of());
    }
    public static final Map<String, List<Integer>> CLASSIC_SCHEDULE = Map.ofEntries(
            Map.entry("4", List.of(0, 0, 0, 1, 1)), Map.entry("5", List.of(0, 0, 1, 1, 1)),
            Map.entry("6", List.of(0, 0, 1, 1, 1)), Map.entry("7", List.of(0, 1, 1, 1, 1)),
            Map.entry("8", List.of(0, 1, 1, 1, 1)), Map.entry("9", List.of(0, 1, 1, 1, 2)),
            Map.entry("10", List.of(0, 1, 1, 1, 2)), Map.entry("11", List.of(0, 1, 1, 2, 2)),
            Map.entry("12", List.of(0, 1, 1, 2, 2)), Map.entry("13", List.of(0, 1, 2, 2, 2)),
            Map.entry("14", List.of(0, 1, 2, 2, 2)), Map.entry("15", List.of(0, 2, 2, 2, 2)),
            Map.entry("16", List.of(0, 2, 2, 2, 2)));

    public static RulePack classic() {
        return ClassicHolder.PACK;
    }

    private static final class ClassicHolder {
        private static final RulePack PACK = load();

        private static RulePack load() {
            try (var source = RulePack.class.getResourceAsStream("/rules/classic.bunker")) {
                if (source == null) throw new IllegalStateException("Не найден встроенный классический набор.");
                var pack = new ObjectMapper().readValue(source, RulePack.class);
                pack.validate(true, pack.rules.maxPlayers);
                if (!"classic".equals(pack.id) || !CLASSIC_SCHEDULE.equals(pack.rules.votingSchedule))
                    throw new IllegalStateException("Некорректные правила встроенного классического набора.");
                return pack;
            } catch (IOException e) {
                throw new IllegalStateException("Не удалось прочитать классические карточки.", e);
            }
        }
    }

    private static void validateCards(List<Card> cards, Set<String> ids) {
        require(cards != null && cards.size() <= 500, "В одной колоде допускается до 500 карт.");
        for (var card : cards) {
            require(card != null && card.id != null && card.id.matches("[a-zA-Z0-9_-]{1,64}") && ids.add(card.id), "Карты должны иметь уникальные ID во всём наборе.");
            require(card.title != null && !card.title.isBlank() && card.title.length() <= 200 && card.text != null && card.text.length() <= 4000, "У карты должны быть заголовок и текст (до 4000 символов).");
            require(card.attributes.size() <= 20 && card.attributes.entrySet().stream().allMatch(e -> e.getKey().matches("[a-zA-Z0-9_-]{1,64}") && e.getValue().length() <= 200), "Некорректные свойства карты.");
            require(!card.attributes.containsKey("age") || card.attributes.get("age").matches("\\d{1,5}"), "Возраст карты: целое число 0–99999.");
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new GameException(400, message);
    }

    public void validate(boolean ready, int players) {
        require("bunker".equals(format) && schemaVersion == 1, "Неподдерживаемый формат .bunker.");
        require(id != null && id.matches("[a-zA-Z0-9_-]{1,64}"), "Нужен ID набора: латиница, цифры, дефис.");
        require(name != null && !name.isBlank() && name.length() <= 100, "Название: от 1 до 100 символов.");
        require(description == null || description.length() <= 4000, "Описание слишком длинное.");
        require(rules != null && categories != null && catastrophes != null && bunker != null && abilities != null, "Набор не содержит обязательных разделов.");
        if (characterGeneration != null) {
            require(!"classic".equals(id), "Генератор биологии доступен только личным пакам.");
            characterGeneration.validate();
            require(categories.stream().anyMatch(c -> c != null && characterGeneration.category.equals(c.id)), "Категория генерируемой биологии отсутствует в наборе.");
        }
        require(rules.roundCount >= 1 && rules.roundCount <= 20, "Число раундов: 1–20.");
        require(rules.minPlayers >= 4 && rules.maxPlayers <= 16 && rules.minPlayers <= rules.maxPlayers, "В этом каркасе поддерживается 4–16 игроков.");
        require(rules.votesPerVoter >= 1 && rules.votesPerVoter <= 5, "Число голосов: 1–5.");
        require(rules.speechSeconds >= 0 && rules.speechSeconds <= 600 && rules.discussionSeconds >= 0 && rules.discussionSeconds <= 600, "Длительность: 0–600 секунд.");
        require(categories.size() >= rules.roundCount && categories.size() <= 30, "Категорий должно быть не меньше числа раундов (до 30).");
        require(rules.votingSchedule != null, "Не задано расписание голосований.");
        for (int n = rules.minPlayers; n <= rules.maxPlayers; n++) {
            var schedule = rules.votingSchedule.get(String.valueOf(n));
            require(schedule != null && schedule.size() == rules.roundCount, "Задайте все раунды для " + n + " игроков.");
            require(schedule.stream().allMatch(v -> v != null && v >= 0 && v <= 4), "В раунде допускается 0–4 голосования.");
            require(schedule.stream().mapToInt(Integer::intValue).sum() < n, "Расписание не может изгнать всех игроков.");
        }
        var categoryIds = new HashSet<String>();
        var cardIds = new HashSet<String>();
        for (var category : categories) {
            require(category != null && category.id != null && category.id.matches("[a-zA-Z0-9_-]{1,64}") && categoryIds.add(category.id), "Категории должны иметь уникальные ID.");
            require(category.name != null && !category.name.isBlank() && category.name.length() <= 100, "Некорректное название категории.");
            validateCards(category.cards, cardIds);
            if (ready && !generatesCategory(category.id))
                require(category.cards.size() >= players, "Не хватает карт в категории «" + category.name + "»: нужно " + players + ".");
        }
        require(rules.firstRevealCategory != null && (rules.firstRevealCategory.isEmpty() || categoryIds.contains(rules.firstRevealCategory)), "Первая открываемая категория отсутствует.");
        validateCards(catastrophes, cardIds);
        validateCards(bunker, cardIds);
        validateCards(threats, cardIds);
        require(abilities.cards != null && abilities.cards.size() <= 500, "Некорректный раздел возможностей.");
        require(abilities.enabled || abilities.cards.isEmpty(), "Удалите возможности, если они отключены.");
        for (var ability : abilities.cards) {
            require(ability != null && ability.id != null && ability.id.matches("[a-zA-Z0-9_-]{1,64}") && cardIds.add(ability.id), "Повторяющийся или неверный ID возможности.");
            require(ability.title != null && !ability.title.isBlank() && ability.title.length() <= 200 && ability.text != null && ability.text.length() <= 4000, "Некорректная возможность.");
            require(EFFECTS.contains(ability.effect == null ? "" : ability.effect), "Неизвестный эффект возможности.");
            require(ability.uses == 1, "Каждая возможность применяется один раз за игру (uses: 1).");
            ability.options.validate(categoryIds, ability.effect);
        }
        if (ready) {
            require(players >= rules.minPlayers && players <= rules.maxPlayers, "Число игроков вне диапазона набора.");
            require(!catastrophes.isEmpty(), "Добавьте карты катастроф.");
            require(bunker.size() >= rules.roundCount, "Не хватает карт бункера: нужно " + rules.roundCount + ".");
            require(!abilities.enabled || abilities.cards.size() >= players, "При включённых возможностях нужна хотя бы одна карта на игрока.");
        }
    }

    public record Card(String id, String title, String text, Map<String, String> attributes) {
        public Card(String id, String title, String text) { this(id, title, text, null); }
        public Card {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record Category(String id, String name, List<Card> cards) {
    }

    public boolean generatesCategory(String category) {
        return characterGeneration != null && !"classic".equals(id) && characterGeneration.category.equals(category);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CharacterGeneration(String category, List<String> species, Double femalePercent,
                                      Double lgbtPercent, Integer minAge, Integer maxAge,
                                      Double childfreePercent, List<SpeciesGeneration> speciesRules) {
        public CharacterGeneration(String category, List<SpeciesGeneration> speciesRules) {
            this(category, null, null, null, null, null, null, speciesRules);
        }
        public CharacterGeneration(String category, List<String> species, Double femalePercent,
                                   Double lgbtPercent, Integer minAge, Integer maxAge, Double childfreePercent) {
            this(category, species, femalePercent, lgbtPercent, minAge, maxAge, childfreePercent, null);
        }
        public CharacterGeneration(String category, List<String> species, Double femalePercent,
                                   Double lgbtPercent, Integer minAge, Integer maxAge) {
            this(category, species, femalePercent, lgbtPercent, minAge, maxAge, 0.0);
        }
        public CharacterGeneration {
            if (speciesRules == null && childfreePercent == null) childfreePercent = 0.0;
        }
        public List<SpeciesGeneration> profiles() {
            return speciesRules != null ? speciesRules : species.stream().map(s -> new SpeciesGeneration(
                    s, femalePercent, lgbtPercent, minAge, maxAge, childfreePercent)).toList();
        }
        void validate() {
            require(category != null && category.matches("[a-zA-Z0-9_-]{1,64}"), "Выберите категорию для генерируемой биологии.");
            if (speciesRules != null) {
                require(species == null && femalePercent == null && lgbtPercent == null && minAge == null && maxAge == null
                        && childfreePercent == null, "Задайте параметры по видам либо общий диапазон старого формата, без смешивания.");
                require(!speciesRules.isEmpty() && speciesRules.size() <= 30, "Задайте от 1 до 30 видов.");
                var names = new HashSet<String>();
                for (var profile : speciesRules) {
                    require(profile != null, "У вида должны быть параметры биологии.");
                    profile.validate();
                    require(names.add(profile.species.toLowerCase(java.util.Locale.ROOT)), "Названия видов должны быть уникальными.");
                }
                return;
            }
            require(species != null && !species.isEmpty() && species.size() <= 30, "Задайте от 1 до 30 видов.");
            var unique = new HashSet<String>();
            for (var value : species) require(value != null && !value.isBlank() && value.length() <= 64
                    && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl)
                    && unique.add(value.toLowerCase(java.util.Locale.ROOT)), "Виды: уникальные названия до 64 символов без переносов.");
            require(femalePercent != null && Double.isFinite(femalePercent) && femalePercent >= 0 && femalePercent <= 100,
                    "Вероятность женского пола: 0–100%.");
            require(lgbtPercent != null && Double.isFinite(lgbtPercent) && lgbtPercent >= 0 && lgbtPercent <= 100,
                    "Вероятность ЛГБТ: 0–100%.");
            require(Double.isFinite(childfreePercent) && childfreePercent >= 0 && childfreePercent <= 100,
                    "Вероятность чайлдфри: 0–100%.");
            require(minAge != null && maxAge != null && minAge >= 0 && maxAge <= 99999 && minAge <= maxAge,
                    "Возраст: целые числа от 0 до 99999; минимум не больше максимума.");
        }
    }

    public record SpeciesGeneration(String species, Double femalePercent, Double lgbtPercent,
                                    Integer minAge, Integer maxAge, Double childfreePercent) {
        public SpeciesGeneration {
            childfreePercent = childfreePercent == null ? 0.0 : childfreePercent;
        }
        void validate() {
            require(species != null && !species.isBlank() && species.length() <= 64 && species.equals(species.trim())
                    && species.chars().noneMatch(Character::isISOControl), "Вид: название до 64 символов без переносов.");
            require(femalePercent != null && Double.isFinite(femalePercent) && femalePercent >= 0 && femalePercent <= 100,
                    "Вероятность женского пола для вида «" + species + "»: 0–100%.");
            require(lgbtPercent != null && Double.isFinite(lgbtPercent) && lgbtPercent >= 0 && lgbtPercent <= 100,
                    "Вероятность ЛГБТ для вида «" + species + "»: 0–100%.");
            require(Double.isFinite(childfreePercent) && childfreePercent >= 0 && childfreePercent <= 100,
                    "Вероятность чайлдфри для вида «" + species + "»: 0–100%.");
            require(minAge != null && maxAge != null && minAge >= 0 && maxAge <= 99999 && minAge <= maxAge,
                    "Возраст для вида «" + species + "»: целые 0–99999; минимум не больше максимума.");
        }
    }

    public static final Set<String> EFFECTS = Set.of("reveal_self", "reveal_target", "redraw_self", "extra_ballot",
            "reveal", "redraw", "replace", "discard", "swap", "shuffle", "steal", "vote_weight", "cancel_vote",
            "double_against", "forbid_vote", "revote", "force_reveal", "silence", "bunker_redraw",
            "bunker_discard", "bunker_steal", "add_threat", "protect_vote", "manual");

    public record Ability(String id, String title, String text, String effect, int uses, AbilityOptions options) {
        public Ability(String id, String title, String text, String effect, int uses) {
            this(id, title, text, effect, uses, null);
        }
        public Ability {
            options = options == null ? AbilityOptions.defaults(effect) : options;
        }
    }

    /** Declarative conditions, shared by the built-in deck and imported packs. No executable scripts. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AbilityOptions(String timing, String target, String actor, String targetState, String category,
                                 String visibility, String duration, String condition, Integer value,
                                 Card replacement, boolean compensation) {
        public AbilityOptions {
            timing = timing == null ? "any" : timing;
            target = target == null ? "self" : target;
            actor = actor == null ? "active" : actor;
            targetState = targetState == null ? "active" : targetState;
            category = category == null ? "" : category;
            visibility = visibility == null ? "any" : visibility;
            duration = duration == null ? "vote" : duration;
            condition = condition == null ? "none" : condition;
            value = value == null ? 2 : value;
        }
        static AbilityOptions defaults(String effect) {
            return new AbilityOptions("extra_ballot".equals(effect) ? "before_vote" : "any",
                    "reveal_target".equals(effect) ? "other" : "self", null, null, null, null, null, null, null, null, false);
        }
        void validate(Set<String> categories, String effect) {
            require(Set.of("any", "before_vote", "during_vote", "after_vote").contains(timing), "Неизвестное время применения.");
            require(Set.of("self", "other", "any", "neighbor", "all", "none").contains(target), "Неизвестный выбор цели.");
            require(Set.of("active", "exiled", "any").contains(actor) && Set.of("active", "exiled", "any").contains(targetState), "Неизвестное состояние игрока.");
            require(category.isEmpty() || categories.contains(category), "Характеристика возможности отсутствует в наборе.");
            require(Set.of("any", "open", "closed").contains(visibility), "Неизвестное состояние характеристики.");
            require(Set.of("vote", "round", "game").contains(duration), "Неизвестный срок действия.");
            require(Set.of("none", "left", "right", "first_revealed", "youngest", "oldest").contains(condition), "Неизвестное условие защиты.");
            require(value >= 1 && value <= 5, "Величина эффекта: 1–5.");
            if (Set.of("reveal", "reveal_self", "reveal_target").contains(effect))
                require(!visibility.equals("open"), "Нельзя открыть уже открытую характеристику.");
            if (Set.of("vote_weight", "cancel_vote", "double_against").contains(effect))
                require(!timing.equals("after_vote"), "Изменение подсчёта нужно применять до объявления результата.");
            if (effect.equals("extra_ballot")) {
                require(value >= 2, "Для дополнительного голоса нужно минимум два бюллетеня.");
                require(Set.of("any", "before_vote").contains(timing), "Дополнительные бюллетени выдаются до голосования.");
            }
            if (Set.of("force_reveal", "silence").contains(effect))
                require(Set.of("any", "before_vote").contains(timing), "Этот эффект применяется до голосования.");
            if (effect.equals("revote")) require(!timing.equals("before_vote"), "Для переголосования нужен начатый бюллетень.");
            if (Set.of("reveal_self", "redraw_self").contains(effect))
                require(target.equals("self"), "Этот эффект применяется только к себе.");
            if (Set.of("swap", "steal", "forbid_vote", "double_against").contains(effect))
                require(Set.of("other", "any", "neighbor").contains(target), "Для этого эффекта нужен выбранный игрок.");
            if (effect.equals("shuffle")) require(target.equals("all"), "Перераздача применяется ко всем подходящим игрокам.");
            if (Set.of("reveal", "reveal_self", "reveal_target", "redraw", "redraw_self", "replace", "discard", "vote_weight", "cancel_vote", "extra_ballot").contains(effect))
                require(!target.equals("none"), "Для этого эффекта нужна цель.");
            if (effect.equals("reveal_target")) require(Set.of("other", "any", "neighbor").contains(target), "Выберите игрока для открытия характеристики.");
            if (Set.of("revote", "force_reveal", "silence", "bunker_redraw", "bunker_discard", "bunker_steal", "add_threat", "protect_vote").contains(effect))
                require(target.equals("none"), "Для этого эффекта цель — общее поле или автоматическое условие.");
            if (effect.equals("replace")) {
                require(replacement != null, "Задайте новую карту характеристики.");
                validateCards(List.of(replacement), new HashSet<>());
            } else require(replacement == null, "Новая карта задаётся только для эффекта «Задать характеристику».");
            require(!compensation || effect.equals("steal"), "Компенсация возможна только при краже характеристики.");
            if (effect.equals("protect_vote")) {
                require(timing.equals("any") && actor.equals("any"), "Защита срабатывает автоматически независимо от этапа и состояния владельца.");
                require(!condition.equals("none"), "Выберите условие обязательного голоса против себя.");
                if (Set.of("first_revealed", "youngest", "oldest").contains(condition))
                    require(!category.isEmpty(), "Для условия защиты нужна фиксированная характеристика.");
            } else require(condition.equals("none"), "Условие защиты доступно только для обязательного голоса против себя.");
        }
    }

    public record Abilities(boolean enabled, List<Ability> cards) {
    }

    public record Rules(int roundCount, int minPlayers, int maxPlayers, String firstRevealCategory,
                        int speechSeconds, int discussionSeconds, int votesPerVoter,
                        boolean lastExiledVotes, boolean allowSelfVote,
                        Map<String, List<Integer>> votingSchedule) {
    }
}
