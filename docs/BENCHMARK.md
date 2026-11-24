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
