package com.nightfall.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

/** NeoForge voice chat plugin registered by its official @ForgeVoicechatPlugin annotation. */
@ForgeVoicechatPlugin
public final class NightfallVoiceChatPlugin implements VoicechatPlugin {
    @Override public String getPluginId() { return "nightfall_mca_voice_ai"; }

    @Override public void initialize(VoicechatApi api) { /* Server API is assigned at server-start event. */ }

    @Override public void registerEvents(EventRegistration events) {
        events.registerEvent(VoicechatServerStartedEvent.class, event -> {
            NightfallVoiceBridge bridge = NightfallVoiceBridge.instance();
            if (bridge != null) bridge.setVoiceApi(event.getVoicechat());
        });
        events.registerEvent(VoicechatServerStoppedEvent.class, event -> {
            NightfallVoiceBridge bridge = NightfallVoiceBridge.instance();
            if (bridge != null) bridge.setVoiceApi(null);
        });
        events.registerEvent(MicrophonePacketEvent.class, event -> {
            NightfallVoiceBridge bridge = NightfallVoiceBridge.instance();
            if (bridge != null && event.getSenderConnection() != null && event.getPacket() != null) {
                try {
                    bridge.receiveVoice(event.getSenderConnection().getPlayer().getUuid(),
                            event.getPacket().getOpusEncodedData());
                } catch (Exception ex) {
                    NightfallVoiceBridge.LOG.warn("Nao foi possivel capturar pacote de voz do MCA: {}", ex.toString());
                }
            }
        });
    }
}
