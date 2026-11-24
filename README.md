# Niriun

App Android (Kotlin) que transforma o celular em webcam de rede, usando o mesmo mecanismo observado no
Iriun Webcam para Android 2.9.7: descoberta na LAN, servidor TCP na porta **4699**, vídeo H.264/HEVC
vindo do `MediaCodec`, áudio Opus, ACK por quadro e controles remotos da câmera.

O protocolo, com o endereço de cada evidência na `libnative-lib.so`, está em
[`docs/PROTOCOL.md`](docs/PROTOCOL.md). O método da análise está em [`docs/ANALYSIS.md`](docs/ANALYSIS.md).
Nenhum código ou asset do Iriun foi copiado: tudo aqui foi escrito do zero a partir do comportamento observado.

## O que faz

| Área | Implementação |
|---|---|
| Descoberta | NSD `_iriunwebcam._tcp.` (4699), beacon UDP multicast de 280 B a cada 2 s (`239.255.188.58:23058` e `ff02::2:d644`), sonda TCP 4697 |
| Transporte | `ControlServer`: TCP `[::]:4699`, 1 cliente, `TCP_NODELAY`, timeout ocioso de 10 s, watchdog de escrita de 4 s |
| Vídeo | Camera2 → `GlRenderer` (OES → rotação/espelho/letterbox) → `MediaCodec` H.264/HEVC → pacote `0x08` |
| Áudio | `AudioRecord` 48 kHz mono → encoder Opus da plataforma (20 ms) → pacote `0x07` |
| Fluxo | ACK `0x09` por quadro + limite de quadros em voo no encoder (`FlowControl`, `EncoderBacklog`) |
| Controles | zoom, lanterna, espelho, EV, balanço de branco, ISO/tempo de exposição, travar AE/AWB, foco manual, toque para focar, trocar câmera |
| Tally | WebSocket do OBS (porta 4455): borda magenta ao vivo, âmbar no preview, na fonte escolhida; confirmação dupla ao iniciar/parar com a fonte ao vivo; fluxo guiado para escolher ou criar a fonte no OBS |
| Senha | não implementada: o desafio `0x17` do Iriun (§4.4 do protocolo) foi removido do app |
| UI | Compose: prévia em tela cheia, status, borda de tally, painel de câmeras, detalhes e OBS |

Pacotes: `protocol/` (puro Kotlin, testável na JVM), `net/` (sockets e descoberta), `stream/` (câmera, GL, codecs,
sessão, serviço em primeiro plano), `ui/`.

## Compilar e testar

```
./gradlew testDebugUnitTest     # testes JVM: protocolo, framing, servidor TCP em loopback, beacon multicast
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

Requisitos: JDK 17+, Android SDK com a plataforma 37 (mesmas versões do DroidNDI: AGP 9.3.2, Gradle 9.5.0, Compose BOM 2026.09.00).

## Testar sem o cliente do PC

`tools/pc-sim/pc-sim.js` (Node) imita o cliente do PC: lê hello/tamanhos/câmeras, envia `START`, confirma cada
quadro e grava o fluxo elementar para decodificar com ffmpeg.

```
adb forward tcp:4699 tcp:4699
node tools/pc-sim/pc-sim.js --seconds 10 --size 1280x720 --fps 30 --audio --out out.h264 --verbose
node tools/pc-sim/pc-sim.js --codec hevc --out out.h265
node tools/pc-sim/pc-sim.js --control zoom=50,torch,next-camera
ffprobe -count_frames -select_streams v:0 out.h264
```

## Diferenças deliberadas em relação ao Iriun

* Intervalo de IDR de **2 s** (o Iriun usa 15 s): recupera mais rápido de qualquer falha.
* `SYNC` (`0x04`) só zera o relógio com o stream parado; no meio do stream faria os timestamps voltarem e o
  `GraphicBufferSource` do Android descartaria todos os quadros.
* O renderizador também pula quadros quando o encoder acumula mais de 3 quadros (latência limitada em encoders lentos).
* Sem as travas "pro" do Iriun (4K/60 fps e controles manuais liberados para todos).
* Não envia as mensagens de info `0x13–0x16`/`0x19` (campos não confirmados; o Iriun gratuito também não envia).

## Limitações conhecidas

* O app só é útil com o cliente do Iriun Webcam instalado no Windows (ele cria a câmera virtual). Testado com o
  cliente 2.9.9, OBS 32 e um Samsung SM-S938B (Android 17). Outros aparelhos e fabricantes ainda não foram testados.
* O tally depende do OBS WebSocket (porta 4455) e do nome da fonte escolhida; sem conexão, o app não sabe se está ao vivo.
* O vídeo não passa pelo OBS WebSocket (ele só controla o OBS); a imagem chega ao OBS pela câmera virtual do Iriun.

## Releases

O CI (`.github/workflows`) roda testes, lint e APK debug a cada push. A release assinada (APK + AAB) é manual:
veja [`docs/ci-cd.md`](docs/ci-cd.md) e [`docs/PLAY-STORE.md`](docs/PLAY-STORE.md).

## Aviso

Projeto independente, sem vínculo com o Iriun. "Iriun" é marca de seus donos. O protocolo foi documentado por
observação do comportamento do app para fins de interoperabilidade. Licença: Apache-2.0.
