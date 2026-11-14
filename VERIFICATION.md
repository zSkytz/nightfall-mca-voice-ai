# Verification report — 13 Nov 2026

## Completed in this runtime

- Java 21 available; source files created.
- `AudioWavSelfTest` compiled and passed (`javac --release 21`): 48 kHz WAV container, 24k->48k conversion, RMS.
- `MicBufferSelfTest` compiled with an intentionally small temporary VoiceConfig stub and passed: speech segmentation on synthetic PCM and WAV export.
- Example JSON files successfully parsed by Python `json`.
- `neoforge.mods.toml` parsed successfully by Python `tomllib`.
- Project code contains VoicechatPlugin service registration, mod entrypoint, STT/LLM/TTS client, NPC registry adapter, world-persistent memory.
- Current dependencies / ModDevGradle conventions checked against public documentation.

## Not completed / no guarantee

- No Minecraft / NeoForge / MCA / Simple Voice Chat runtime tests.
- Complete `gradle build` not executed because the runtime lacks Gradle and cannot download required external dependencies.
- Consequently, no compiled, runnable JAR is supplied. Build and resolve any API signature issues before putting the mod on the real Nightfall world.
- No live OpenAI API invocation; no secrets/credentials included.

## First integration tests for the maintainer

1. Build using GitHub Actions or Gradle and resolve any compiler errors.
2. Backup and boot a **temporary NeoForge 1.21.1 world** with exact dependencies.
3. Check logs for `Nightfall Voice inicializado` and `API Simple Voice Chat conectada`.
4. `/nfvoice consent on` then `/nfvoice focus` in front of a MCA villager.
5. Try `/nfvoice say Boa tarde` to verify LLM / JSON / text subtitles.
6. Test PTT audio capture, transcription, then proximity TTS.
7. Reboot, verify same villager recalls prior dialogue; rename villager and verify UUID memory unaffected.
8. Test two users, simultaneous NPCs, departure/disconnect and `/nfvoice consent off`.
