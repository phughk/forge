# Feature 4: DSL Byte Protocol / Tokenisation

## Goal

Design a compact byte encoding for the Forge card DSL that an LLM can be trained on and used for inference. Every token in the protocol maps to a fixed-size byte sequence; the vocabulary is small enough that a single or double byte covers all values in a given field.

The long-term protocol represents a card as a sequence of typed tokens:

```
<FIELD_TYPE byte> <VALUE byte(s)>  ...  <END_OF_CARD byte>
```

And a game action / state snapshot as:

```
<MSG_TYPE byte> <TURN byte> <PHASE byte> <PLAYER byte> ...token stream... <END_OF_MSG byte>
```

## Phase 1 (this feature): Vocabulary Survey

Before assigning byte values we need to know the full vocabulary for each field. This phase walks the 32,879 `.txt` card files in `forge-gui/res/cardsfolder/`, parses each into structured fields, and produces a frequency table for every distinct value in every field.

### Fields parsed

| Line prefix | Semantics | Collected statistic |
|---|---|---|
| `Name:` | Card name | counted (total cards) |
| `ManaCost:` | Raw mana cost string | frequency per cost string |
| `Types:` | Space-separated type tokens | frequency per token |
| `PT:` | "power/toughness" string | frequency per value |
| `K:` | Keyword (first colon-segment) | frequency per keyword name |
| `A:` | Activated/spell ability — pipe-separated `Key$ Value` pairs | `SP`/`AB`/`DB` → api-type frequency; all keys → param-key frequency |
| `T:` | Trigger — same format | `Mode$` value → trigger-mode frequency; all keys → param-key frequency |
| `S:` | Static ability — same format | `Mode$` value → static-mode frequency; all keys → param-key frequency |
| `SVar:name:value` | Named variable | frequency per SVar name |

### Output

Running `cargo run -- <path/to/cardsfolder>` prints a vocabulary report to stdout:

```
=== Card DSL Vocabulary Survey ===

Cards parsed :  32879
Parse errors :      0

--- ABILITY API TYPES ---
  unique=262 → 2 byte(s) min
  125438  DealDamage
   98271  ChangeZone
  ...

--- KEYWORDS ---
  unique=189 → 1 byte(s) min
  ...
```

The `unique=N → K byte(s)` line tells you the minimum byte width needed to encode that vocabulary. The goal is for every vocabulary to fit in 1 or 2 bytes.

## Phase 2 (future): Byte Assignment

Assign each vocabulary entry a canonical integer code. Publish a JSON/TOML codec table mapping `value → byte_code`. This becomes the training-time tokeniser and the inference-time decode table.

## Phase 3 (future): Encode / Decode

Implement `encode_card(card_txt) → Vec<u8>` and `decode(bytes) → card_txt` using the codec tables. This produces the training corpus in byte form.

## Relationship to Other Features

- Feature 3 (DSL Serialization) exports cards as human-readable JSONL. Feature 4 targets a compact byte encoding for ML training.
- Feature 2 (Training Data) records game decisions as JSONL. Feature 4's byte protocol will eventually encode game states and actions too.
- Feature 1 (HTTP Server) will eventually communicate via the byte protocol instead of JSON.
