# DarkCamera OpenSource

**Criado por [Rômulo Innocêncio](https://github.com/romuloinnocencio)**

> I decided to create my own camera app that fill my needs! (Old phone)

Câmera profissional para Android (foto + vídeo), feita para o Galaxy S20+ 5G.
Kotlin + Camera2, sem bibliotecas externas.

## Recursos (v0.1.0)
- **Vídeo:** 4K UHD (até 30 fps) ou 1080p (até 60 fps), H.265/H.264, 50/100/150 Mb/s, perfil LOG, áudio 48 kHz estéreo
- **Foto:** RAW (DNG), JPEG ou RAW+JPEG, proporção 4:3/16:9/1:1, timer 2/10 s, bracketing ±1/±2 EV
- **Controles manuais:** ISO, obturador (ângulo no vídeo, velocidade na foto), balanço de branco em Kelvin + tint, foco manual
- **Monitoramento:** histograma, zebras (90/95/100%), focus peaking, grade de terços, guias 2.39:1 / 1.85:1 / 9:16
- **Lentes:** ultra-wide, principal e tele (detectadas automaticamente)
- Arquivos salvos em `DCIM/DarkCamera`. Os botões de volume gravam/fotografam.

## Compilar
```
gradle assembleRelease
```
Requer Android SDK (plataforma 35, build-tools 35.0.0) e JDK 17+.
A assinatura usa `keystore/keystore.properties` (não versionado). Guarde o `darkcamera.jks`:
sem ele, futuras versões não instalam por cima desta.

## Instalar
Baixe o APK na página de **Releases** do repositório, abra no celular e permita
"Instalar apps desconhecidos". Depois permita câmera e microfone.

## Licença
[MIT](LICENSE): use, copie, modifique e distribua à vontade, inclusive para criar o seu próprio app.
Só mantenha o aviso de copyright e o crédito ao criador original.

---
Criador: **Rômulo Innocêncio** · [github.com/romuloinnocencio](https://github.com/romuloinnocencio)
