# Feature 3: Implementation Notes

## Status: Complete

## New Files

| File | Description |
|------|-------------|
| `forge-gui-desktop/src/main/java/forge/view/DslExporter.java` | Walks all `PaperCard` instances from `StaticData`, builds card + face records, resolves `ApiType` for every ability/trigger/sub-ability, writes `cards.jsonl` and `effects.jsonl` |

## Modified Files

| File | Change |
|------|--------|
| `forge-gui-desktop/src/main/java/forge/view/Main.java` | Added `case "export-dsl": DslExporter.export(args); break;` |
| `forge-gui-desktop/pom.xml` | Added `jackson-databind` dependency (version managed from root) |
| `pom.xml` (root) | Added `<jackson.version>2.17.2</jackson.version>` property and `jackson-databind` entry in `<dependencyManagement>` |

## CLI

```
java -jar forge.jar export-dsl --output cards.jsonl --effects effects.jsonl
```

## Key Design Decisions

- **`DslExporter` lives in `forge-gui-desktop`** because `FModel.initialize()` (which loads `StaticData`) is only available there.
- **SVars merged across both faces** before building face records — fixes DFC cards where a trigger on face A executes an SVar declared on face B.
- **Pre-filtered `exportableTypes` list** excludes internal `ApiType` aliases (`BlankLine`, `DamageResolve`, `ChangeZoneResolve`, `CompanionChoose`, `InternalLegendaryRule`, `InternalIgnoreEffect`, `InternalRadiation`) from the effects vocabulary.
- **`checkError()` after each loop** catches silent `PrintWriter` write failures.

## Output Format

### `cards.jsonl` — one record per unique card

```json
{
  "name": "Lightning Bolt",
  "mana_cost": "R",
  "cmc": 1,
  "types": ["Instant"],
  "subtypes": [],
  "supertypes": [],
  "oracle": "Lightning Bolt deals 3 damage to any target.",
  "faces": [
    {
      "name": "Lightning Bolt",
      "oracle": "Lightning Bolt deals 3 damage to any target.",
      "abilities": [
        {
          "kind": "spell",
          "raw_dsl": "SP$ DealDamage | ValidTgts$ Any | TgtPrompt$ Select target | NumDmg$ 3",
          "params": { "SP": "DealDamage", "ValidTgts": "Any", "NumDmg": "3" },
          "api_type": "DealDamage",
          "effect_class": "forge.game.ability.effects.DamageDealEffect"
        }
      ]
    }
  ]
}
```

### `effects.jsonl` — one record per `ApiType`

```json
{
  "api_type": "DealDamage",
  "effect_class": "forge.game.ability.effects.DamageDealEffect",
  "observed_params": ["NumDmg", "ValidTgts", "Source", "Defined", ...],
  "example_cards": ["Lightning Bolt", "Fireball", "Shock"]
}
```
