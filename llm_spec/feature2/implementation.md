# Feature 2: Implementation Notes

## Status: Complete

## New Files

| File | Description |
|------|-------------|
| `forge-ai/src/main/java/forge/ai/training/TrainingDataRecorder.java` | Buffers decision records per game; serializes state/actions; fills `outcome` (WIN/LOSS/DRAW) via `RegisteredPlayer` identity when the game ends; flushes JSONL in append mode |

## Modified Files

| File | Change |
|------|--------|
| `forge-ai/src/main/java/forge/ai/PlayerControllerAi.java` | Added `trainingRecorder` field + `setTrainingRecorder()`; hooked `chooseSpellAbilityToPlay()`, `declareAttackers()`, `declareBlockers()`; added protected helpers `computeAvailableSpells()`, `computeEligibleAttackers()`, `computeEligibleBlockers()` |
| `forge-gui-desktop/src/main/java/forge/view/SimulateMatch.java` | Added `-training-data <path>` flag; creates `TrainingDataRecorder`; calls `recorder.beginGame(g1)` / `recorder.finalizeGame(g1)` around each game; injects recorder into AI controllers via `setTrainingRecorder()` |
| `forge-ai/pom.xml` | Added `jackson-databind` dependency (version managed from root) |

## CLI

```
java -jar forge.jar sim -d "RG Aggro" "UW Control" -n 100 -training-data games.jsonl
```

Without `-training-data`, the recorder is not instantiated and there is zero overhead.

## Key Design Decisions

- **In-memory buffer per game**: all records buffered in RAM during the game; `finalizeGame()` fills outcomes and flushes to disk in one pass. No temp files needed.
- **Outcome by `RegisteredPlayer` identity**: `GameOutcome.getWinningPlayer()` returns a `RegisteredPlayer` reference; matched by identity (`==`) against `gamePlayers.get(playerIdx).getRegisteredPlayer()`, avoiding name-collision bugs in AI-vs-AI simulations with identical deck names.
- **`buffer.clear()` in `finally`**: the flush loop clears the buffer unconditionally so an I/O error in one game doesn't leak records into the next game's flush.
- **`computeEligibleBlockers` uses `canBlockAtLeastOne`**: filters out creatures that are physically unable to block any of the declared attackers (e.g., ground creatures vs. flyers), preventing spurious `POTENTIAL_BLOCKER` entries.
- **`SimulateMatch.simulateSingleMatch` overloaded**: a no-arg recorder variant preserves backward compatibility with the tournament code path.

## Output Format

One JSON object per line, appended to the file across all games.

```json
{
  "game_id": "1",
  "record_index": 14,
  "decision_type": "cast_or_pass",
  "turn": 5,
  "phase": "MAIN1",
  "state": {
    "active_player": "Player1-RG Aggro",
    "players": [
      {
        "name": "Player1-RG Aggro",
        "life": 20,
        "library_size": 47,
        "hand": ["Lightning Bolt", "Forest", "Llanowar Elves"],
        "battlefield": [
          {"name": "Forest", "id": 7, "type": "Land Forest", "tapped": true},
          {"name": "Grizzly Bears", "id": 12, "type": "Creature Bear", "power": 2, "toughness": 2}
        ],
        "graveyard": []
      },
      {
        "name": "Player2-UW Control",
        "life": 17,
        "library_size": 49,
        "hand_size": 5,
        "battlefield": [],
        "graveyard": []
      }
    ]
  },
  "available_actions": [
    {"type": "CAST_OR_ACTIVATE", "card": "Lightning Bolt", "card_id": 3, "api_type": "DealDamage", "description": "Lightning Bolt deals 3 damage to any target."}
  ],
  "chosen_action": {"type": "PASS_PRIORITY"},
  "metadata": {
    "player_index": 0,
    "player_name": "Player1-RG Aggro",
    "deck": "RG Aggro",
    "format": "Constructed",
    "outcome": "WIN"
  }
}
```

### Decision types

| `decision_type` | Triggered by | `chosen_action` shape |
|---|---|---|
| `cast_or_pass` | `chooseSpellAbilityToPlay()` | `{type: PASS_PRIORITY}` or `{type: CAST_OR_ACTIVATE, card, card_id, api_type, ...}` |
| `declare_attackers` | `declareAttackers()` | `{type: DECLARE_ATTACKERS, attackers: [{name, id}, ...]}` |
| `declare_blockers` | `declareBlockers()` | `{type: DECLARE_BLOCKERS, assignments: [{blocker, blocker_id, blocks, blocks_id}, ...]}` |
