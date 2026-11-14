package com.nightfall.voice.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.world.entity.Entity;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Author-authored background. Lookup uses persistent UUID before display name. */
public final class NpcProfiles {
    public static final class Profile {
        public String voice = "alloy";
        public String role = "Morador local";
        public String personality = "Pessoa comum, com seus interesses e conhecimento limitado.";
        public String speechStyle = "Português natural, frases concisas, sem linguagem de videogame.";
        public String secrets = "";
        public Profile() { }
        public Profile(String voice, String role, String personality, String speechStyle) {
            this.voice=voice; this.role=role; this.personality=personality; this.speechStyle=speechStyle;
        }
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, Profile>>() {}.getType();
    private final Map<String, Profile> profiles;

    private NpcProfiles(Map<String, Profile> profiles) { this.profiles = profiles; }

    public static NpcProfiles load(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            Map<String, Profile> defaults = new LinkedHashMap<>();
            defaults.put("Tom Collins", new Profile("onyx", "Morador de Freedom County", "Reservado, observa antes de confiar; possui rotina e interesses próprios.", "Voz contida, pausas naturais, fala sem excesso de exposição."));
            defaults.put("Adam Collins", new Profile("echo", "Atendente de 19 anos", "Quer sair de Freedom County; tem opiniões próprias e não conhece segredos dos outros sem tê-los ouvido.", "Jovem, inquieto, espontâneo, às vezes irônico."));
            defaults.put("Beatrice Wallace", new Profile("nova", "Moradora de Freedom County", "Pessoas comuns têm recordações incompletas e não são oniscientes.", "Calma e precisa."));
            defaults.put("Roger Dale", new Profile("fable", "Morador de Freedom County", "Reage de forma realista à confiança e às circunstâncias.", "Direta, com vocabulário cotidiano."));
            defaults.put("Dr. Samuel Reid", new Profile("alloy", "Médico local", "Evita divulgar informações confidenciais sem motivo legítimo.", "Medido, profissional e humano."));
            Files.writeString(path, GSON.toJson(defaults), StandardCharsets.UTF_8);
            return new NpcProfiles(defaults);
        }
        Map<String, Profile> p = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), TYPE);
        return new NpcProfiles(p == null ? Map.of() : p);
    }

    public Profile forEntity(Entity entity) {
        String id = entity.getUUID().toString();
        Profile byId = profiles.get(id);
        if (byId != null) return byId;
        Profile byName = profiles.get(entity.getName().getString());
        return byName != null ? byName : new Profile();
    }
}
