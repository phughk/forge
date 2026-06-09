use duckdb::{Appender, Connection, params};
use std::collections::HashMap;
use std::fs;
use std::path::Path;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    let cards_dir = args.get(1).map(String::as_str).unwrap_or("../forge-gui/res/cardsfolder");
    let db_path   = args.get(2).map(String::as_str).unwrap_or("cards.db");

    let conn = Connection::open(db_path)?;
    conn.execute_batch(
        "DROP TABLE IF EXISTS card_dsl;
         CREATE TABLE card_dsl (
             card_name   VARCHAR NOT NULL,
             line_num    INTEGER NOT NULL,
             line_type   VARCHAR NOT NULL,
             param_index INTEGER NOT NULL,
             param_key   VARCHAR NOT NULL,
             param_value VARCHAR NOT NULL
         );",
    )?;

    eprintln!("Scanning {} → {}", cards_dir, db_path);
    let mut stats = Stats::default();
    {
        let mut app = conn.appender("card_dsl")?;
        walk_dir(Path::new(cards_dir), &mut stats, &mut app);
        app.flush()?;
    }

    // Indexes after bulk load — much faster than building during insert
    conn.execute_batch(
        "CREATE INDEX idx_kv   ON card_dsl(param_key, param_value);
         CREATE INDEX idx_card ON card_dsl(card_name);
         CREATE INDEX idx_pos  ON card_dsl(line_type, param_index, param_key);",
    )?;

    eprintln!("Done — {} cards, {} parse errors", stats.total_cards, stats.parse_errors);
    print_report(&stats);
    Ok(())
}

// ── Schema ──────────────────────────────────────────────────────────────────
//
// One row per (card, structured value).
//
// param_index is the 0-based position of each Key$Value pair within its
// pipe-separated sequence.  For the line:
//
//   A:AB$ Mana | Cost$ 1 | Produced$ G | ActivationLimit$ 1
//
// four rows are produced:
//   (…, "A", 0, "AB",              "Mana")
//   (…, "A", 1, "Cost",            "1")
//   (…, "A", 2, "Produced",        "G")
//   (…, "A", 3, "ActivationLimit", "1")
//
// For flat / single-valued lines (ManaCost, PT, K) param_index is 0.
// For space-tokenised Types, param_index is the token position.
// For SVar name rows the synthetic param_index -1 distinguishes the name
// metadata from the 0-based value params that follow.

// ── Vocab stats (printed to stdout after the DB is written) ─────────────────

#[derive(Default)]
struct Stats {
    total_cards: u32,
    parse_errors: u32,
    mana_costs: HashMap<String, u32>,
    type_tokens: HashMap<String, u32>,
    keywords: HashMap<String, u32>,
    ability_api_types: HashMap<String, u32>,
    trigger_modes: HashMap<String, u32>,
    static_modes: HashMap<String, u32>,
    param_keys: HashMap<String, u32>,
    svar_names: HashMap<String, u32>,
    pt_values: HashMap<String, u32>,
}

// ── Directory traversal ──────────────────────────────────────────────────────

fn walk_dir(dir: &Path, stats: &mut Stats, app: &mut Appender<'_>) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    let mut paths: Vec<_> = entries.flatten().map(|e| e.path()).collect();
    paths.sort();
    for path in paths {
        if path.is_dir() {
            walk_dir(&path, stats, app);
        } else if path.extension().map_or(false, |e| e == "txt") {
            if parse_card_file(&path, stats, app).is_err() {
                stats.parse_errors += 1;
            }
        }
    }
}

// ── Per-file parsing ─────────────────────────────────────────────────────────

fn parse_card_file(path: &Path, stats: &mut Stats, app: &mut Appender<'_>) -> std::io::Result<()> {
    let content = fs::read_to_string(path)?;

    // Card name is needed for every row, so find it in a first scan.
    let card_name: String = match content
        .lines()
        .find_map(|l| l.trim().strip_prefix("Name:").map(str::to_owned))
    {
        Some(n) => n,
        None => return Ok(()), // not a card file
    };

    stats.total_cards += 1;

    for (i, raw) in content.lines().enumerate() {
        let line_num = (i + 1) as i32;
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with("Name:") {
            continue;
        }

        if let Some(rest) = line.strip_prefix("ManaCost:") {
            inc(&mut stats.mana_costs, rest);
            emit(app, &card_name, line_num, "ManaCost", 0, "ManaCost", rest);

        } else if let Some(rest) = line.strip_prefix("Types:") {
            for (i, tok) in rest.split_whitespace().enumerate() {
                inc(&mut stats.type_tokens, tok);
                emit(app, &card_name, line_num, "Types", i as i32, "Types", tok);
            }

        } else if let Some(rest) = line.strip_prefix("PT:") {
            inc(&mut stats.pt_values, rest);
            emit(app, &card_name, line_num, "PT", 0, "PT", rest);

        } else if let Some(rest) = line.strip_prefix("K:") {
            // "Flying", "Morph:X B B", "ETBReplacement:Other:ChooseCT" — take first segment
            let kw = rest.split(':').next().unwrap_or(rest).trim();
            if !kw.is_empty() {
                inc(&mut stats.keywords, kw);
                emit(app, &card_name, line_num, "K", 0, "keyword", kw);
            }

        } else if let Some(rest) = line.strip_prefix("A:") {
            for (i, (k, v)) in parse_params(rest).into_iter().enumerate() {
                inc(&mut stats.param_keys, &k);
                if matches!(k.as_str(), "SP" | "AB" | "DB") {
                    inc(&mut stats.ability_api_types, &v);
                }
                emit(app, &card_name, line_num, "A", i as i32, &k, &v);
            }

        } else if let Some(rest) = line.strip_prefix("T:") {
            for (i, (k, v)) in parse_params(rest).into_iter().enumerate() {
                inc(&mut stats.param_keys, &k);
                if k == "Mode" {
                    inc(&mut stats.trigger_modes, &v);
                }
                emit(app, &card_name, line_num, "T", i as i32, &k, &v);
            }

        } else if let Some(rest) = line.strip_prefix("S:") {
            for (i, (k, v)) in parse_params(rest).into_iter().enumerate() {
                inc(&mut stats.param_keys, &k);
                if k == "Mode" {
                    inc(&mut stats.static_modes, &v);
                }
                emit(app, &card_name, line_num, "S", i as i32, &k, &v);
            }

        } else if let Some(rest) = line.strip_prefix("SVar:") {
            if let Some(colon) = rest.find(':') {
                let name = rest[..colon].trim();
                let value = rest[colon + 1..].trim();
                if !name.is_empty() {
                    inc(&mut stats.svar_names, name);
                    // -1 marks this as the name row, separate from the 0-based value params
                    emit(app, &card_name, line_num, "SVar", -1, "svar_name", name);
                    // Parse the value: might be ability params ("DB$ PumpAll | ...") or a raw scalar
                    let kv_pairs = parse_params(value);
                    if kv_pairs.is_empty() {
                        // Raw scalar ("2", "TRUE") — no $ found at all
                        if !value.is_empty() {
                            emit(app, &card_name, line_num, "SVar", 0, "svar_value", value);
                        }
                    } else {
                        for (i, (k, v)) in kv_pairs.into_iter().enumerate() {
                            inc(&mut stats.param_keys, &k);
                            if matches!(k.as_str(), "SP" | "AB" | "DB") {
                                inc(&mut stats.ability_api_types, &v);
                            }
                            emit(app, &card_name, line_num, "SVar", i as i32, &k, &v);
                        }
                    }
                }
            }
        }
    }

    Ok(())
}

// Splits "SP$ DealDamage | ValidTgts$ Any | NumDmg$ 3" into
// [("SP","DealDamage"), ("ValidTgts","Any"), ("NumDmg","3")]
fn parse_params(line: &str) -> Vec<(String, String)> {
    line.split('|')
        .filter_map(|seg| {
            let seg = seg.trim();
            // Try "Key$ Value" first (most common), then "Key$Value" (no space)
            if let Some(pos) = seg.find("$ ") {
                Some((seg[..pos].trim().to_owned(), seg[pos + 2..].trim().to_owned()))
            } else if let Some(pos) = seg.find('$') {
                Some((seg[..pos].trim().to_owned(), seg[pos + 1..].trim().to_owned()))
            } else {
                None
            }
        })
        .collect()
}

fn inc(map: &mut HashMap<String, u32>, key: &str) {
    *map.entry(key.to_owned()).or_default() += 1;
}

fn emit(app: &mut Appender<'_>, card: &str, line: i32, ltype: &str, idx: i32, key: &str, val: &str) {
    let _ = app.append_row(params![card, line, ltype, idx, key, val]);
}

// ── Vocab report (stdout) ────────────────────────────────────────────────────

fn print_report(stats: &Stats) {
    println!("\n=== Card DSL Vocabulary Survey ===\n");
    println!("Cards parsed : {:>6}", stats.total_cards);
    println!("Parse errors : {:>6}", stats.parse_errors);
    println!();

    print_vocab("MANA COSTS",        &stats.mana_costs,        30);
    print_vocab("TYPE TOKENS",       &stats.type_tokens,        0);
    print_vocab("KEYWORDS",          &stats.keywords,           0);
    print_vocab("ABILITY API TYPES", &stats.ability_api_types,  0);
    print_vocab("TRIGGER MODES",     &stats.trigger_modes,      0);
    print_vocab("STATIC MODES",      &stats.static_modes,       0);
    print_vocab("PARAM KEYS",        &stats.param_keys,         0);
    print_vocab("SVAR NAMES",        &stats.svar_names,        40);
    print_vocab("POWER/TOUGHNESS",   &stats.pt_values,         30);
}

fn print_vocab(title: &str, map: &HashMap<String, u32>, top_n: usize) {
    let n = map.len();
    let bytes_min = if n <= 256 { 1 } else if n <= 65_536 { 2 } else { 3 };

    println!("--- {} ---", title);
    println!("  unique={} → {} byte(s) min", n, bytes_min);

    let mut entries: Vec<(&String, &u32)> = map.iter().collect();
    entries.sort_by(|a, b| b.1.cmp(a.1).then(a.0.cmp(b.0)));

    let show = if top_n == 0 { entries.len() } else { top_n.min(entries.len()) };
    for (key, count) in entries.iter().take(show) {
        println!("    {:>6}  {}", count, key);
    }
    if top_n > 0 && n > top_n {
        println!("  ... ({} more not shown)", n - top_n);
    }
    println!();
}
