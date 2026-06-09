package forge.view.server;

import forge.game.Game;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class GameSession {

    final String id;
    final Game game;

    // Game thread puts state here when a decision is needed; HTTP thread polls.
    final LinkedBlockingQueue<Map<String, Object>> stateQueue = new LinkedBlockingQueue<>(1);

    // HTTP thread puts the client's action here; game thread polls.
    final LinkedBlockingQueue<Map<String, Object>> actionQueue = new LinkedBlockingQueue<>(1);

    final AtomicBoolean gameOver = new AtomicBoolean(false);

    GameSession(String id, Game game) {
        this.id = id;
        this.game = game;
    }
}
