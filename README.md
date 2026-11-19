# Ritmo

Aplicativo Android de listas e tarefas em português do Brasil. Organize tarefas em listas e sublistas, adicione etiquetas, anexos, notas formatadas e lembretes, e alterne entre lista e cards. Tarefas concluídas ficam riscadas no lugar. Deslize uma tarefa para arquivá-la e use **Arquivadas** para consultá-la ou restaurá-la.

## Instalação

Requer Android 8.0 (API 26) ou superior. Nas releases de teste, baixe o APK identificado como `debug` em **Assets** e abra-o no aparelho. Uma atualização instalada sobre outra cópia do Ritmo precisa usar a mesma chave de assinatura.

## Ditado e dados locais

O ditado usa Vosk e pede acesso ao microfone quando acionado. No primeiro uso, o app baixa um modelo em português de cerca de 31 MB. Depois disso, o reconhecimento funciona offline. O áudio é processado no aparelho e o texto fica no campo para revisão. [Decisão técnica e fonte do modelo](DECISOES-DITADO.md).

O app funciona sem conta. O Room guarda as tarefas e listas no aparelho. Os backups `.ritmo` são arquivos ZIP, gravados na pasta escolhida pelo usuário, com as tarefas, a ordem, o estado de arquivamento, as etiquetas e os anexos. A versão atual grava o formato 4 e aceita ZIP v3 e v4, além do JSON legado v1 e v2. O backup automático usa o WorkManager e o Android determina o horário efetivo da execução.

## Desenvolvimento

Use um JDK 17 completo (com `javac` e `jlink`), o Android SDK com a plataforma 35 e o Gradle Wrapper incluído no projeto. Configure `JAVA_HOME` e `ANDROID_HOME` para a sua instalação ou abra a pasta no Android Studio.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No Windows, use `gradlew.bat`. O primeiro build precisa de acesso aos repositórios de dependências. Os esquemas do Room ficam em `app/schemas` e fazem parte do código publicado.

## Publicação no GitHub

Para um repositório novo, crie-o vazio, escolha a visibilidade e use a URL dele como `origin`. Confira a lista de arquivos antes do commit. Se já existir um repositório, revise os arquivos rastreados e o histórico antes de publicar, pois o `.gitignore` só impede a inclusão de arquivos ainda não rastreados.

```sh
git init -b main
git add --dry-run .
git add .
git diff --cached --name-only
git commit -m "Prepara Ritmo 1.7"
git remote add origin https://github.com/SEU-USUARIO/SEU-REPOSITORIO.git
git push -u origin main
```

Em **Releases**, crie um rascunho com a tag `v1.7` apontando para o commit publicado. Use as [notas da v1.7](RELEASE-v1.7.md), anexe o APK de teste e o arquivo `SHA256SUMS` e marque como pré-release. Os arquivos binários ficam nos anexos da release. A validação em aparelho cobre o primeiro download do modelo, o ditado em modo avião, os gestos da lista e a restauração de backup.
