# Início — launcher pessoal (Android)

Launcher simples e meu: tela inicial só com o que eu escolho, pastas, ocultar apps,
papel de parede próprio por orientação e um menu lateral estilo Iniciar do Windows 11.
Sem widgets na v1.

## Gerar o APK (GitHub Actions, sem PC)

1. Crie um repositório vazio no GitHub e suba todos os arquivos deste projeto
   (mantendo as pastas, principalmente `.github/workflows/build.yml`).
2. A cada push na branch `main`, a aba **Actions** roda "Gerar APK".
   Ao terminar, abra a execução e baixe o artefato **Inicio-apk** (um .zip com o `app-debug.apk`).
3. Para baixar o APK direto, sem zip: crie a tag `v1.0` (Releases > Draft a new release >
   tag `v1.0`). O workflow anexa o APK à release.

## Instalar e ativar

1. Abra o APK no celular e permita "instalar de fontes desconhecidas" para o navegador/arquivos.
2. Abra o app **Início**. Ajustes (engrenagem) > **Definir como launcher padrão**.
   Ou: Configurações > Apps > Apps padrão > App de início > Início.
3. Para voltar ao launcher da Xiaomi, escolha-o no mesmo lugar.

## Como usar

- **Botão Iniciar** (embaixo, centro) ou deslizar para a direita num espaço vazio: abre o menu lateral.
- Menu lateral: pesquisa, **Fixados**, lista A–Z. Segure um app para: adicionar à tela inicial,
  adicionar à pasta, fixar, ocultar, informações, desinstalar.
- Tela inicial: toque abre; **segure e arraste** para reordenar. Durante o arraste aparece a barra
  no topo: solte em **Remover** ou **Informações/Renomear**. Solte um app sobre uma pasta para guardá-lo.
- Segurar num espaço vazio ou tocar na engrenagem: Ajustes.
- **Papel de parede**: sistema, ou imagem própria para retrato e para paisagem. Se faltar uma das duas,
  a outra é usada (cortada, nunca girada).
- **Rotação**: seguir o sistema, sempre girar, travar retrato ou travar paisagem.
- **Backup**: exportar/importar o layout em arquivo (as imagens de fundo não entram no backup).

## Limitações da v1

- Sem widgets.
- Apps ocultos só aparecem em Ajustes > Apps ocultos (desmarque para voltar).
- Tela inicial única, com rolagem (sem várias páginas).
