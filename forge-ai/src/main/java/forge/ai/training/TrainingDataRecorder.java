package forge.ai.training;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class TrainingDataRecorder {

    private final String outputPath;
    private final ObjectMapper mapper;

    // Per-game state — reset on beginGame
    private String gameId;
    private List<Player> gamePlayers;
    private int recordIndex;
    private final List<Map<String, Object>> buffer = new ArrayList<>();

    public TrainingDataRecorder(String outputPath) {
        this.outputPath = outputPath;
        this.mapper = new ObjectMapper();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public void beginGame(Game game) {
        buffer.clear();
        recordIndex = 0;
        gameId = String.valueOf(game.getId());
        gamePlayers = new ArrayList<>(game.getPlayers());
    }

    public void onCastOrPass(Game game, Player player,
                              List<SpellAbility> available, List<SpellAbility> chosen) {
        if (available.isEmpty() && chosen.isEmpty()) return;
        Map<String, Object> record = buildBase(game, player, "cast_or_pass");
        record.put("available_actions", serializeSaList(available));
        record.put("chosen_action", chosen.isEmpty()
                ? mapOf("type", "PASS_PRIORITY")
                : serializeSa(chosen.get(0)));
        buffer.add(record);
    }

    public void onAttackersDeclared(Game game, Player attacker, Combat combat,
                                     CardCollection eligible) {
        if (eligible.isEmpty()) return;
        Map<String, Object> record = buildBase(game, attacker, "declare_attackers");
        record.put("available_actions", eligible.stream()
                .map(c -> cardAsAction(c, "POTENTIAL_ATTACKER"))
                .collect(Collectors.toList()));
        List<Map<String, Object>> chosen = new ArrayList<>();
        for (Card c : combat.getAttackers()) chosen.add(serializeCardRef(c));
        record.put("chosen_action", mapOf2("type", "DECLARE_ATTACKERS", "attackers", chosen));
        buffer.add(record);
    }

    public void onBlockersDeclared(Game game, Player defender, Combat combat,
                                    CardCollection eligible) {
        if (eligible.isEmpty() || combat.getAttackers().isEmpty()) return;
        Map<String, Object> record = buildBase(game, defender, "declare_blockers");
        record.put("available_actions", eligible.stream()
                .map(c -> cardAsAction(c, "POTENTIAL_BLOCKER"))
                .collect(Collectors.toList()));
        List<Map<String, Object>> assignments = new ArrayList<>();
        for (Card a : combat.getAttackers()) {
            for (Card b : combat.getBlockers(a)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("blocker", b.getName());
                entry.put("blocker_id", b.getId());
                entry.put("blocks", a.getName());
                entry.put("blocks_id", a.getId());
                assignments.add(entry);
            }
        }
        record.put("chosen_action", mapOf2("type", "DECLARE_BLOCKERS", "assignments", assignments));
        buffer.add(record);
    }

    public void finalizeGame(Game game) {
        GameOutcome outcome = game.getOutcome();
        if (outcome == null) {
            flush();
            return;
        }
        boolean draw = outcome.isDraw();
        int winnerIdx = -1;
        if (!draw) {
            RegisteredPlayer winReg = outcome.getWinningPlayer();
            if (winReg != null) {
                for (int i = 0; i < gamePlayers.size(); i++) {
                    if (gamePlayers.get(i).getRegisteredPlayer() == winReg) {
                        winnerIdx = i;
                        break;
                    }
                }
            }
        }
        final int finalWinnerIdx = winnerIdx;
        for (Map<String, Object> record : buffer) {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) record.get("metadata");
            if (meta != null) {
                int playerIdx = (Integer) meta.get("player_index");
                if (draw) {
                    meta.put("outcome", "DRAW");
                } else {
                    meta.put("outcome", playerIdx == finalWinnerIdx ? "WIN" : "LOSS");
                }
            }
        }
        flush();
    }

    // ─── record construction ──────────────────────────────────────────────────

    private Map<String, Object> buildBase(Game game, Player player, String decisionType) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("game_id", gameId);
        rec.put("record_index", recordIndex++);
        rec.put("decision_type", decisionType);
        rec.put("turn", game.getPhaseHandler().getTurn());
        rec.put("phase", game.getPhaseHandler().getPhase().name());
        rec.put("state", buildState(game, player));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("player_index", gamePlayers.indexOf(player));
        meta.put("player_name", player.getLobbyPlayer().getName());
        meta.put("deck", player.getRegisteredPlayer().getDeck().getName());
        meta.put("format", game.getRules().getGameType().name());
        meta.put("outcome", null);
        rec.put("metadata", meta);
        return rec;
    }

    // ─── state serialization ──────────────────────────────────────────────────

    private Map<String, Object> buildState(Game game, Player active) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("active_player", game.getPhaseHandler().getPlayerTurn().getLobbyPlayer().getName());

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : game.getPlayers()) players.add(buildPlayerState(p, p == active));
        state.put("players", players);

        List<Map<String, Object>> stack = new ArrayList<>();
        for (SpellAbilityStackInstance si : game.getStack()) {
            SpellAbility sa = si.getSpellAbility();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("card", sa.getHostCard().getName());
            if (sa.getApi() != null) entry.put("api_type", sa.getApi().name());
            stack.add(entry);
        }
        if (!stack.isEmpty()) state.put("stack", stack);

        return state;
    }

    private Map<String, Object> buildPlayerState(Player p, boolean isActive) {
        Map<String, Object> ps = new LinkedHashMap<>();
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
        int totalMana = mana.values().stream().mapToInt(Integer::intValue).sum();
        if (totalMana > 0) ps.put("mana_pool", mana);

        if (isActive) {
            List<String> hand = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Hand)) hand.add(c.getName());
            ps.put("hand", hand);
        } else {
            ps.put("hand_size", p.getCardsIn(ZoneType.Hand).size());
        }

        List<Map<String, Object>> battlefield = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) battlefield.add(serializeCard(c));
        if (!battlefield.isEmpty()) ps.put("battlefield", battlefield);

        List<String> graveyard = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Graveyard)) graveyard.add(c.getName());
        if (!graveyard.isEmpty()) ps.put("graveyard", graveyard);

        return ps;
    }

    // ─── card / action serialization ─────────────────────────────────────────

    private Map<String, Object> serializeCard(Card c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.getName());
        m.put("id", c.getId());
        m.put("type", c.getType().toString());
        if (c.isCreature()) {
            m.put("power", c.getNetPower());
            m.put("toughness", c.getNetToughness());
        }
        if (c.isTapped()) m.put("tapped", true);
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (Map.Entry<CounterType, Integer> e : c.getCounters().entrySet()) {
            if (e.getValue() > 0) counters.put(e.getKey().toString(), e.getValue());
        }
        if (!counters.isEmpty()) m.put("counters", counters);
        return m;
    }

    private Map<String, Object> serializeCardRef(Card c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.getName());
        m.put("id", c.getId());
        return m;
    }

    private Map<String, Object> cardAsAction(Card c, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("card", c.getName());
        m.put("card_id", c.getId());
        return m;
    }

    private Map<String, Object> serializeSa(SpellAbility sa) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "CAST_OR_ACTIVATE");
        m.put("card", sa.getHostCard().getName());
        m.put("card_id", sa.getHostCard().getId());
        if (sa.getApi() != null) m.put("api_type", sa.getApi().name());
        m.put("description", sa.toString());
        Map<String, String> params = sa.getMapParams();
        if (params != null && !params.isEmpty()) m.put("params", params);
        return m;
    }

    private List<Map<String, Object>> serializeSaList(List<SpellAbility> sas) {
        return sas.stream().map(this::serializeSa).collect(Collectors.toList());
    }

    // ─── I/O ─────────────────────────────────────────────────────────────────

    private void flush() {
        try {
            try (PrintWriter out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(outputPath, true), StandardCharsets.UTF_8)))) {
                for (Map<String, Object> record : buffer) {
                    try {
                        out.println(mapper.writeValueAsString(record));
                    } catch (JsonProcessingException e) {
                        System.err.println("Skipping unserializable record: " + e.getMessage());
                    }
                }
                if (out.checkError()) System.err.println("Write error flushing to " + outputPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to open " + outputPath + " for writing: " + e.getMessage());
        } finally {
            buffer.clear();
        }
    }

    // ─── utilities ────────────────────────────────────────────────────────────

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static Map<String, Object> mapOf2(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
