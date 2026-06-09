package forge.view;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.StaticData;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.item.PaperCard;
import forge.model.FModel;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class DslExporter {

    // Internal/alias ApiType values excluded from the effects vocabulary
    private static final Set<String> INTERNAL_TYPES = new HashSet<>(Arrays.asList(
            "BlankLine", "DamageResolve", "ChangeZoneResolve",
            "CompanionChoose", "InternalLegendaryRule", "InternalIgnoreEffect", "InternalRadiation"
    ));

    public static void export(String[] args) {
        String outputPath = "cards.jsonl";
        String effectsPath = "effects.jsonl";

        for (int i = 1; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) outputPath = args[++i];
            else if ("--effects".equals(args[i]) && i + 1 < args.length) effectsPath = args[++i];
        }

        FModel.initialize(null, null);

        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // Pre-filter once; used for both the tracking map setup and the vocabulary write
        List<ApiType> exportableTypes = new ArrayList<>();
        for (ApiType at : ApiType.values()) {
            if (!INTERNAL_TYPES.contains(at.name())) exportableTypes.add(at);
        }

        Map<String, Set<String>> observedParams = new LinkedHashMap<>();
        Map<String, List<String>> exampleCards = new LinkedHashMap<>();
        for (ApiType at : exportableTypes) {
            observedParams.put(at.name(), new TreeSet<>());
            exampleCards.put(at.name(), new ArrayList<>());
        }

        int exported = 0, skipped = 0;
        boolean cardWriteError = false;

        try (PrintWriter out = openWriter(outputPath)) {
            for (PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
                try {
                    Map<String, Object> record = buildCardRecord(pc.getRules(), observedParams, exampleCards);
                    out.println(mapper.writeValueAsString(record));
                    exported++;
                } catch (Exception e) {
                    skipped++;
                }
            }
            cardWriteError = out.checkError();
        } catch (IOException e) {
            System.err.println("Failed to write " + outputPath + ": " + e.getMessage());
            cardWriteError = true;
        }
        if (cardWriteError) System.err.println("Write error on " + outputPath + " — output may be truncated");

        boolean effectsWriteError = false;

        try (PrintWriter eff = openWriter(effectsPath)) {
            for (ApiType at : exportableTypes) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("api_type", at.name());
                rec.put("effect_class", at.getSpellEffect().getClass().getName());
                rec.put("observed_params", new ArrayList<>(observedParams.get(at.name())));
                List<String> ex = exampleCards.get(at.name());
                rec.put("example_cards", ex.subList(0, Math.min(3, ex.size())));
                eff.println(mapper.writeValueAsString(rec));
            }
            effectsWriteError = eff.checkError();
        } catch (IOException e) {
            System.err.println("Failed to write " + effectsPath + ": " + e.getMessage());
            effectsWriteError = true;
        }
        if (effectsWriteError) System.err.println("Write error on " + effectsPath + " — output may be truncated");

        System.out.printf("Exported %d cards, skipped %d%n", exported, skipped);
        System.out.flush();
    }

    private static PrintWriter openWriter(String path) throws IOException {
        return new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> buildCardRecord(
            CardRules rules,
            Map<String, Set<String>> observedParams,
            Map<String, List<String>> exampleCards) {

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", rules.getName());

        ManaCost mc = rules.getManaCost();
        rec.put("mana_cost", mc.isNoCost() ? null : mc.toString());
        rec.put("cmc", mc.isNoCost() ? 0 : mc.getCMC());

        CardType type = rules.getType();
        rec.put("types", enumNames(type.getCoreTypes()));
        rec.put("subtypes", new ArrayList<>(type.getSubtypes()));
        rec.put("supertypes", enumNames(type.getSupertypes()));

        ICardFace main = rules.getMainPart();
        ICardFace other = rules.getOtherPart();

        if (main != null) {
            String power = main.getPower();
            if (power != null && !power.isEmpty()) rec.put("pt", power + "/" + main.getToughness());
            rec.put("oracle", main.getOracleText());
        }

        // Merge SVars from all faces so triggers on one face can resolve Execute$ SVars
        // declared on the other face (common in DFCs).
        Map<String, String> allSvars = new LinkedHashMap<>();
        if (main != null) {
            for (Map.Entry<String, String> e : main.getVariables()) allSvars.put(e.getKey(), e.getValue());
        }
        if (other != null) {
            for (Map.Entry<String, String> e : other.getVariables()) allSvars.put(e.getKey(), e.getValue());
        }

        List<Map<String, Object>> faces = new ArrayList<>();
        faces.add(buildFaceRecord(main, allSvars, observedParams, exampleCards));
        if (other != null) faces.add(buildFaceRecord(other, allSvars, observedParams, exampleCards));
        rec.put("faces", faces);

        return rec;
    }

    private static <E extends Enum<E>> List<String> enumNames(Collection<E> items) {
        List<String> result = new ArrayList<>();
        for (E e : items) result.add(e.name());
        return result;
    }

    private static Map<String, Object> buildFaceRecord(
            ICardFace face,
            Map<String, String> allSvars,
            Map<String, Set<String>> observedParams,
            Map<String, List<String>> exampleCards) {

        if (face == null) return Collections.emptyMap();

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", face.getName());

        ManaCost mc = face.getManaCost();
        if (!mc.isNoCost()) rec.put("mana_cost", mc.toString());
        rec.put("oracle", face.getOracleText());

        List<String> keywords = new ArrayList<>();
        for (String kw : face.getKeywords()) keywords.add(kw);
        if (!keywords.isEmpty()) rec.put("keywords", keywords);

        List<Map<String, Object>> abilities = new ArrayList<>();
        for (String raw : face.getAbilities()) {
            Map<String, Object> ab = parseAbilityEntry(raw, allSvars, face.getName(), observedParams, exampleCards);
            if (ab != null) abilities.add(ab);
        }
        for (String raw : face.getTriggers()) {
            abilities.add(parseTriggerEntry(raw, allSvars, face.getName(), observedParams, exampleCards));
        }
        for (String raw : face.getStaticAbilities()) {
            abilities.add(rawEntry("static", raw));
        }
        for (String raw : face.getReplacements()) {
            abilities.add(rawEntry("replacement", raw));
        }
        if (!abilities.isEmpty()) rec.put("abilities", abilities);

        return rec;
    }

    private static Map<String, Object> parseAbilityEntry(
            String raw, Map<String, String> allSvars, String cardName,
            Map<String, Set<String>> observedParams, Map<String, List<String>> exampleCards) {
        Map<String, String> params;
        try {
            params = AbilityFactory.getMapParams(raw);
        } catch (Exception e) {
            return null;
        }

        String kind;
        String apiTypeName;
        if (params.containsKey("AB")) {
            kind = "activated";
            apiTypeName = params.get("AB");
        } else if (params.containsKey("SP")) {
            kind = "spell";
            apiTypeName = params.get("SP");
        } else if (params.containsKey("DB")) {
            kind = "sub_ability";
            apiTypeName = params.get("DB");
        } else {
            kind = "unknown";
            apiTypeName = null;
        }

        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("kind", kind);
        ab.put("raw_dsl", raw);
        ab.put("params", params);
        resolveAndTrackApiType(ab, "api_type", "effect_class", apiTypeName, params.keySet(),
                cardName, observedParams, exampleCards);
        return ab;
    }

    private static Map<String, Object> parseTriggerEntry(
            String raw, Map<String, String> allSvars, String cardName,
            Map<String, Set<String>> observedParams, Map<String, List<String>> exampleCards) {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("kind", "trigger");
        ab.put("raw_dsl", raw);

        Map<String, String> trigParams;
        try {
            trigParams = AbilityFactory.getMapParams(raw);
        } catch (Exception e) {
            return ab;
        }
        ab.put("params", trigParams);
        if (trigParams.containsKey("Mode")) ab.put("trigger_mode", trigParams.get("Mode"));

        // Follow Execute$ → SVar → DB$ to identify the effect type
        String executeSvar = trigParams.get("Execute");
        if (executeSvar != null) {
            String svarValue = allSvars.get(executeSvar);
            if (svarValue != null) {
                try {
                    Map<String, String> svarParams = AbilityFactory.getMapParams(svarValue);
                    resolveAndTrackApiType(ab, "execute_api_type", "execute_effect_class",
                            svarParams.get("DB"), svarParams.keySet(), cardName, observedParams, exampleCards);
                } catch (Exception ignored) {}
            }
        }
        return ab;
    }

    private static void resolveAndTrackApiType(
            Map<String, Object> ab,
            String apiTypeKey, String effectClassKey,
            String apiTypeName, Set<String> paramKeys,
            String cardName,
            Map<String, Set<String>> observedParams, Map<String, List<String>> exampleCards) {
        if (apiTypeName == null) return;
        try {
            ApiType at = ApiType.smartValueOf(apiTypeName);
            ab.put(apiTypeKey, at.name());
            ab.put(effectClassKey, at.getSpellEffect().getClass().getName());
            if (observedParams.containsKey(at.name())) {
                observedParams.get(at.name()).addAll(paramKeys);
                List<String> ex = exampleCards.get(at.name());
                if (ex.size() < 3 && !ex.contains(cardName)) ex.add(cardName);
            }
        } catch (Exception ignored) {}
    }

    private static Map<String, Object> rawEntry(String kind, String raw) {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("kind", kind);
        ab.put("raw_dsl", raw);
        try {
            ab.put("params", AbilityFactory.getMapParams(raw));
        } catch (Exception ignored) {}
        return ab;
    }
}
