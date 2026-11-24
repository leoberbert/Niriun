# Como a análise foi feita

Alvo: `Iriun+4K+Webcam+for+PC+and+Mac_2.9.7_APKPure.zip` (xapk com `com.jacksoftw.webcam.apk` base, versionCode 215,
mais splits de idioma/ABI). O APK não foi executado: toda a análise é estática.

## Ferramentas

| Parte | Ferramenta |
|---|---|
| `classes.dex` (Java/Kotlin, ofuscado por R8) | jadx 1.5.7 (`java -cp jadx-1.5.7-all.jar jadx.cli.JadxCLI`) |
| `lib/arm64-v8a/libnative-lib.so` (C++, sem símbolos) | Ghidra 12.1.4 headless (`analyzeHeadless`) + JDK 21, scripts em [`tools/ghidra`](../tools/ghidra) |
| Verificação do fluxo gerado | ffmpeg/ffprobe, emulador Android 17, `tools/pc-sim/pc-sim.js` |

Scripts Ghidra (rodam com `-process libnative-lib.so -noanalysis -postScript <script> <args>`):

* `ListFuncs.java <pasta de saída>`: lista funções (`funcs.txt`) e símbolos externos (`externs.txt`).
* `DecompRange.java <lo> <hi> <saida.c>`: descompila todas as funções de um intervalo de endereços.
* `DumpVt.java <saida> <endereço> <n> ...`: lê vtables C++ e resolve as funções.
* `GetStr.java <saida> <endereço> ...`: lê strings da `.rodata`.
* `Disasm.java <saida> <lo> <hi>`: desmonta um intervalo (usado para confirmar a ordem dos argumentos, ex.: o `f32` de fps em `s0`).

## Caminho da análise

1. **Manifesto e Java.** `LocalService` mostra o pipeline: Camera2 + `MediaCodec` (`video/avc` ou `video/hevc`,
   `COLOR_FormatSurface`), `NsdManager` (`_iriunwebcam._tcp.`, porta 4699), e a ponte JNI: `nativeSend(ByteBuffer, offset, size, ts)`,
   `nativeWaitForVideoAck()`, `nativeSetSizes`, `nativeSetCameraList`, `nativeSetPasscode`, `nativeStart(LocalService)`.
   Os callbacks nativo→Java têm nomes curtos preservados pelo R8 (`a(IIIIII)V` = iniciar stream, `d(II)V` = controle…).
2. **Nativo.** `strings` na `.so` revelou libopus + FFmpeg (libswresample) + Oboe e as funções `Java_com_iriun_webcam_*`.
   Os imports (`listen`/`accept4`/`sendto`/`recvfrom`/`poll`/`setsockopt`) deram a forma da rede. As funções do app ocupam
   `0x126830–0x12fe40`; o resto é libopus.
3. **Servidor TCP.** `FUN_0012e9e8` (socket AF_INET6, `bind` na porta de `+0xE8`, `listen(1)`, `accept4`) e `FUN_0012f2d4`
   (laço de recepção, enquadramento de 24 bits de tamanho + 8 bits de tipo, buffer de 4 000 000 bytes, timeout ocioso).
4. **Despachante de mensagens.** `FUN_0012cb48`, ligado ao vtable `0x19a0a8`. A desmontagem de `0x12cb48` e `0x1289e0`
   confirmou a ordem dos campos (por exemplo, o `fps` da mensagem `0x0B` é `f32` em `s0`; o valor do controle `0x10` é lido
   como inteiro e convertido para float só nos ids 7 e 8).
5. **Mensagens do celular.** `FUN_00128b0c` (hello 0x118 bytes), `FUN_00128800` (tamanhos), `FUN_0012c94c` (câmeras),
   `FUN_0012d798` (vídeo), `FUN_0012d34c` (áudio Opus, `opus_encoder_create(48000, 1, 0x801)`).
6. **Descoberta.** `FUN_0012ac54` (beacon multicast a cada 20×100 ms), `FUN_0012ba84` (socket do beacon), `FUN_001291d0`/`FUN_0012944c` (porta 4697).
7. **Senha.** `FUN_0012de2c` (FNV-1a 64) e a lógica de `case 0x17` em `FUN_0012cb48`.

## O que a verificação dinâmica confirmou (emulador + `pc-sim.js`)

* hello/sizes/cameras chegam nessa ordem; com senha, o desafio `0x17` chega **antes** do hello.
* `START` com H.264 e HEVC produz fluxo Annex-B decodificável (`ffprobe`: h264 Constrained Baseline 1280×720 e 640×480; hevc Main 640×480).
* Pacotes Opus de 20 ms vêm do encoder da plataforma sem cabeçalho Ogg (TOC `0xF8`, CELT fullband).
* Senha errada ou ausente → nenhum quadro; senha certa → vídeo e áudio.
* Trocar a câmera por controle `0x10`/id 14 reenvia `0x0A` e `0x18` com o novo índice.
* Mandar `SYNC` periodicamente (como keep-alive) derruba todos os quadros: o relógio volta e o `GraphicBufferSource` do
  Android descarta quadros "indo para trás no tempo". Por isso o keep-alive do simulador usa o tipo `0x01` (no-op) e o app
  ignora `SYNC` durante o stream.

## Artefatos intermediários

Fora do repositório, em `C:\work\iriun`: APKs extraídos, `jadx_out` (fonte Java), `ghidra_proj` (projeto Ghidra com a
análise) e `app_decomp.c` (descompilação das funções do app). Podem ser apagados sem afetar o projeto.
