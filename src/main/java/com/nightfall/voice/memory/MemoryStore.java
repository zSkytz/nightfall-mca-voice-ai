package com.nightfall.voice.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Strict separation: speech history != verified facts. AI-extracted notes are BELIEFS only. */
public final class MemoryStore {
    public static final class Turn {
        public String at;
        public String playerId;
        public String speaker;
        public String text;
        public Turn() { }
        public Turn(String at, String playerId, String speaker, String text) {
            this.at = at; this.playerId=playerId; this.speaker=speaker; this.text=text;
        }
    }
    public static final class Belief {
        public String at;
        public String sourcePlayer;
        public String claim;
        public String status = "unverified";
        public Belief() { }
        public Belief(String at, String sourcePlayer, String claim) {
            this.at=at; this.sourcePlayer=sourcePlayer; this.claim=claim;
        }
    }
    public static final class NpcMemory {
        public String npcId;
        public List<Turn> turns = new ArrayList<>();
        public List<Belief> beliefs = new ArrayList<>();
        public NpcMemory() { }
        NpcMemory(String id) { this.npcId=id; }
    }
    public static final class Snapshot {
        public final List<Turn> turns;
        public final List<Belief> beliefs;
        Snapshot(List<Turn> turns, List<Belief> beliefs) { this.turns=turns; this.beliefs=beliefs; }
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, NpcMemory>>() {}.getType();
    private final Path file;
    private final int maxTurns;
    private final int maxBeliefs;
    private final Map<String, NpcMemory> npcs;

    private MemoryStore(Path file, int maxTurns, int maxBeliefs, Map<String, NpcMemory> npcs) {
        this.file=file; this.maxTurns=maxTurns; this.maxBeliefs=maxBeliefs; this.npcs=npcs;
    }
    public static MemoryStore load(Path file, int maxTurns, int maxBeliefs) throws IOException {
        Files.createDirectories(file.getParent());
        Map<String,NpcMemory> data = new HashMap<>();
        if (Files.exists(file)) {
            Map<String,NpcMemory> parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), TYPE);
            if (parsed != null) data.putAll(parsed);
        }
        return new MemoryStore(file, Math.max(10,maxTurns), Math.max(1,maxBeliefs), data);
    }
    public synchronized Snapshot snapshot(UUID npc) {
        NpcMemory data = npcs.get(npc.toString());
        if (data == null) return new Snapshot(List.of(), List.of());
        List<Turn> ts = data.turns == null ? List.of() : data.turns;
        List<Belief> bs = data.beliefs == null ? List.of() : data.beliefs;
        return new Snapshot(List.copyOf(ts), List.copyOf(bs));
    }
    public synchronized void addConversation(UUID npc, UUID player, String text, String reply, List<String> beliefClaims) throws IOException {
        String key = npc.toString();
        NpcMemory memo = npcs.computeIfAbsent(key, NpcMemory::new);
        if (memo.turns == null) memo.turns = new ArrayList<>();
        if (memo.beliefs == null) memo.beliefs = new ArrayList<>();
        String when = Instant.now().toString();
        memo.turns.add(new Turn(when, player.toString(), "player", text));
        memo.turns.add(new Turn(when, player.toString(), "npc", reply));
        // Prevent unbounded files. Older entries are archived, never silently elevated into facts.
        if (memo.turns.size() > maxTurns) {
            List<Turn> removed = new ArrayList<>(memo.turns.subList(0, memo.turns.size() - maxTurns));
            archive(npc, removed);
            memo.turns.subList(0, memo.turns.size() - maxTurns).clear();
        }
        if (beliefClaims != null) for (String claim : beliefClaims) {
            if (claim == null) continue;
            claim = claim.replaceAll("[\\r\\n]+", " ").trim();
            if (claim.isBlank() || claim.length() > 300) continue;
            String finalClaim = claim;
            boolean duplicate = memo.beliefs.stream().anyMatch(b -> b.claim != null && b.claim.equalsIgnoreCase(finalClaim));
            if (!duplicate) memo.beliefs.add(new Belief(when, player.toString(), claim));
        }
        while (memo.beliefs.size() > maxBeliefs) memo.beliefs.remove(0);
        save();
    }
    private void archive(UUID npc, List<Turn> older) throws IOException {
        Path dir = file.getParent().resolve("archive");
        Files.createDirectories(dir);
        Path log = dir.resolve(npc + ".jsonl");
        StringBuilder b = new StringBuilder();
        for (Turn t : older) b.append(GSON.toJson(t)).append('\n');
        Files.writeString(log, b.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
    public synchronized void clearNpc(UUID npc) throws IOException {
        npcs.remove(npc.toString());
        Files.deleteIfExists(file.getParent().resolve("archive").resolve(npc + ".jsonl"));
        save();
    }
    public synchronized int countNpcMemories() { return npcs.size(); }
    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(temp, GSON.toJson(npcs), StandardCharsets.UTF_8);
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
