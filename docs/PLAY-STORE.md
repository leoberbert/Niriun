# Checklist da Play Store

## Chaves
- **Chave de upload**: `signing/niriun-upload.jks` (alias `niriun-upload`). Assina o `.aab` enviado.
- **Play App Signing**: ative ao criar o app. O Google guarda a chave final; se a chave de upload for perdida, e
  possivel pedir redefinicao ao suporte. Sem copia do `.jks` e das senhas, nao ha como publicar atualizacoes.
- Faca copia do `.jks` e das senhas fora do computador (gerenciador de senhas).

## Identidade
| Item | Valor |
| --- | --- |
| Package (applicationId) | `com.niriun.app` (nao pode mudar depois de publicar) |
| Nome | Niriun |
| Idiomas | Portugues (Brasil) e Ingles |

## Itens que voce precisa providenciar
- Icone 512x512 PNG e recurso grafico 1024x500 (modelo: `DroidNDI/store-assets`).
- Capturas de tela do celular (minimo 2).
- **URL da politica de privacidade** (obrigatoria: o app usa camera e microfone).
- Descricao curta (80) e completa (4000 caracteres).
- Email de contato do desenvolvedor.
- Conta de desenvolvedor (taxa unica de US$ 25; contas pessoais novas exigem teste fechado com 12 testadores por 14 dias
  antes da producao).

## Declaracoes na Play Console
- **Seguranca dos dados**: o app nao coleta nem envia dados para servidores. Video e audio vao so para o computador na
  rede local; o endereco e a senha do OBS ficam apenas no aparelho. Declare: nenhum dado coletado/compartilhado.
- **Permissoes sensíveis / servico em primeiro plano**: o app declara `FOREGROUND_SERVICE_CAMERA` e
  `FOREGROUND_SERVICE_MICROPHONE`. Prepare um video curto mostrando o servico ativo com notificacao e a funcao
  (webcam de rede) para a declaracao.
- Permissoes de runtime: `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `ACCESS_LOCAL_NETWORK` (Android 17+).
- Trafego em texto claro (`usesCleartextTraffic`): necessario para `ws://` do OBS na rede local.
- Classificacao de conteudo (questionario) e publico-alvo (maiores de 13 anos).
- Marca: o nome "Iriun" nao deve aparecer na ficha da loja alem de uma menção de compatibilidade clara.

## Risco conhecido
O protocolo vem de engenharia reversa do Iriun e o app depende do cliente do Iriun no Windows. Avalie o risco de
politica/marca antes de publicar na Play; o APK na GitHub Release e a alternativa.
