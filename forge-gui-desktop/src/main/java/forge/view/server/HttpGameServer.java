package forge.view.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.*;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.TextUtil;
import forge.util.storage.IStorage;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class HttpGameServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void start(String[] args) throws Exception {
        int port = 8080;
        for (int i = 1; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }

        FModel.initialize(null, null);

        Map<String, GameSession> sessions = new ConcurrentHashMap<>();
        AtomicInteger counter = new AtomicInteger(0);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/game/start", new StartHandler(sessions, counter));
        server.createContext("/game/", new GameHandler(sessions));
        server.start();

        System.out.printf("Forge HTTP server listening on port %d%n", port);
        System.out.println("  POST /game/start  {\"decks\":[\"Name1\",\"Name2\"],\"ai_slots\":[1]}");
        System.out.println("  GET  /game/{id}/state");
        System.out.println("  POST /game/{id}/action  {\"type\":\"PASS_PRIORITY\"}");
        System.out.println("  POST /game/{id}/stop");

        // Block the calling thread so System.exit(0) in Main.java is not reached.
        Thread.currentThread().join();
    }

    // ─── /game/start ─────────────────────────────────────────────────────────

    private static final class StartHandler implements HttpHandler {
        private final Map<String, GameSession> sessions;
        private final AtomicInteger counter;

        StartHandler(Map<String, GameSession> sessions, AtomicInteger counter) {
            this.sessions = sessions;
            this.counter = counter;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            Map<String, Object> body;
            try {
                body = MAPPER.readValue(readBody(ex), Map.class);
            } catch (Exception e) {
                respond(ex, 400, "{\"error\":\"Invalid JSON\"}");
                return;
            }

            List<String> deckNames = castList(body.get("decks"), String.class);
            if (deckNames == null || deckNames.size() < 2) {
                respond(ex, 400, "{\"error\":\"'decks' must have at least 2 entries\"}");
                return;
            }
            List<Integer> aiSlots = castList(body.get("ai_slots"), Integer.class);
            if (aiSlots == null) aiSlots = Collections.emptyList();

            String formatStr = body.containsKey("format") ? (String) body.get("format") : "Constructed";
            GameType gameType = parseGameType(formatStr);

            List<RegisteredPlayer> regPlayers = new ArrayList<>();
            for (int i = 0; i < deckNames.size(); i++) {
                Deck d = loadDeck(deckNames.get(i), gameType);
                if (d == null) {
                    respond(ex, 400, json("error", "Deck not found: " + deckNames.get(i)));
                    return;
                }
                String name = TextUtil.concatNoSpace("Player", String.valueOf(i + 1), "-", d.getName());
                RegisteredPlayer rp = new RegisteredPlayer(d);
                rp.setPlayer(GamePlayerUtil.createAiPlayer(name, i));
                regPlayers.add(rp);
            }

            GameRules rules = new GameRules(gameType);
            rules.setAppliedVariants(EnumSet.of(gameType));
            Match mc = new Match(rules, regPlayers, "HttpGame");
            Game g1 = mc.createGame();

            String sessionId = String.format("%06d", counter.incrementAndGet());
            GameSession session = new GameSession(sessionId, g1);

            // Inject HTTP controllers for non-AI player slots
            List<Player> players = new ArrayList<>(g1.getPlayers());
            for (int i = 0; i < players.size(); i++) {
                if (!aiSlots.contains(i)) {
                    Player p = players.get(i);
                    PlayerControllerHttp ctrl = new PlayerControllerHttp(
                            g1, p, p.getLobbyPlayer(), i, session);
                    p.dangerouslySetController(ctrl);
                }
            }

            // Register session before starting the thread so any state.put is immediately reachable.
            sessions.put(sessionId, session);

            final List<Integer> finalAiSlots = aiSlots;
            Thread gameThread = new Thread(() -> runGame(mc, g1, session, finalAiSlots));
            gameThread.setDaemon(true);
            gameThread.start();

            // Return first decision (or game_over if game ends immediately)
            Map<String, Object> firstState;
            try {
                firstState = session.stateQueue.poll(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(ex, 503, "{\"error\":\"Interrupted waiting for game start\"}");
                return;
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("session_id", sessionId);
            if (firstState != null) {
                resp.putAll(firstState);
            } else {
                resp.put("game_over", true);
            }
            respond(ex, 200, MAPPER.writeValueAsString(resp));
        }
    }

    // ─── /game/{id}/state  and  /game/{id}/action  and  /game/{id}/stop ─────

    private static final class GameHandler implements HttpHandler {
        private final Map<String, GameSession> sessions;

        GameHandler(Map<String, GameSession> sessions) {
            this.sessions = sessions;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath(); // e.g. /game/000001/state
            String[] parts = path.split("/");
            // parts: ["", "game", "000001", "state"]
            if (parts.length < 4) {
                respond(ex, 404, "{\"error\":\"Not found\"}");
                return;
            }
            String sessionId = parts[2];
            String endpoint = parts[3];
            GameSession session = sessions.get(sessionId);
            if (session == null) {
                respond(ex, 404, json("error", "Session not found: " + sessionId));
                return;
            }

            try {
                if ("state".equals(endpoint) && "GET".equals(ex.getRequestMethod())) {
                    handleGetState(ex, session);
                } else if ("action".equals(endpoint) && "POST".equals(ex.getRequestMethod())) {
                    handlePostAction(ex, session);
                } else if ("stop".equals(endpoint) && "POST".equals(ex.getRequestMethod())) {
                    handleStop(ex, session, sessions);
                } else {
                    respond(ex, 404, "{\"error\":\"Unknown endpoint\"}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(ex, 503, "{\"error\":\"Server interrupted\"}");
            }
        }

        private static void handleGetState(HttpExchange ex, GameSession session)
                throws IOException, InterruptedException {
            Map<String, Object> state = session.stateQueue.poll(30, TimeUnit.SECONDS);
            if (state == null) {
                if (session.gameOver.get()) {
                    respond(ex, 200, "{\"game_over\":true}");
                } else {
                    respond(ex, 408, "{\"error\":\"Timeout waiting for game state\"}");
                }
                return;
            }
            respond(ex, 200, MAPPER.writeValueAsString(state));
        }

        private static void handlePostAction(HttpExchange ex, GameSession session)
                throws IOException, InterruptedException {
            if (session.gameOver.get()) {
                respond(ex, 400, "{\"error\":\"Game is already over\"}");
                return;
            }
            Map<String, Object> action;
            try {
                action = MAPPER.readValue(readBody(ex), Map.class);
            } catch (Exception e) {
                respond(ex, 400, "{\"error\":\"Invalid JSON\"}");
                return;
            }
            if (!session.actionQueue.offer(action)) {
                respond(ex, 429, "{\"error\":\"An action is already pending\"}");
                return;
            }
            // Wait for the next decision state from the game thread
            Map<String, Object> nextState = session.stateQueue.poll(30, TimeUnit.SECONDS);
            if (nextState == null) {
                if (session.gameOver.get()) {
                    respond(ex, 200, "{\"game_over\":true}");
                } else {
                    respond(ex, 408, "{\"error\":\"Timeout waiting for next game state\"}");
                }
                return;
            }
            respond(ex, 200, MAPPER.writeValueAsString(nextState));
        }

        private static void handleStop(HttpExchange ex, GameSession session,
                                        Map<String, GameSession> sessions)
                throws IOException {
            sessions.remove(session.id);
            session.gameOver.set(true);
            // Unblock game thread BEFORE setGameOver tears down controllers.
            session.actionQueue.offer(Collections.singletonMap("type", "PASS_PRIORITY"));
            session.game.setGameOver(GameEndReason.Draw);
            respond(ex, 200, "{}");
        }
    }

    // ─── game thread ─────────────────────────────────────────────────────────

    private static void runGame(Match mc, Game g1, GameSession session,
                                  List<Integer> aiSlots) {
        try {
            mc.startGame(g1);
        } catch (Exception e) {
            System.err.println("Game error: " + e.getMessage());
        } finally {
            g1.setGameOver(GameEndReason.Draw);
            session.gameOver.set(true);
            Map<String, Object> gameOverState = new LinkedHashMap<>();
            gameOverState.put("game_over", true);
            gameOverState.put("turn", g1.getPhaseHandler().getTurn());
            GameOutcome outcome = g1.getOutcome();
            if (outcome != null && !outcome.isDraw() && outcome.getWinningLobbyPlayer() != null) {
                gameOverState.put("winner", outcome.getWinningLobbyPlayer().getName());
            } else {
                gameOverState.put("winner", null);
            }
            // Unblock any HTTP thread waiting for the next state.
            session.stateQueue.offer(gameOverState);
        }
    }

    // ─── utilities ───────────────────────────────────────────────────────────

    private static GameType parseGameType(String raw) {
        if (raw == null) return GameType.Constructed;
        String normalised = Character.toUpperCase(raw.charAt(0)) + raw.substring(1).toLowerCase();
        try { return GameType.valueOf(normalised); } catch (IllegalArgumentException e) { return GameType.Constructed; }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object o, Class<T> cls) {
        if (!(o instanceof List)) return null;
        return (List<T>) o;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String json(String key, String value) {
        return "{\"" + key + "\":\"" + value.replace("\"", "\\\"") + "\"}";
    }

    private static Deck loadDeck(String deckname, GameType type) {
        int dotpos = deckname.lastIndexOf('.');
        if (dotpos > 0 && dotpos == deckname.length() - 4) {
            String baseDir = GameType.Commander.equals(type)
                    ? ForgeConstants.DECK_COMMANDER_DIR : ForgeConstants.DECK_CONSTRUCTED_DIR;
            File f = new File(baseDir + deckname);
            return DeckSerializer.fromFile(f);
        }
        IStorage<Deck> store = GameType.Commander.equals(type)
                ? FModel.getDecks().getCommander()
                : FModel.getDecks().getConstructed();
        return store.get(deckname);
    }

    private HttpGameServer() {}
}
