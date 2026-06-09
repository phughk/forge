# Feature 4: Implementation Notes

## Status: Complete (Phase 1 + DuckDB Index)

## Project

`llm_spec/dsl_prototype/` — standalone Rust project (`edition = "2024"`, depends on `duckdb` with `bundled` feature).

## New / Modified Files

| File | Change |
|------|--------|
| `llm_spec/dsl_prototype/src/main.rs` | Card parser, DuckDB indexer, vocabulary reporter |
| `llm_spec/dsl_prototype/Cargo.toml` | Added `duckdb = { version = "…", features = ["bundled"] }` |
| `llm_spec/feature4/spec.md` | Feature specification |

## CLI

```
cd llm_spec/dsl_prototype
cargo run -- <cards_dir> [db_path]
# defaults: ../forge-gui/res/cardsfolder  cards.db
```

Example:
```
cargo run -- ../../forge-gui/res/cardsfolder cards.db
```

Outputs vocab stats to stdout and writes the DuckDB index to `cards.db`.

## Vocabulary Survey Results (32,877 cards, 0 parse errors)

| Field | Unique values | Min bytes |
|---|---|---|
| Mana costs | 859 | 2 |
| Type tokens | 558 | 2 |
| Keywords (`K:`) | 250 | 1 |
| Ability API types (`SP$/AB$/DB$`) | 152 | **1** |
| Trigger modes (`T: Mode$`) | 135 | **1** |
| Static modes (`S: Mode$`) | 81 | **1** |
| Param keys (all `Key$` names) | 863 | 2 |
| SVar names | 6,151 | 2 |
| P/T values | 140 | **1** |

Key finding: the most semantically rich fields (ability API types, keywords, trigger modes, P/T) each fit in a single byte. Param keys and type tokens need 2 bytes.

## DuckDB Schema

One row per structured value extracted from a card file. 636,772 rows across 32,877 cards.

```sql
CREATE TABLE card_dsl (
    card_name   VARCHAR NOT NULL,
    line_num    INTEGER NOT NULL,  -- 1-based line in the .txt file
    line_type   VARCHAR NOT NULL,  -- ManaCost | Types | PT | K | A | T | S | SVar
    param_key   VARCHAR NOT NULL,  -- key in a Key$Value pair, or field name for flat fields
    param_value VARCHAR NOT NULL   -- the value
);
CREATE INDEX idx_kv   ON card_dsl(param_key, param_value);
CREATE INDEX idx_card ON card_dsl(card_name);
```

Row counts by `line_type`:

| line_type | rows | distinct cards |
|---|---|---|
| SVar | 251,905 | 24,665 |
| A (abilities) | 106,420 | 15,606 |
| T (triggers) | 99,193 | 13,663 |
| Types | 74,750 | 32,876 |
| S (static) | 34,300 | 6,074 |
| ManaCost | 33,805 | 32,876 |
| PT | 18,663 | 18,328 |
| K (keywords) | 17,736 | 13,682 |

### Example queries

```sql
-- Which ability types appear on the most cards?
SELECT param_value AS api_type, COUNT(DISTINCT card_name) AS cards
FROM card_dsl WHERE param_key IN ('SP', 'AB', 'DB')
GROUP BY param_value ORDER BY cards DESC LIMIT 10;

-- Find all cards that can target anything
SELECT DISTINCT card_name FROM card_dsl
WHERE param_key = 'ValidTgts' AND param_value = 'Any';

-- How many cards have Flying?
SELECT COUNT(DISTINCT card_name) FROM card_dsl
WHERE line_type = 'K' AND param_value = 'Flying';

-- What are the most shared (key, value) pairs across cards?
SELECT param_key, param_value, COUNT(DISTINCT card_name) AS cards
FROM card_dsl GROUP BY param_key, param_value
ORDER BY cards DESC LIMIT 20;

-- All params used on DealDamage abilities
SELECT param_key, COUNT(*) AS n FROM card_dsl
WHERE line_type = 'A'
  AND card_name IN (
      SELECT DISTINCT card_name FROM card_dsl
      WHERE param_key = 'SP' AND param_value = 'DealDamage')
GROUP BY param_key ORDER BY n DESC;
```

## Design

### Parsing

Each card `.txt` file is read line-by-line. Recognised prefixes:

| Prefix | Action |
|---|---|
| `Name:` | Counts the card |
| `ManaCost:` | Records the full cost string |
| `Types:` | Splits on whitespace, records each token |
| `PT:` | Records the raw "N/M" string |
| `K:` | Extracts first colon-segment as the keyword name |
| `A:` | Parses pipe-separated `Key$ Value` pairs; records `SP`/`AB`/`DB` values as api-types |
| `T:` | Same; records `Mode$` values as trigger-modes |
| `S:` | Same; records `Mode$` values as static-modes |
| `SVar:name:value` | Extracts the SVar name |

### `parse_params`

Splits on `|`, then splits each segment on `$ ` (with space, or `$` without) to extract key/value pairs. Returns `Vec<(String, String)>`.

### Output

`print_vocab` sorts by descending frequency, prints `unique=N → K byte(s) min` header, then top-N entries (or all if `top_n == 0`).

## Next Steps (Phase 2)

1. Assign stable integer codes to each vocabulary (sorted by frequency → most common = lowest code).
2. Export codec tables as TOML/JSON.
3. Implement `encode_card(path) → Vec<u8>` and `decode(bytes) → String`.
