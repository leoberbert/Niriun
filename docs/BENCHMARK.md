# Benchmark Niriun x DroidNDI

Objetivo: comparar atraso (vidro a vidro), travamentos e custo no celular, na mesma rede e com a mesma cena.

## 1. Atraso vidro a vidro (o número que importa)
1. No PC, abra um cronômetro em milissegundos (por exemplo https://time.is ou um app de cronômetro) em tela cheia.
2. Aponte a câmera do celular para o cronômetro. No OBS, mostre a fonte ao lado do cronômetro real (captura de tela).
3. Fotografe/grave a tela do PC com **outro celular a 60 fps ou mais** (ou use captura de tela a 60 fps). Em cada quadro,
   atraso = cronômetro real - cronômetro visto na fonte.
4. Meça 30 quadros por modo; anote mediana, p95 e máximo.

Modos a comparar (mesma câmera, 1080p30, mesmo roteador, celular e PC a 1 m e a ~10 m do roteador):
| Modo | PC |
|---|---|
| Niriun (H.264) | Iriun Webcam + OBS |
| DroidNDI 720p30 | OBS (DistroAV) |
| DroidNDI 1080p30 | OBS (DistroAV) |
| Referência | webcam USB ou HDMI no OBS |

## 2. Travamentos
Deixe 10 minutos rodando em cada modo e conte congelamentos acima de 200 ms (gravação do OBS + `ffprobe` com
`-show_frames` para ver intervalos entre quadros, ou o menu Estatísticas do OBS: quadros perdidos e atraso de renderização).
Repita com carga: um download grande no mesmo Wi-Fi e depois afastando o celular do roteador.

## 3. Custo no celular
* **DroidNDI** (build da branch `bench/instrumentation`): `adb logcat -s DroidNDI-Bench DroidNDI-Camera`. A cada 5 s:
  `pack` = ms para converter YUV→UYVY e girar na CPU; `send` = ms dentro de `NDIlib_send_send_video_v2` (compressão SpeedHQ
  + envio, bloqueante); `busy` = % do tempo da thread de câmera ocupada; `analyzer: arrived/submitted` = quadros da câmera
  que chegaram e que foram enviados. `busy` perto de 100% ou `submitted` bem abaixo de `arrived` indica gargalo no celular.
* **Niriun**: tela Detalhes mostra fps e kbps. `adb shell dumpsys gfxinfo`/`top -H` para CPU por thread em ambos.
* Ambos: `adb shell dumpsys batterystats` e temperatura (`adb shell dumpsys battery`) após 10 min.
* Banda: no PC, `typeperf "\Network Interface(*)\Bytes Received/sec"` durante o teste.

## Resultados medidos (24/11, Samsung SM-S938B, câmera traseira, cena quase parada)
Receptor: PC por Ethernet, celular por Wi-Fi 5 GHz, mesmo roteador. DroidNDI: build `bench/instrumentation` (release, id `.bench`) com
`tools` próprio de receptor NDI; Niriun: release 0.1.1 + `tools/pc-sim/pc-sim.js` (confirma cada quadro, não decodifica).
Havia 2 receptores NDI ligados no DroidNDI (OBS do usuário + o de teste).

| Modo | fps recebido | intervalo entre quadros p50 / p99 / máx (ms) | Rede | CPU do celular por quadro |
|---|---|---|---|---|
| DroidNDI 720p30 | 30,0 | 32 / 63 / 76 | ~110 Mbit/s por receptor (NIC total 224 com 2 receptores, 720p60) | conversão 10,7 ms, envio 5,9 ms: thread 50% ocupada |
| DroidNDI 720p60 | 59,5 | 16,6 / 29,5 / 45 | idem | conversão 8,7 ms, envio 3,4 ms: **72%** |
| DroidNDI 1080p30 | 29,9 | 32 / 55 / 65 | NIC total 227 Mbit/s | conversão **20,2 ms (máx 39)**, envio 6,2 ms: **80%** |
| DroidNDI 1080p60 | **42,0** | 23 / **122** / 161 (17 pausas > 100 ms) | NIC total 200 Mbit/s | conversão 21 ms, thread **99% ocupada**: não sustenta 60 fps |
| Niriun 720p30 | 29,8 | 33 / 48 / 66 | **4,1 Mbit/s** | codificação por hardware |
| Niriun 1080p30 | 29,1 | 33 / 59 / 80 | **7,1 Mbit/s** | idem |
| Niriun 1080p60 pedido | **28,7** | 33 / 54 / 67 | 12,2 Mbit/s | o app entregou ~29 fps: pendência a investigar |

Limites da medição: cena parada (movimento aumenta o tamanho dos quadros), atraso vidro a vidro **não medido**, a decodificação do
Iriun no PC não está no teste do Niriun, e o Iriun do PC chegou a ficar aberto durante os testes do DroidNDI.

## DroidNDI depois da conversão rápida + envio assíncrono (mesma bancada, 24/11)
| Modo | Antes: fps, p99 de intervalo (ms), conversão, thread ocupada | Depois |
|---|---|---|
| 720p30 | 30,0, 63, 10,7 ms, 50% | 29,9, 59, **3,1 ms, 9%** |
| 1080p30 | 29,9, 55, 20,2 ms (máx 39), 80% | 30,0, 61, **5,9 ms, 18%** (1 pausa de 105 ms) |
| 1080p60 | **42,0**, **122** (17 pausas > 100 ms), 21 ms, 99% | **59,6**, **35**, 0 pausas > 100 ms, **4,9 ms, 30%** |
A banda do NDI não mudou (~110 Mbit/s por receptor em 720p; NIC total 229 Mbit/s em 1080p30 e 291 Mbit/s em 1080p60).

## Niriun 1080p60 corrigido (24/11)
Causa: com `fps > 30` o app escolhia a primeira faixa de AE com máximo 60, que no S25 Ultra é a variável `[15,60]`; o AE a derrubava
para ~30 fps. A câmera traseira também oferece `[60,60]`, e agora ela é preferida (`CameraPipeline`).
| Modo | fps | intervalo p50 / p99 / máx (ms) | Rede |
|---|---|---|---|
| Niriun 1080p60 antes | 28,7 | 33 / 54 / 67 | 12,2 Mbit/s |
| Niriun 1080p60 depois | **57,1** | **16,6 / 32 / 46** | 10,7 Mbit/s |
(Uma segunda rodada em 1080p30 não pôde ser medida porque o cliente Iriun do PC se reconectou ao celular.)
