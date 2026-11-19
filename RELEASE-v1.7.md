# Ritmo 1.7

Pré-release de teste para Android 8.0 ou superior (`versionCode 8`).

- Ditado em português dentro do app. O primeiro uso baixa o modelo Vosk e os seguintes funcionam offline. O texto fica no campo para revisão.
- Tarefas concluídas ficam riscadas na posição em que estavam. Deslize para arquivar ou restaure na tela **Arquivadas**.
- Reordenação por arraste com animação dos itens vizinhos, ícones com dica por toque longo e ajustes nas abas, notas e subtarefas.
- O backup `.ritmo` agora preserva o estado de arquivamento. A atualização do banco mantém as tarefas existentes.

O APK de teste usa assinatura de debug. Os seis testes unitários passaram e a migração 3→4 foi conferida em SQLite. O microfone e os gestos ainda precisam de validação em aparelho.
