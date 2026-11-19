# Ditado por voz no Ritmo

O ditado usa Vosk com o modelo pequeno `vosk-model-small-pt-0.3`, processado no próprio aparelho. Na primeira utilização, o app precisa de internet para baixar o modelo (aproximadamente 31 MB compactados); ele fica na pasta privada do app para os usos seguintes, inclusive em modo avião. O arquivo do modelo não é incluído no APK para manter a atualização leve.

O microfone permanece dentro da tela de edição. O botão encerra o reconhecimento e deixa o texto no campo para revisão; ditar nunca envia a tarefa automaticamente. O reconhecimento usa áudio mono PCM de 16 kHz e libera modelo e gravador ao terminar.

Fonte/licença do modelo: Vosk `vosk-model-small-pt-0.3`, Apache 2.0 — https://alphacephei.com/vosk/models
