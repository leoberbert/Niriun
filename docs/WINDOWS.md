# Análise do cliente Windows (Iriun Webcam 2.9.9)

Alvo: `C:\Program Files (x86)\Iriun Webcam` (instalado a partir de `IriunWebcam-2.9.9.exe`). Método: extração do bundle
.NET, decompilação da UI (ILSpy 9.1), Ghidra 12.1.4 em `engine.dll` e observação passiva do processo (`netstat`, `reg`,
`pnputil`). Os endereços abaixo são da imagem x86 de `engine.dll` (base `0x10000000`). Nenhum código do Iriun é copiado.

## 1. Estrutura

| Arquivo | O que é |
|---|---|
| `IriunWebcam.exe` | apphost .NET 10 (arquivo único). Contém só `IriunWebcam.dll` (UI WPF, 313 KB), `runtimeconfig` e `deps.json` |
| `engine.dll` | **x86 nativo, 492 KB**: descoberta, TCP, decodificação (FFmpeg), preview D3D9, ponte com os drivers. Toda a lógica de protocolo está aqui |
| `avcodec-62 / avutil-60 / swscale-9.dll` | FFmpeg (H.264/HEVC); o Opus está linkado dentro de `engine.dll` |
| `adb.exe`, `AdbWin*.dll` | conexão por cabo (USB) com Android via `adb forward` |
| `devcon.exe` | instala/remove os dispositivos virtuais |

A UI chama `engine.dll` por P/Invoke: `EInitialize(count, cb)`, `EOpen()`, `SetCallback(...)`, `ESelectPhone(idx, tag)`,
`ESetVideoFormat`, `EFocus`, `EFocusDistance`, `ButtonEvent`, `ESetPasscode(uid, texto)`, `ESetBlank`, `Render`.

## 2. Os "até 4 dispositivos" do instalador

* O instalador grava `HKLM\Software\WOW6432Node\Iriun\webcam\cameraCount` (DWORD, 1 a 4; nesta máquina vale 4). A UI lê
  esse valor (`MainWindow`), força 1 se estiver fora de 1–4 e mostra uma aba por câmera (`tab0`..`tab3`).
* `EInitialize(count)` cria `count` instâncias; cada aba chama `EOpen()` e recebe um índice 0–3, que vai no segundo campo do `0x1A`.
* Dispositivos vistos com `pnputil` nesta máquina: câmeras `Iriun Webcam`, `Iriun Webcam #2` e `#4` (classe Camera,
  `ROOT\DEVGEN\{...}`, drivers `oem5/oem10/oem30.inf`) e `Microfone (Iriun Webcam #3)` e `#4`. `engine.dll` referencia `ROOT\IriunA0`..`A3` (áudio) e 12 objetos nomeados
  `Global\...`/`Local\...` (memória compartilhada/eventos com os drivers).
* Consequência para o Niriun: o PC pode ter até 4 câmeras virtuais e o celular é ligado a uma aba (`ESelectPhone(idx, tag)`).
  Não foi verificado se o mesmo celular pode atender duas abas.

## 3. O que `engine.dll` faz com o celular

### Descoberta (PC)
* **UDP:** para cada interface IPv4 ativa (exclui túnel `0x83` e loopback `0x18`), abre um socket UDP em
  `0.0.0.0:4698`, entra no grupo `239.255.188.58` (bytes `ef ff bc 3a`) e escuta; o IPv6 usa `FF02::2:d644`. Confirmado em
  execução: `netstat -ano -p UDP` mostra `0.0.0.0:4698` no processo `IriunWebcam.exe`.
  **Correção do Niriun:** a porta é **4698 (0x125A)**, não 23058. O valor `0x5A12` visto no APK é o `u16` já em ordem de rede.
  Até esta versão o beacon do Niriun ia para a porta errada e a descoberta só funcionava pelo NSD.
* **NSD/mDNS:** `DnsServiceBrowse` (`dnsapi.dll`) para `_iriunwebcam._tcp.local`.
* **USB:** `adb.exe devices`, depois `adb forward tcp:4697 tcp:4697` / `tcp:4699 tcp:4699` e conexão em `127.0.0.1`. Os
  nomes mostram `[USB]` ou `[WiFi]`. Para iPhone usa `usbmuxd` (portas 4697 e 4699).

### Mensagens recebidas do celular (`FUN_10009980`, despacho por `bytes[3]`)
`0x01` hello/beacon (UDP: chave = 12 bytes do UUID, nome nos 256 B a partir de `+0x18`), `0x02`, `0x03` (responde `0x04`),
`0x05`, `0x07` áudio (Opus, decodificado com `FUN_10025ed0`; saída provavelmente PCM 16 bits mono, **inferido**), `0x08` vídeo, `0x09`, `0x0A` tamanhos, `0x0D`/`0x0E`, `0x10` controle,
`0x13`–`0x16` ISO/exposição/EV/WB, `0x17` desafio de senha, `0x18` lista de câmeras, `0x19` foco. Tipos que o app Android
não envia (`0x02`, `0x03`, `0x05`, `0x0D`, `0x0E`) existem para o cliente iOS.

### Mensagens enviadas ao celular
| Tipo | Quando | Conteúdo |
|---|---|---|
| `0x11` | logo depois de receber `0x0A` | tipo e versão do PC |
| `0x0B` | depois do `0x0A`, e de novo se o usuário mudar o formato | `flag=1` (**sempre pede áudio**), codec, largura, altura, fps (24 B) |
| `0x1A` | logo depois do `0x0B` e a cada mudança | `{versão, índice da câmera, estado}` (ver abaixo) |
| `0x09` | a cada quadro decodificado | timestamp + atrasos (16 B) |
| `0x17` | em resposta ao desafio | `nonce XOR FNV1a64(senha)` |
| `0x10`, `0x19` | ações do usuário na UI | controles (zoom, lanterna...) e foco manual. O remetente de `0x12` (toque) não foi localizado |
| `0x0C` | quando existe o valor `erd` em `HKCU\Software\Iriun\webcam` | relatório de erro (256 B), apagado depois de enviado |
| `0x04` | em resposta a `0x03` | sincronização |

### `0x1A`: "alguma aplicação está usando a câmera virtual"
Terceiro campo = `FUN_10017940() + 1`. A função devolve verdadeiro quando o bloco de memória compartilhada da câmera
virtual tem leitor (ou, sem ele, quando o driver responde `IOCTL 0x222008` com contagem de leitores ou estado 2).
Ou seja, **1 = ninguém abriu a câmera, 2 = OBS/Zoom/Teams... abriu**. O PC reenvia ao mudar. O Niriun passou a ler
essa mensagem (detalhes na tela de Detalhes). Observação importante: o OBS mantém o dispositivo aberto enquanto a fonte
existe, a menos que "Desativar quando não estiver visível" esteja ligado; então isso **não substitui o tally**.

### Escolha de codec
`FUN_10011280(hevc)`: id do codec = `0x1B` (H.264) ou `0xAD` (HEVC). HEVC só é aberto se o celular marcar o bit 16–23 do campo
de flags do `0x0A` **e** existir decodificação por hardware D3D11VA; se faltar qualquer um, o PC cai para H.264
(`FUN_10011140(0)`). O campo `codec` do `0x0B` é `(id != H.264) + 1`. O Niriun hoje envia flags sem esse bit, então sempre
recebe H.264.

### Escolha de tamanho
O PC só usa tamanhos que o celular anunciou. A UI mostra a lista (`Automatic`, `640 x 360` … `3840 x 2160`, e as variantes
em pé `720 x 1280`, `1080 x 1920`); a escolha vai para `HKCU\...\videoFormat<N>`. Se a lista vier vazia, usa 640×480@30.

### Tempo e keep-alive
Não há mensagem periódica de keep-alive no PC: o tráfego contínuo é o ACK `0x09` por quadro. O recebimento usa `WSAPoll`
de 100 ms e buffer próprio; mensagem maior que o buffer derruba a conexão.

## 4. Pendências
* Testar `flags` com o bit 16 ligado (HEVC) com o Iriun real e medir latência/qualidade.
* Usar o `0x1A` num segundo sinal "ao vivo" quando não houver OBS.
* Confirmar o conteúdo exato de `0x02`, `0x05`, `0x0D`, `0x0E` (só interessam ao iOS).
