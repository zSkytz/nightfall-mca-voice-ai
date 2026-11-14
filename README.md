# NIGHTFALL — MCA Voice AI Bridge 0.2.0-alpha

**Projeto-fonte experimental — não é JAR pronto nem foi compilado num ambiente Minecraft.**

Alvo: **Minecraft Java 1.21.1 + NeoForge 21.1.235 + MCA Reborn (NeoForge) 7.7.x + Simple Voice Chat NeoForge 2.6.20+ + Java 21**.

Este mod é um **add-on separado** que não substitui MCA Reborn nem reescreve seu sistema de relacionamentos. Ao se aproximar de um homem ou mulher do MCA e ativar o foco, o jogador fala pelo Simple Voice Chat: o servidor coleta uma frase, decodifica Opus em PCM, envia WAV à transcrição de voz, consulta a IA com personalidade/memória do morador, guarda a conversa e reproduz TTS espacial posicionado no NPC. Opcionalmente mostra legenda. Nenhuma mudança automática de reputação do MCA é feita.

## Funções implementadas no código

- Descoberta de `mca:male_villager` e `mca:female_villager` pelo registro do Minecraft (sem dependência de classes internas não documentadas).
- Plugin da API do Simple Voice Chat usando `META-INF/services/de.maxhenkel.voicechat.api.VoicechatPlugin`.
- Autorização explícita por jogador e **foco** obrigatório no morador mais próximo visível.
- Captura Opus, decodificação para WAV PCM mono 48 kHz, segmentação por volume RMS e silêncio.
- Transcrição STT via API HTTP de áudio; chat por LLM via Chat Completions; TTS PCM 24 kHz convertido para 48 kHz e enviado em canal de entidade por proximidade.
- Memória persistente **por UUID de NPC**, de múltiplos jogadores, salva no diretório do mundo e carregada a cada início de servidor.
- Conversas anteriores separadas de **crenças atribuídas a jogadores, não verificadas**; nenhuma delas é convertida em fato global.
- Arquivo histórico JSONL para conversas mais antigas e escrita atômica do arquivo ativo de memória.
- Perfis autorais de NPCs configuráveis por nome exato ou UUID; inclui exemplos para personagens de Freedom County.
- Tratamento assíncrono de requisições; resposta não bloqueia ticks do Minecraft; limitação de concorrência por NPC/jogador.
- Modo de teste por texto sem microfone e recarga de arquivos de configuração.

## Limites importantes

1. **Ainda não foi validado contra o MCA real e o Simple Voice Chat no cliente e servidor**, pois esta sessão não tem acesso às dependências externas de compilação. Pode exigir ajustes pequenos de API/mapeamentos no primeiro build.
2. Não sincroniza corações, amizade, crimes ou profissões do MCA. Mantém uma memória paralela no Nightfall (não altera os dados do MCA).
3. IA não executa comandos nem altera estado do mundo. NPCs **podem inventar** coisas; a memória marca relatos como não verificados.
4. O reconhecimento por voz não é local com essa configuração inicial. Áudio, transcrições e prompts são enviados para provedores externos. **APIs podem cobrar** por consumo; não existe chave gratuita embutida.
5. O microfone não é gravado em disco como arquivo de áudio por este mod, mas a transcrição e respostas ficam armazenadas no mundo. Sempre informe os participantes sobre o processamento de voz.
6. Outros NPCs (Easy NPC, MCA humano/monstro adicional) não entram no escopo desse primeiro build.
7. Respostas são locucionadas com a voz TTS selecionada por personagem, **sem clonar voz de pessoas reais**.
8. O Simple Voice Chat precisa estar conectado no servidor e nos clientes (abra porta UDP 24454 quando necessário).

## 1. Pré-requisitos

- Java **JDK 21**, Gradle 8.10.2, Minecraft Java 1.21.1 / NeoForge 21.1.235.
- MCA Reborn **NeoForge 1.21.1** instalado normalmente (não use versão Fabric ou Forge).
- Simple Voice Chat **NeoForge 1.21.1** versão 2.6.20 ou compatível.
- Chave de API dos serviços que configurou, acessível **somente no servidor**.

## 2. Compilar no Windows (com Gradle instalado)

Extraia o ZIP. Abra um PowerShell **dentro** da pasta do projeto e rode:

```powershell
java -version
# (Confirme Java 21)
gradle --version
gradle --no-daemon build
```

Se compilar, o artefato ficará em `build/libs/nightfall-mca-voice-ai-0.2.0-alpha.jar`. Coloque-o na pasta `mods` do servidor. Como o mod declara suporte dos dois lados, coloque também a mesma versão no `mods` de cada cliente. **Este ZIP de origem não deve ser colocado em mods**.

**Sem Gradle local:** há um fluxo `.github/workflows/build.yml` incluído. Ao enviar os arquivos para um repositório GitHub e abrir a aba Actions → `Build Nightfall MCA Voice AI`, ele tentará gerar o JAR como artefato. Isso também funcionará como verificação real de compilação.

> Este pacote não traz `gradlew` porque não pude incluir a distribuição do Gradle e o `gradle-wrapper.jar` sem acesso externo. O comando é `gradle build` após instalar Gradle.

## 3. Configuração segura no servidor

Na primeira inicialização, o mod gera:

- `config/nightfall-voice.json` (IA, voz, distâncias, cenário, etc.)
- `config/nightfall-npcs.json` (personalidade, voz, jeito de falar e cargo)
- `<mundo>/data/nightfall_voice/memory.json` (memória por UUID do NPC)
- `<mundo>/data/nightfall_voice/archive/<uuid>.jsonl` (turnos antigos)

Configure a chave como **variável de ambiente do processo do servidor** (NUNCA em arquivos distribuídos a jogadores):

PowerShell, antes de iniciar o servidor na mesma sessão:

```powershell
$env:NIGHTFALL_AI_API_KEY = "SUA_CHAVE_PRIVADA_AQUI"
# Então execute o seu script normal de inicialização do servidor.
```

Alternativamente aceita `OPENAI_API_KEY`. Evite registrar chaves em prints/logs. O servidor exige URLs HTTPS para serviços externos; HTTP é liberado só para `localhost`.

O padrão é OpenAI-compatível (Chat Completions, transcrição multipart e TTS PCM). Outros serviços podem exigir adaptação de formato. Para economizar, defina `"enableTts": false` e `"showSubtitles": true` — você ainda recebe resposta escrita. Com `enableTts: true`, o áudio sintético é espacial e ouvido por quem estiver perto.

## 4. Jogar

Cada jogador faz uma vez por sessão:

```text
/nfvoice consent on
```

Aproxime-se (até 7 blocos, com linha de visão) do NPC e selecione:

```text
/nfvoice focus
```

Agora use sua tecla de transmissão do **Simple Voice Chat** para conversar. Depois de uma pequena pausa na fala, o servidor processa e o NPC responde. Para experimentar sem microfone:

```text
/nfvoice say Boa tarde, Adam. Você viu alguma coisa estranha na estrada ontem?
```

Para parar:

```text
/nfvoice stop
```

Para revogar autorização (encerra foco imediatamente):

```text
/nfvoice consent off
```

Para diagnosticar:

```text
/nfvoice status
```

Comandos OP nível 2:

```text
/nfvoice reload
/nfvoice forget <UUID_DO_NPC>
```

`forget` apaga as entradas ativas e o arquivo de histórico daquele NPC; faça backup antes de usar. Nenhum comando remove os dados do MCA original.

## 5. Identidade e memória de personagens

`config/nightfall-npcs.json` aceita chaves com nome ou UUID. O UUID tem prioridade e **não muda se você renomear o NPC** (desde que o MCA mantenha a entidade). Para perfis estáveis, prefira UUID quando tiver a identificação de cada entidade.

Exemplo de perfil:

```json
"Adam Collins": {
  "voice": "echo",
  "role": "Atendente de 19 anos; filho de Tom Collins",
  "personality": "Quer sair de Freedom County. Guarda suas impressões sobre clientes habituais.",
  "speechStyle": "Jovem, informal, humor seco; respostas breves.",
  "secrets": ""
}
```

O código usa a informação explícita do perfil. Ele não inventa posições do NPC nem constrói rotina artificial. **A IA pode se contradizer** e o armazenamento de lembranças não garante veracidade. Fatos do Nightfall Core Engine ainda exigem uma ponte futura, separada desse addon.

### Exemplo em jogo

**William:** “Adam, um caminhoneiro disse que viu alguém desaparecer na rodovia.”

**Adam:** “Desaparecer como? Tem gente que inventa cada história quando o posto já fechou… Mas espera. Foi na estrada velha?”

A memória salva a conversa como fala de William, resposta de Adam e, se a IA indicar relevância, a alegação como crença **não verificada**, vinculada à origem. Roger Dale não aprende o mesmo relato automaticamente.

## 6. Depuração e proteção do mundo

1. Faça backup completo do mundo e copie as configs para testar em mundo de desenvolvimento.
2. Inicie o servidor com MCA e Simple Voice Chat; confira a inicialização do `nightfall_voice` nos logs.
3. Use `/nfvoice status` e `/nfvoice say` primeiro. Se o modo texto não funciona, não tente corrigir o microfone ainda.
4. Se `Simple Voice Chat: false`, cheque versão e configuração do plugin. Se `IA configurada: false`, confira a variável de ambiente visível para o processo Java.
5. Se a legenda aparece mas não há voz, cheque TTS, saída de áudio, categoria do SVC e logs do servidor.
6. Reinicie o servidor após configurar a variável de ambiente; `/nfvoice reload` atualiza perfis e JSON, não muda variáveis de ambiente de um processo já iniciado.
7. Nenhuma gravação de PCM é persistida; o JSON e arquivo histórico são dados pessoais dos jogadores e devem ser armazenados com as permissões apropriadas.

## Licenças e referências

- Código específico deste addon: licença MIT, disponível em `LICENSE`.
- API do Simple Voice Chat: https://voicechat.modrepo.de/
- MCA Reborn: https://github.com/Luke100000/minecraft-comes-alive
- NeoForge 1.21.1: https://docs.neoforged.net/docs/1.21.1/gettingstarted/

Nada deste projeto distribui código ou ativos do MCA, SVC, Minecraft ou modelos de IA.
