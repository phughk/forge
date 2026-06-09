package forge.view.server;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A PlayerController that surfaces the three main decision points
 * (cast-or-pass, declare attackers, declare blockers) to an HTTP client
 * and falls back to the Forge AI for all other decisions.
 */
public final class PlayerControllerHttp extends PlayerControllerAi {

    private final int playerIndex;
    private final GameSession session;

    public PlayerControllerHttp(Game game, Player p, LobbyPlayer lp,
                                  int playerIndex, GameSession session) {
        super(game, p, lp);
        this.playerIndex = playerIndex;
        this.session = session;
    }

    // ─── decision overrides ──────────────────────────────────────────────────

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> available = computeAvailableSpells();
        if (available.isEmpty()) return super.chooseSpellAbilityToPlay();

        Map<String, Object> statePayload = buildDecisionState("cast_or_pass", available, null, null);
        try {
            if (!session.stateQueue.offer(statePayload, 5, TimeUnit.SECONDS)) {
                return super.chooseSpellAbilityToPlay();
            }
            Map<String, Object> action = session.actionQueue.poll(60, TimeUnit.SECONDS);
            if (action == null) {
                session.stateQueue.poll();  // remove stale state not consumed by HTTP
                session.actionQueue.poll(); // drain any late-arriving action
                return super.chooseSpellAbilityToPlay();
            }
            return decodeCastOrPass(action, available);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return super.chooseSpellAbilityToPlay();
        }
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        CardCollection eligible = computeEligibleAttackers(attacker);
        if (eligible.isEmpty()) {
            super.declareAttackers(attacker, combat);
            return;
        }
        Map<String, Object> statePayload = buildDecisionState("declare_attackers", null, eligible, null);
        try {
            if (!session.stateQueue.offer(statePayload, 5, TimeUnit.SECONDS)) {
                super.declareAttackers(attacker, combat);
                return;
            }
            Map<String, Object> action = session.actionQueue.poll(60, TimeUnit.SECONDS);
            if (action == null) {
                session.stateQueue.poll();
                super.declareAttackers(attacker, combat);
                return;
            }
            decodeAttackers(action, attacker, combat, eligible);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            super.declareAttackers(attacker, combat);
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        CardCollection eligible = computeEligibleBlockers(defender, combat);
        if (eligible.isEmpty() || combat.getAttackers().isEmpty()) {
            super.declareBlockers(defender, combat);
            return;
        }
        Map<String, Object> statePayload = buildDecisionState("declare_blockers", null, eligible, combat);
        try {
            if (!session.stateQueue.offer(statePayload, 5, TimeUnit.SECONDS)) {
                super.declareBlockers(defender, combat);
                return;
            }
            Map<String, Object> action = session.actionQueue.poll(60, TimeUnit.SECONDS);
            if (action == null) {
                session.stateQueue.poll();
                super.declareBlockers(defender, combat);
                return;
            }
            decodeBlockers(action, combat, eligible);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            super.declareBlockers(defender, combat);
        }
    }

    // ─── action decoding ─────────────────────────────────────────────────────

    private List<SpellAbility> decodeCastOrPass(Map<String, Object> action,
                                                  List<SpellAbility> available) {
        String type = (String) action.get("type");
        if (type == null || "PASS_PRIORITY".equals(type)) return Collections.emptyList();

        Object rawCardId = action.get("card_id");
        if (rawCardId == null) return Collections.emptyList();
        int cardId = toInt(rawCardId);

        Object rawAbilIdx = action.get("ability_index");
        int abilIdx = rawAbilIdx != null ? toInt(rawAbilIdx) : 0;

        // Find matching SA from available list
        List<SpellAbility> matches = available.stream()
                .filter(sa -> sa.getHostCard().getId() == cardId)
                .collect(Collectors.toList());
        if (matches.isEmpty()) return Collections.emptyList();

        SpellAbility chosen = (abilIdx < matches.size()) ? matches.get(abilIdx) : matches.get(0);
        return Collections.singletonList(chosen);
    }

    @SuppressWarnings("unchecked")
    private void decodeAttackers(Map<String, Object> action, Player attacker,
                                   Combat combat, CardCollection eligible) {
        List<Object> attackerIds = (List<Object>) action.get("attackers");
        if (attackerIds == null || attackerIds.isEmpty()) return;

        List<Player> opponents = new ArrayList<>(attacker.getOpponents());
        if (opponents.isEmpty()) return;
        Player defender = opponents.get(0);

        for (Object rawId : attackerIds) {
            int cardId = toInt(rawId);
            for (Card c : eligible) {
                if (c.getId() == cardId) {
                    combat.addAttacker(c, defender);
                    break;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void decodeBlockers(Map<String, Object> action, Combat combat,
                                  CardCollection eligible) {
        List<Map<String, Object>> assignments = (List<Map<String, Object>>) action.get("assignments");
        if (assignments == null) return;

        for (Map<String, Object> assignment : assignments) {
            int blockerId = toInt(assignment.get("blocker_id"));
            int attackerId = toInt(assignment.get("attacker_id"));

            Card blocker = eligible.stream()
                    .filter(c -> c.getId() == blockerId)
                    .findFirst().orElse(null);
            Card attacker = combat.getAttackers().stream()
                    .filter(c -> c.getId() == attackerId)
                    .findFirst().orElse(null);

            if (blocker != null && attacker != null) {
                combat.addBlocker(attacker, blocker);
            }
        }
    }

    // ─── state serialization ─────────────────────────────────────────────────

    private Map<String, Object> buildDecisionState(String decisionType,
                                                     List<SpellAbility> castChoices,
                                                     CardCollection combatEligible,
                                                     Combat combat) {
        Map<String, Object> payload = buildGameState();

        Map<String, Object> awaiting = new LinkedHashMap<>();
        awaiting.put("player_index", playerIndex);
        awaiting.put("decision_type", decisionType);
        awaiting.put("choices", buildChoices(decisionType, castChoices, combatEligible, combat));
        payload.put("awaiting_action", awaiting);

        return payload;
    }

    private Map<String, Object> buildGameState() {
        Game g = player.getGame();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("turn", g.getPhaseHandler().getTurn());
        state.put("phase", g.getPhaseHandler().getPhase().name());
        state.put("active_player", g.getPhaseHandler().getPlayerTurn().getLobbyPlayer().getName());

        List<Map<String, Object>> playerStates = new ArrayList<>();
        List<Player> allPlayers = new ArrayList<>(g.getPlayers());
        for (int i = 0; i < allPlayers.size(); i++) {
            playerStates.add(buildPlayerState(allPlayers.get(i), i));
        }
        state.put("players", playerStates);

        List<Map<String, Object>> stack = new ArrayList<>();
        for (SpellAbilityStackInstance si : g.getStack()) {
            SpellAbility sa = si.getSpellAbility();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("card", sa.getHostCard().getName());
            if (sa.getApi() != null) entry.put("api_type", sa.getApi().name());
            stack.add(entry);
        }
        if (!stack.isEmpty()) state.put("stack", stack);

        return state;
    }

    private Map<String, Object> buildPlayerState(Player p, int index) {
        Map<String, Object> ps = new LinkedHashMap<>();
        ps.put("index", index);
        ps.put("name", p.getLobbyPlayer().getName());
        ps.put("life", p.getLife());
        ps.put("library_size", p.getCardsIn(ZoneType.Library).size());

        Map<String, Integer> mana = new LinkedHashMap<>();
        mana.put("W", p.getManaPool().getAmountOfColor((byte) ManaAtom.WHITE));
        mana.put("U", p.getManaPool().getAmountOfColor((byte) ManaAtom.BLUE));
        mana.put("B", p.getManaPool().getAmountOfColor((byte) ManaAtom.BLACK));
        mana.put("R", p.getManaPool().getAmountOfColor((byte) ManaAtom.RED));
        mana.put("G", p.getManaPool().getAmountOfColor((byte) ManaAtom.GREEN));
        mana.put("C", p.getManaPool().getAmountOfColor((byte) ManaAtom.COLORLESS));
        ps.put("mana_pool", mana);

        if (index == playerIndex) {
            // Active HTTP player: full hand visible
            List<Map<String, Object>> hand = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Hand)) hand.add(serializeCardFull(c));
            ps.put("hand", hand);
        } else {
            // Opponent: hidden hand
            List<Map<String, Object>> hand = new ArrayList<>();
            for (int i = 0; i < p.getCardsIn(ZoneType.Hand).size(); i++) {
                hand.add(Collections.singletonMap("hidden", true));
            }
            ps.put("hand", hand);
        }

        List<Map<String, Object>> battlefield = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) battlefield.add(serializeCardBattlefield(c));
        ps.put("battlefield", battlefield);

        List<Map<String, Object>> graveyard = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Graveyard)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", c.getId());
            entry.put("name", c.getName());
            graveyard.add(entry);
        }
        ps.put("graveyard", graveyard);

        return ps;
    }

    private Map<String, Object> serializeCardFull(Card c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("mana_cost", c.getManaCost().isNoCost() ? null : c.getManaCost().toString());
        m.put("types", c.getType().toString());
        if (c.isCreature()) {
            m.put("power", c.getNetPower());
            m.put("toughness", c.getNetToughness());
        }
        return m;
    }

    private Map<String, Object> serializeCardBattlefield(Card c) {
        Map<String, Object> m = serializeCardFull(c);
        if (c.isTapped()) m.put("tapped", true);
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (Map.Entry<CounterType, Integer> e : c.getCounters().entrySet()) {
            if (e.getValue() > 0) counters.put(e.getKey().toString(), e.getValue());
        }
        if (!counters.isEmpty()) m.put("counters", counters);
        return m;
    }

    // ─── choices list ────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildChoices(String decisionType,
                                                     List<SpellAbility> castChoices,
                                                     CardCollection combatEligible,
                                                     Combat combat) {
        List<Map<String, Object>> choices = new ArrayList<>();
        if ("cast_or_pass".equals(decisionType)) {
            choices.add(Collections.singletonMap("type", "PASS_PRIORITY"));
            if (castChoices != null) {
                for (SpellAbility sa : castChoices) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("type", sa.isLandAbility() ? "PLAY_LAND" : "CAST_SPELL");
                    c.put("card_id", sa.getHostCard().getId());
                    c.put("card_name", sa.getHostCard().getName());
                    if (sa.getApi() != null) c.put("api_type", sa.getApi().name());
                    c.put("description", sa.toString());
                    choices.add(c);
                }
            }
        } else if ("declare_attackers".equals(decisionType) && combatEligible != null) {
            for (Card c : combatEligible) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "DECLARE_ATTACKER");
                entry.put("card_id", c.getId());
                entry.put("card_name", c.getName());
                entry.put("power", c.getNetPower());
                entry.put("toughness", c.getNetToughness());
                choices.add(entry);
            }
        } else if ("declare_blockers".equals(decisionType) && combatEligible != null) {
            for (Card c : combatEligible) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", "POTENTIAL_BLOCKER");
                entry.put("card_id", c.getId());
                entry.put("card_name", c.getName());
                entry.put("power", c.getNetPower());
                entry.put("toughness", c.getNetToughness());
                choices.add(entry);
            }
            if (combat != null) {
                List<Map<String, Object>> attackers = new ArrayList<>();
                for (Card a : combat.getAttackers()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("card_id", a.getId());
                    entry.put("card_name", a.getName());
                    entry.put("power", a.getNetPower());
                    entry.put("toughness", a.getNetToughness());
                    attackers.add(entry);
                }
                choices.add(Collections.singletonMap("attackers", attackers));
            }
        }
        return choices;
    }

    // ─── utilities ───────────────────────────────────────────────────────────

    private static int toInt(Object o) {
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Long) return ((Long) o).intValue();
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return -1; }
    }
}
