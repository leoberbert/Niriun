# CI/CD - GitHub Actions

## Workflows

### CI (`.github/workflows/ci.yml`)
Roda em push na `main`, em pull request e manualmente: testes unitarios, APK debug e lint. Publica o APK debug e o
relatorio de lint como artifacts (7 dias).

### Release (`.github/workflows/release.yml`)
Somente manual (Actions -> Release -> Run workflow).

| Input | Descricao |
| --- | --- |
| `tag` | Ex.: `v0.1.0`. Precisa ser igual ao `versionName` de `app/build.gradle.kts` (o workflow confere) |
| `prerelease` | Marca a release como pre-release |

Gera `Niriun-<versao>.apk` e `Niriun-<versao>.aab` assinados, mais `SHA256SUMS.txt`, e publica uma GitHub Release.

## Secrets (Settings -> Secrets and variables -> Actions)

| Secret | Conteudo |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `signing/niriun-upload.jks` em base64 |
| `SIGNING_STORE_PASSWORD` | Senha do keystore |
| `SIGNING_KEY_ALIAS` | `niriun-upload` |
| `SIGNING_KEY_PASSWORD` | Senha da chave |

Nao ha variaveis (Variables) nem outros secrets. O keystore e as senhas ficam em `signing/` e `signing.properties`,
ambos no `.gitignore`.

### Recriar o keystore em base64
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("signing\niriun-upload.jks")) | Set-Clipboard
```

## Build de release local
Crie `signing.properties` na raiz:
```
storeFile=signing/niriun-upload.jks
storePassword=...
keyAlias=niriun-upload
keyPassword=...
```
```
./gradlew assembleRelease bundleRelease
```
Saidas: `app/build/outputs/apk/release/app-release.apk` e `app/build/outputs/bundle/release/app-release.aab`.
Sem `signing.properties` o build de release falha de proposito (`validateReleaseSigning`).

## Publicar uma versao
1. Aumente `versionCode` (sempre) e ajuste `versionName` em `app/build.gradle.kts`.
2. Merge na `main`.
3. Actions -> Release -> Run workflow com a tag `v<versionName>`.
4. Envie o `.aab` da release para a Play Console (veja `docs/PLAY-STORE.md`).
