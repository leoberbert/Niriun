# Protocolo observado no Iriun Webcam para Android (2.9.7, versionCode 215)

Fonte: análise estática de `com.jacksoftw.webcam` (xapk da APKPure) — `classes.dex` (jadx 1.5.7) e
`lib/arm64-v8a/libnative-lib.so` (Ghidra 12.1.4, descompilação + desmontagem). Nada foi executado; o que
não foi confirmado no código está marcado como **[inferido]**. Endereços são da `libnative-lib.so` arm64.

O Niriun implementa este protocolo do zero (nenhum código ou asset do Iriun foi copiado).

## 1. Arquitetura geral

```
PC (cliente)  ── descobre ──►  celular (servidor)
              ── TCP 4699 ──►  mensagens enquadradas (vídeo H.264/HEVC, áudio Opus, controles)
```

O **celular é o servidor TCP**; o PC descobre o celular e conecta. O pipeline do celular é:

```
Camera2 ─► SurfaceTexture (OES) ─► GL ES (rotação/espelho/fit) ─► Surface do MediaCodec (H.264 ou HEVC)
                                                                   └► nativeSend() ─► pacote tipo 8 ─► TCP
AudioRecord/Oboe (48 kHz mono) ─► Opus (20 ms) ─► pacote tipo 7 ─► TCP
PC ─► pacote tipo 9 (ACK por quadro) ─► controle de fluxo (descarta quadros se o PC atrasar)
```

## 2. Descoberta (3 mecanismos, todos presentes no app)

| Mecanismo | Detalhe | Evidência |
|---|---|---|
| NSD / mDNS | `NsdManager.registerService`, tipo `_iriunwebcam._tcp.`, porta **4699**, nome = 32 hex (16 bytes do UUID persistido, hex dos bytes na ordem LE) | `LocalService.onCreate` (Java) |
| Beacon UDP multicast | datagrama de **280 bytes** (o mesmo "hello", §4.1) enviado a cada ~2 s (20 × 100 ms) em **cada interface** ativa para `239.255.188.58:4698` (IPv4) e `ff02::2:d644` porta `4698` (IPv6). Só enquanto **não** há PC conectado (o objeto é destruído em `onConnect` `FUN_0012c580` e recriado em `onDisconnect` `FUN_0012ca94`) | `FUN_0012ac54` (loop), `FUN_0012ba84` (socket: SO_REUSEADDR/SO_REUSEPORT, bind `0.0.0.0:4698`, `IP_ADD_MEMBERSHIP`) |
| Sonda TCP | segundo servidor TCP em **4697** (`0x1259`, `FUN_001291d0`) que, ao aceitar, envia o hello de 280 bytes (`FUN_0012944c`) e fecha após ~1 s ociosa | `FUN_001291d0`, `FUN_0012944c` |

## 3. Transporte TCP (porta 4699)

* Servidor em `[::]:4699` (AF_INET6 dual-stack), `SO_REUSEADDR`, `listen(backlog=1)`, `accept4` com `poll(100 ms)`
  (`FUN_0012e9e8`). Uma conexão por vez.
* No socket aceito: `TCP_NODELAY`, não bloqueante, `SO_SNDBUF >= 0x7D000` (512000).
* **Timeout ocioso de 10 s** sem receber nenhuma mensagem do PC → o celular derruba (`FUN_0012f2d4`, timeout em
  `+0xA8`, definido em `FUN_0012d1cc` / `FUN_0012d110`). O PC mantém a conexão viva com os ACKs/mensagens.
* Escrita: `send(MSG_NOSIGNAL)` com `poll(POLLOUT, 4000 ms)`; se não drenar em 4 s ou der erro, a conexão é derrubada
  (`FUN_0012f0a8`).
* Buffer de recepção de 4 000 000 bytes; pacote maior → conexão derrubada.

### 3.1 Enquadramento (todas as mensagens, nos dois sentidos)

```
u32 LE header:  bits 0..23 = comprimento TOTAL do pacote (inclui o próprio header)
                bits 24..31 = tipo da mensagem
corpo ... (little-endian)
```

Prova: `*hdr = len & 0xFFFFFF | type << 24` (`FUN_00128800`, `FUN_0012d798`); leitura em `FUN_0012f2d4`
(`(*buf & 0xFFFFFF)` = comprimento, `buf[3]` = tipo).

## 4. Mensagens

### 4.1 Celular → PC

| Tipo | Nome | Layout (offsets em bytes, LE) |
|---|---|---|
| `0x01` | **Hello** (280 B) | `0` hdr `0x01000118`; `4` UUID (16 B = `LE64(least) ‖ LE64(most)`); `20` u16 = **2** (tipo de dispositivo: Android); `22` u16 = **2** (versão do protocolo); `24` nome do dispositivo, 256 B, NUL-preenchido |
| `0x0A` | **Tamanhos** | `4` u32 flags (bit0 = há microfone; bit16..23 = **o celular aceita HEVC**; o app Android sempre envia 0, ver `docs/WINDOWS.md` §4); `8` u32 `n`; `12` `n` × { i32 largura, i32 altura, **f32** fps } (12 B cada). Total = `12 + 12n`. O primeiro item é 640×480@30 (padrão do PC) |
| `0x18` | **Lista de câmeras** | `4` u32 `n`; `8` u32 índice selecionado; `12` `n` × 256 B (nome NUL-preenchido). Total = `12 + 256n` |
| `0x17` | **Desafio de senha** | hdr `0x17000010` (16 B); `4` u32 0; `8` u64 nonce aleatório. Só enviado se há senha configurada |
| `0x08` | **Vídeo** | `0` hdr (len = 8 + N); `4` **f32** timestamp em segundos desde o início do stream; `8` N bytes de saída crua do `MediaCodec` (inclui o buffer `CODEC_CONFIG` SPS/PPS/VPS, Annex-B). N ≤ 4 999 992 |
| `0x07` | **Áudio** | `0` hdr (len = 8 + N); `4` u32 0; `8` pacote Opus cru (48 kHz, mono, 20 ms = 960 amostras, `OPUS_APPLICATION_AUDIO`, máx. 4992 B) |
| `0x13` `0x14` `0x15` `0x16` | Info de ISO / tempo de exposição / compensação EV / balanço de branco | 20 B: hdr + 4 × u32 (mín, máx, atual/passo, flag auto). **Só enviados na versão "pro"** (checagem `DAT_001aa890 == 0x823` em `nativeIsoInfo` & cia.). Semântica exata dos campos **[inferido]** |
| `0x19` | Info de foco manual | 12 B: hdr + u32 + u32 **[inferido]** |
| `0x10` | (celular→PC) ganho | 12 B: hdr + u32 + u32 (`FUN_0012d9a8`) **[inferido]** |

O celular envia, **logo após `accept`** (`onConnect`, `FUN_0012c580`): Desafio de senha `0x17` (só se há senha) → Hello → Tamanhos → Lista
de câmeras. Quando câmera/tamanhos mudam em tempo de execução (`nativeSetSizes`/`nativeSetCameraList`), reenvia `0x0A`/`0x18`.

### 4.2 PC → celular (despachante `FUN_0012cb48`)

| Tipo | Nome | Layout / efeito |
|---|---|---|
| `0x01` | no-op | ignorado |
| `0x04` | sincronização | redefine a origem do relógio (`+0x138 = agora`); sem outro efeito no celular. Zerar o relógio com o stream ativo faz os timestamps dos quadros recuarem; o Niriun só aplica com o stream parado |
| `0x09` | **ACK de vídeo** | `4` f32 timestamp do quadro confirmado; opcional (len ≥ 16): `8` u32 atraso de decodificação, `12` u32 atraso de pós-processamento (mostrados na UI). Atualiza `ack` e acorda o renderizador |
| `0x0B` | **Iniciar stream** (24 B) | `4` u32 flag (≠0 ⇒ **enviar áudio**); `8` u32 codec (**2 = HEVC**, qualquer outro = H.264); `12` u32 largura; `16` u32 altura; `20` **f32** fps. Zera o ACK (`ack = -1`, `anterior = 0`) e a origem do relógio; chama `LocalService.a(codec, w, h, fps, tipoPC, versãoPC)`. Se a senha falhou: "Access denied" (`f()`), nenhum stream |
| `0x0C` | diagnóstico do PC | len = 0x10C: u32, u32 (>0xE), 256 B texto → só telemetria (`craa`) |
| `0x10` | **Controle de câmera** | `4` u32 id; `8` valor (u32, ou f32 conforme o id). Ver §4.3 |
| `0x11` | Info do PC | `4` u32 tipo do PC; `8` u32 versão do cliente PC |
| `0x12` | Toque para focar | `4` f32 x∈[0,1]; `8` f32 y∈[0,1]; `12` u32 (len ≥ 13): 1 ⇒ liberar/voltar ao automático |
| `0x17` | **Resposta de senha** | `8` u64 = `nonce XOR FNV1a64(senha)`; diferente ⇒ acesso negado |
| `0x19` | Foco manual | `4` u32 flags (bit0 = manual); `8` f32 distância de foco (`LENS_FOCUS_DISTANCE`) |
| `0x1A` | **Uso da câmera virtual** | `4` u32 versão (o celular ignora se ≤ 1); `8` u32 índice da câmera do PC (0–3); `12` u32 estado: **2 = algum aplicativo está com a câmera virtual aberta, 1 = ninguém** (engine.dll, ver `docs/WINDOWS.md` §3) |
| `0x30` | **Tally (extensão Niriun)** | `4` u32 estado (0 = desligado, 1 = preview, 2 = programa); `8` nome da fonte opcional, 256 B NUL-preenchido. Não existe no Iriun original; ver §4.5 |
| `0x31` | **Tela ao parar (extensão Niriun)** | `4` u32 modo (0 = nada, 1 = preto, 2 = cartão offline). Celular → PC; ver §4.6 |

### 4.3 Controle de câmera `0x10` (id → ação, vindo de `LocalService.d(int,int)`)

| id | Ação | Valor |
|---|---|---|
| 1 | travar/destravar AE+AWB e disparar AF | — |
| 2 | alternar lanterna (`FLASH_MODE_TORCH`) | — |
| 3 | alternar espelhamento horizontal | — |
| 4 | zoom | 0–100 % (`CONTROL_ZOOM_RATIO` interpolado entre mín e máx; senão `SCALER_CROP_REGION`) |
| 5 | próxima câmera | — |
| 6 | ISO manual (<0 = auto) | int |
| 7 | tempo de exposição manual (<0 = auto) | **f32 segundos** (o nativo multiplica por 1e9 → ns) |
| 8 | compensação de exposição | **f32 EV** (nativo ×1000; Java divide por 1000 e pelo passo de AE) |
| 9 | temperatura de cor (<0 = AWB auto) | int Kelvin |
| 10 | alterna estado de UI/áudio (`w0`) | — |
| 11 | fechar o app | — |
| 14 | selecionar câmera pelo índice | int |

### 4.4 Autenticação por senha (opcional)

* `hash = FNV-1a 64 bits` dos bytes UTF-8 da senha (offset `0xCBF29CE484222325`, primo `0x100000001B3`); senha vazia ⇒ 0
  (`FUN_0012de2c`). Sem senha o desafio nem é enviado.
* No `accept` o celular gera `nonce` (mt19937 semeado por `/dev/urandom`) e envia `0x17`.
* O PC responde `0x17` com `nonce XOR hash`. `(nonce ^ hash) != resposta` ⇒ `0x0B` é recusado ("Access denied").

### 4.5 Extensão Niriun: tally (0x30)

**Não faz parte do protocolo original do Iriun** (o Iriun não usa o tipo `0x30`; o app original ignoraria o pacote). É uma
extensão do Niriun: o plugin Niriun para OBS Studio (cliente do PC) informa ao celular, pelo próprio canal de controle,
se a fonte está no programa ou no preview do OBS.

| Offset | Campo |
|---|---|
| `0` | cabeçalho u32 LE = `(tamanhoTotal & 0xFFFFFF) \| (0x30 << 24)` |
| `4` | u32 estado: `0` = desligado (fora do programa e do preview), `1` = preview, `2` = programa |
| `8` | **opcional**: nome da fonte do OBS, UTF-8 NUL-preenchido em um campo de 256 B (total = 264). Um pacote de exatamente 8 B (sem nome) também é aceito |

* O plugin envia logo após o `START` e a cada mudança enquanto conectado. O celular nunca responde.
* Estado desconhecido (> 2) ⇒ o pacote é ignorado. O nome serve só para exibição ("AO VIVO · Camera 1").
* Ao desconectar, o celular volta o tally para desligado (sem PC o estado é desconhecido).

### 4.6 Extensão Niriun: tela ao parar (0x31)

Também **não faz parte do protocolo original**. Quando o usuário toca em Parar, o celular envia este pacote logo antes de
fechar a conexão para dizer ao plugin o que deixar na fonte do OBS (opção "Ao parar, o OBS mostra" das configurações).

| Offset | Campo |
|---|---|
| `0` | cabeçalho u32 LE = `(8 & 0xFFFFFF) \| (0x31 << 24)` |
| `4` | u32 modo: `0` = sem imagem, `1` = quadro preto, `2` = cartão offline (roxo escuro com anel e traço) |

* Celular → PC; só é enviado em parada deliberada. Queda de rede ou app fechado não enviam nada e o plugin limpa a imagem como antes.
* Modo desconhecido ⇒ tratado como `0`. O plugin limpa o quadro quando a fonte é desativada.

## 5. Controle de fluxo (ACK)

Estado nativo: `enviado` (`+0x4070`), `anterior` (`+0x406C`), `ack` (`+0x4074`, inicia em −1).

* Após enviar um quadro com timestamp `t` (f32): `anterior = enviado; enviado = t` (`FUN_0012d798`).
* Ao receber ACK (`0x09`): `ack = t_ack`; `notify_all`.
* `nativeWaitForVideoAck` (`FUN_0012d5c8`): se `enviado ≠ 0 && ack ≥ 0 && anterior > ack`, espera até **100 ms** pelo ACK.
  Retorna `ack < anterior` ("PC atrasado"). O laço de renderização (`oy0.run`) usa isso para **não alimentar o encoder**
  (descarta o quadro da câmera) — no máximo ~2 quadros em voo.
* Timestamp = `(f32)(CLOCK_MONOTONIC − origem)`; `eglPresentationTimeANDROID(ts × 1e9)`; `presentationTimeUs / 1e6` vai no pacote.

## 6. Codificação

* `MediaCodec.createEncoderByType("video/avc"|"video/hevc")`, `COLOR_FormatSurface` (0x7F000789).
* Bitrate (bps): 3840×2160 → 16 000 000; 2560×1440 → 10 000 000; 1920×1080 → 6 894 720; 1280×960 → 4 300 000; 1280×720 → 3 628 800;
  960×540 → 2 200 000; 640×480 → 1 612 800; 640×360 → 1 400 000; outros: `w·h·2,7 + 1,5 M`. ×10/9 na versão pro; ×3/2 se fps > 40;
  HEVC: ×4/5 e `profile=Main`, `level=HighTier` (API ≥ 27); teto 16 Mbps.
* `frame-rate = min(pedido, fps da câmera)`, `i-frame-interval = 15`, `priority = 0` (tempo real).
* Rotação da câmera: `(SENSOR_ORIENTATION − rotação da tela) % 360` (invertido na frontal), aplicada em GL.
* Câmera: `TEMPLATE_RECORD`, `CONTROL_AF_MODE_CONTINUOUS_VIDEO`, `CONTROL_AE_TARGET_FPS_RANGE` com teto ≥ fps pedido (para > 30).

## 7. Incertezas conhecidas

1. Os campos de `0x13–0x16`, `0x19` (celular→PC) continuam inferidos.
2. O que o cliente PC faz com `0x10`/`0x19` vindos do celular.
3. O PC não envia keep-alive ocioso (`docs/WINDOWS.md` §3); o timeout de 10 s é coberto pelo ACK por quadro e pelo tráfego do `0x0A`/`0x18`.
4. O cliente PC oficial 2.9.9 foi testado ao vivo com o Niriun (vídeo, áudio, controles). Ver `docs/WINDOWS.md`.

## 8. Verificado dinamicamente (implementação Niriun, emulador Android 17 + `tools/pc-sim`)

Confirmado que a *implementação* segue o protocolo acima e que o fluxo é decodificável: ordem das mensagens de abertura, `START` H.264/HEVC → Annex-B decodificado
pelo ffprobe, Opus cru (TOC `0xF8`), desafio/resposta de senha (errada/ausente = sem vídeo), troca de câmera reenviando `0x0A`/`0x18`. Isso valida a coerência
interna do código, não a aceitação pelo cliente oficial.
