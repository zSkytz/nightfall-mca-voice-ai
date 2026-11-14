package com.nightfall.voice;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.nightfall.voice.ai.AiClient;
import com.nightfall.voice.audio.AudioWav;
import com.nightfall.voice.audio.MicBuffer;
import com.nightfall.voice.config.VoiceConfig;
import com.nightfall.voice.memory.MemoryStore;
import com.nightfall.voice.npc.NpcProfiles;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;

/**
 * Server-authoritative, opt-in bridge. MCA entities are located by registry ID, not reflected
 * into internal MCA implementation; original MCA and its save data stay untouched.
 */
@Mod("nightfall_voice")
public final class NightfallVoiceBridge {
    public static final Logger LOG = LogUtils.getLogger();
    private static NightfallVoiceBridge INSTANCE;
    private volatile VoicechatServerApi voiceApi;
    private volatile MinecraftServer server;
    private volatile VoiceConfig config;
    private NpcProfiles profiles;
    private MemoryStore memory;
    private AiClient ai;
    private ExecutorService workers;
    private final Map<UUID, UUID> focus = new ConcurrentHashMap<>();
    private final Set<UUID> consent = ConcurrentHashMap.newKeySet();
    private final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> busyNpcs = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AudioCapture> capture = new ConcurrentHashMap<>();
    private int tickCount;

    private static final class AudioCapture {
        private final OpusDecoder decoder;
        private final MicBuffer buffer;
        private AudioCapture(VoicechatServerApi api, VoiceConfig cfg) {
            this.decoder = api.createDecoder();
            this.buffer = new MicBuffer(cfg);
        }
        synchronized void accept(byte[] opus) {
            if (opus == null || opus.length == 0) return;
            buffer.accept(decoder.decode(opus));
        }
        synchronized byte[] poll() { return buffer.poll(); }
        synchronized void close() { buffer.clear(); decoder.close(); }
    }
    public NightfallVoiceBridge() {
        INSTANCE = this;
        NeoForge.EVENT_BUS.addListener(this::onStart);
        NeoForge.EVENT_BUS.addListener(this::onStop);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onCommand);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
    }
    public static NightfallVoiceBridge instance() { return INSTANCE; }
    public void setVoiceApi(VoicechatServerApi api) {
        this.voiceApi = api;
        LOG.info("Nightfall Voice: API Simple Voice Chat {}", api == null ? "desconectada" : "conectada");
    }
    private void onStart(ServerStartedEvent event) {
        this.server = event.getServer();
        this.workers = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "nightfall-voice-ai"); t.setDaemon(true); return t;
        });
        try {
            loadConfigs();
            Path path = server.getWorldPath(LevelResource.ROOT).resolve("data/nightfall_voice/memory.json");
            memory = MemoryStore.load(path, config.maxStoredTurnsPerNpc, config.maxBeliefsPerNpc);
            LOG.info("Nightfall Voice inicializado: {} NPCs com memoria.", memory.countNpcMemories());
            if (config.apiKey().isBlank()) LOG.warn("NIGHTFALL_AI_API_KEY ausente: conversas por IA desativadas ate definir a chave e reiniciar.");
        } catch (Exception ex) {
            LOG.error("Config de Nightfall Voice invalida. Ponte desativada; dados existentes nao foram alterados.", ex);
            config = null;
        }
    }
    private void loadConfigs() throws Exception {
        config = VoiceConfig.load(FMLPaths.CONFIGDIR.get().resolve("nightfall-voice.json"));
        profiles = NpcProfiles.load(FMLPaths.CONFIGDIR.get().resolve("nightfall-npcs.json"));
        ai = new AiClient(config);
    }
    private void onStop(ServerStoppingEvent event) {
        focus.clear(); consent.clear(); busyPlayers.clear(); busyNpcs.clear();
        capture.values().forEach(c -> { try { c.close(); } catch(Exception ignored) { } });
        capture.clear();
        if (workers != null) {
            workers.shutdownNow();
            try { workers.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        if (memory != null) try { memory.save(); } catch (Exception ex) { LOG.error("Erro ao salvar memoria",ex); }
        this.server = null;
    }
    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        stopListening(id);
        consent.remove(id);
    }
    private void stopListening(UUID player) {
        focus.remove(player);
        AudioCapture c = capture.remove(player);
        if (c != null) c.close();
    }
    /** Called by Simple Voice Chat's packet callback thread; never accesses Minecraft world. */
    public void receiveVoice(UUID user, byte[] opus) {
        VoiceConfig cfg=config;
        VoicechatServerApi api=voiceApi;
        if (server==null || cfg==null || api==null || !cfg.enabled || cfg.apiKey().isBlank()) return;
        if (!consent.contains(user) || !focus.containsKey(user) || busyPlayers.contains(user)) return;
        if (opus == null || opus.length == 0) return;
        try {
            capture.computeIfAbsent(user, k -> new AudioCapture(api,cfg)).accept(opus);
        } catch (Exception ex) {
            LOG.warn("Opus nao pode ser decodificado para {}: {}", user, ex.toString());
        }
    }
    private void onTick(ServerTickEvent.Post event) {
        if (++tickCount % 8 != 0 || server == null || config == null) return;
        for (Map.Entry<UUID, AudioCapture> e : capture.entrySet()) {
            UUID user = e.getKey();
            if (!consent.contains(user) || !focus.containsKey(user)) continue;
            ServerPlayer source = server.getPlayerList().getPlayer(user);
            if (source == null || nearFocusedNpc(source) == null) {
                stopListening(user); // No voice processing when leaving conversation range.
                continue;
            }
            byte[] wav = e.getValue().poll();
            if (wav != null) process(user,wav,null);
        }
    }
    private static boolean isMcaVillager(Entity e) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return e instanceof LivingEntity && "mca".equals(key.getNamespace())
                && ("male_villager".equals(key.getPath()) || "female_villager".equals(key.getPath()));
    }
    private Entity nearFocusedNpc(ServerPlayer p) {
        UUID wanted = focus.get(p.getUUID());
        if (wanted == null) return null;
        Entity e = p.serverLevel().getEntity(wanted);
        if (e == null || !e.isAlive() || !isMcaVillager(e)) return null;
        if (p.distanceToSqr(e) > config.maxInteractionDistance * config.maxInteractionDistance) return null;
        if (!p.hasLineOfSight(e)) return null;
        return e;
    }
    private Entity findClosest(ServerPlayer p) {
        double range = config.maxInteractionDistance;
        return p.serverLevel().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(range),
                e -> isMcaVillager(e) && e.isAlive() && p.distanceToSqr(e)<=range*range && p.hasLineOfSight(e))
                .stream().min(java.util.Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
    }
    /** Main thread. Text override enables testing STT-free with /nfvoice say. */
    private void process(UUID playerId, byte[] wav, String typed) {
        if (config==null || ai==null || memory==null || server==null) return;
        ServerPlayer player=server.getPlayerList().getPlayer(playerId);
        if (player==null || !consent.contains(playerId)) return;
        Entity npc=nearFocusedNpc(player);
        if (npc==null) { tell(player,"Morador fora de alcance. Use /nfvoice focus novamente."); stopListening(playerId); return; }
        if (!busyPlayers.add(playerId)) return;
        if (!busyNpcs.add(npc.getUUID())) { busyPlayers.remove(playerId); tell(player,"Este morador está conversando com outra pessoa."); return; }
        UUID npcId=npc.getUUID();
        String npcName=npc.getName().getString();
        String playerName=player.getGameProfile().getName();
        NpcProfiles.Profile persona=profiles.forEntity(npc);
        MemoryStore.Snapshot snapshot=memory.snapshot(npcId);
        MinecraftServer currentServer=server;
        try {
            workers.submit(() -> {
                try {
                    String userText=(typed != null ? typed : ai.transcribe(wav)).strip();
                    if (userText.length()>450) userText=userText.substring(0,450);
                    if (userText.length()<2) { onMain(currentServer, () -> tellCurrent(playerId,"Não consegui entender a fala.")); return; }
                    final String utterance = userText;
                    AiClient.Reply result=ai.chat(npcName,npcId,playerName,playerId,persona,utterance,snapshot);
                    byte[] tts = null;
                    if (config.enableTts && voiceApi!=null) {
                        try { tts=ai.speakPcm24k(result.text(),persona.voice == null || persona.voice.isBlank() ? config.defaultVoice : persona.voice); }
                        catch(Exception ttsError) { LOG.warn("TTS indisponivel para {}: {}",npcName,ttsError.toString()); }
                    }
                    final byte[] sound=tts;
                    onMain(currentServer, () -> deliver(playerId,npcId,npcName,utterance,result,sound));
                } catch (Exception ex) {
                    LOG.warn("Fluxo de voz IA falhou para {}: {}",playerId,ex.toString());
                    onMain(currentServer, () -> tellCurrent(playerId,"Não foi possível obter resposta da IA. Verifique a chave, conexão e logs do servidor."));
                } finally {
                    onMain(currentServer, () -> { busyPlayers.remove(playerId); busyNpcs.remove(npcId); });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            busyPlayers.remove(playerId); busyNpcs.remove(npcId);
        }
    }
    private void onMain(MinecraftServer expected, Runnable code) {
        if (expected != null && expected == server) expected.execute(() -> { if (expected == server) code.run(); });
    }
    private void deliver(UUID playerId, UUID npcId, String npcName, String words, AiClient.Reply response, byte[] pcm24) {
        ServerPlayer p=server.getPlayerList().getPlayer(playerId);
        Entity npc=p==null?null:p.serverLevel().getEntity(npcId);
        // Don't deliver to moved-away players or NPCs. Record only acknowledged interactions.
        if (p==null || npc==null || !npc.isAlive() || !consent.contains(playerId) || !npcId.equals(focus.get(playerId))) return;
        if (p.distanceToSqr(npc) > config.maxInteractionDistance * config.maxInteractionDistance) return;
        try { memory.addConversation(npcId,playerId,words,response.text(),response.beliefs()); }
        catch(Exception ex) { LOG.error("Falha ao gravar memoria de {}",npcName,ex); }
        if (config.showSubtitles) tell(p,"§6"+npcName+"§f: "+response.text());
        if (pcm24!=null && pcm24.length>0) playAtNpc(npc,pcm24);
    }
    private void playAtNpc(Entity npc,byte[] pcm24) {
        VoicechatServerApi api=voiceApi;
        if (api==null) return;
        OpusEncoder encoder=null;
        try {
            EntityAudioChannel channel=api.createEntityAudioChannel(UUID.randomUUID(),api.fromEntity(npc));
            if (channel==null) return;
            channel.setDistance(config.replyHearingDistance);
            encoder=api.createEncoder();
            short[] pcm48=AudioWav.pcm24to48(pcm24);
            AudioPlayer player=api.createAudioPlayer(channel,encoder,pcm48);
            OpusEncoder toClose=encoder;
            player.setOnStopped(() -> {
                try { channel.flush(); } catch(Exception ignored) { }
                try { toClose.close(); } catch(Exception ignored) { }
            });
            player.startPlaying();
        } catch(Exception ex) {
            if (encoder!=null) try { encoder.close(); } catch(Exception ignored) { }
            LOG.warn("Audio espacial nao pode ser reproduzido: {}",ex.toString());
        }
    }
    private void tell(ServerPlayer p,String text) { p.sendSystemMessage(Component.literal("§7[Nightfall Voice] §f"+text)); }
    private void tellCurrent(UUID id,String text) { if(server!=null) { ServerPlayer p=server.getPlayerList().getPlayer(id); if(p!=null)tell(p,text); } }

    private void onCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("nfvoice")
            .then(literal("consent")
                .then(literal("on").executes(ctx -> {
                    ServerPlayer p=ctx.getSource().getPlayerOrException();
                    consent.add(p.getUUID());
                    tell(p,"Permissão de processamento da sua voz ativada para esta sessão. Use /nfvoice focus perto de um morador."); return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    ServerPlayer p=ctx.getSource().getPlayerOrException();
                    consent.remove(p.getUUID()); stopListening(p.getUUID());
                    tell(p,"Microfone desconectado da IA. Nenhuma nova fala será processada."); return 1;
                })))
            .then(literal("focus").executes(ctx -> {
                ServerPlayer p=ctx.getSource().getPlayerOrException();
                if (!consent.contains(p.getUUID())) { tell(p,"Primeiro use /nfvoice consent on."); return 0; }
                if (config==null || !config.enabled || config.apiKey().isBlank()) { tell(p,"IA indisponível. Confira config e NIGHTFALL_AI_API_KEY."); return 0; }
                Entity closest=findClosest(p);
                if (closest==null) { tell(p,"Nenhum morador do MCA visível e próximo."); return 0; }
                stopListening(p.getUUID());
                focus.put(p.getUUID(),closest.getUUID());
                tell(p,"Você agora conversa com §e"+closest.getName().getString()+"§f. Fale pelo Simple Voice Chat."); return 1;
            }))
            .then(literal("stop").executes(ctx -> {
                ServerPlayer p=ctx.getSource().getPlayerOrException();
                stopListening(p.getUUID()); tell(p,"Conversa encerrada."); return 1;
            }))
            .then(literal("say").then(argument("texto",StringArgumentType.greedyString()).executes(ctx -> {
                ServerPlayer p=ctx.getSource().getPlayerOrException();
                if (!consent.contains(p.getUUID())) { tell(p,"Use /nfvoice consent on antes."); return 0; }
                String input=StringArgumentType.getString(ctx,"texto");
                process(p.getUUID(),null,input); return 1;
            })))
            .then(literal("status").executes(ctx -> {
                ServerPlayer p=ctx.getSource().getPlayerOrException();
                tell(p,"Consentimento: "+consent.contains(p.getUUID())+"; foco: "+focus.get(p.getUUID())+
                        "; Simple Voice Chat: "+(voiceApi!=null)+"; IA configurada: "+(config!=null && !config.apiKey().isBlank())); return 1;
            }))
            .then(literal("reload").requires(s -> s.hasPermission(2)).executes(ctx -> {
                try { loadConfigs(); ctx.getSource().sendSuccess(() -> Component.literal("Nightfall Voice: perfis/config recarregados."),true); return 1; }
                catch(Exception ex) { LOG.error("Falha no reload",ex); return 0; }
            }))
            .then(literal("forget").requires(s -> s.hasPermission(2))
                .then(argument("npc_uuid",StringArgumentType.word()).executes(ctx -> {
                    try {
                        UUID npcId=UUID.fromString(StringArgumentType.getString(ctx,"npc_uuid"));
                        if (memory==null) return 0;
                        memory.clearNpc(npcId);
                        ctx.getSource().sendSuccess(() -> Component.literal("Memória ativa e arquivos históricos do NPC apagados."),true);
                        return 1;
                    } catch(Exception ex) { LOG.warn("UUID invalido ao esquecer memoria",ex); return 0; }
                })))
        );
    }
}
