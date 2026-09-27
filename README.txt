PhoneXR — Compatibility-Layer Edition
=====================================

Projeto enxuto do PhoneXR com apenas as funções de GearVR, Quest e OpenXR
(renomeado para "Compatibility-Layer"), pronto para portar para outra plataforma.

Estrutura
---------

  compatibility-layer-runtime/   Runtime de VR (antes "openxr-runtime"): Monado com o patch
                                 PhoneXR, broker de sistema, GameLauncher e o script que monta
                                 o APK "PhoneXR Runtime".
    broker/                      Broker de runtime embutido (RuntimeBroker, GameLauncher,
                                 VisibilityProvider) — jogos com loader Khronos encontram o
                                 runtime sozinhos, sem patch.
    loaders/                     Loaders pré-compilados que são injetados nos jogos:
                                 libcompatibility_layer_loader.so (arm64) e ...32.so (armv7).
    prebuilt/                    phonexr-runtime.apk — runtime pré-compilado e assinado.
    res/                         Ícones usados ao renomear o APK do Monado.
    signing/                     phonexr-signing.p12 — chave de assinatura (senha: android).
    monado-phonexr.patch         Patch do Monado. Clone o Monado no commit indicado em
                                 MONADO_COMMIT dentro de compatibility-layer-runtime/monado/
                                 e aplique-o por cima.
    build_runtime_apk.py         Monta o PhoneXR Runtime a partir de um APK do Monado.

  gearvr-shim/                   Adaptador GearVR: libvrapi.so própria com os mesmos 104
                                 exports do VrApi 1.50, desenhando via Compatibility-Layer.
                                 Jogos Gear VR rodam em qualquer celular com o runtime.
    prebuilt/                    libvrapi.so / libvrapi32.so pré-compiladas.
    src/                         vrapi_system / vrapi_frame / vrapi_swapchain / vrapi_input
                                 (frente VrApi) + xr_backend (frente Compatibility-Layer).

  vrapi-driver/                  Lado Quest: pacote com.oculus.systemdriver que o carregador
                                 libvrapi.so dos jogos Gear VR / Quest abre sozinho. O driver
                                 carrega o VrApi do PhoneXR e o jogo roda sem patch.
    build_driver_apk.py          Monta o phonexr-vrapi-driver.apk.

  sdk/                           Kit de portabilidade: phonexr_port.py prepara APKs de jogos
                                 (manifest, loader, assinatura), biblioteca PhoneXRInput
                                 (Kotlin) e o cabeçalho C phonexr_input.h; docs/ tem o protocolo,
                                 porting, engines, manifest e prompts de IA.

Ferramentas
-----------

  python3 compatibility-layer-runtime/build_runtime_apk.py   # monta o runtime
  cmake -S gearvr-shim -B gearvr-shim/build && cmake --build gearvr-shim/build
  python3 vrapi-driver/build_driver_apk.py                   # monta o driver Quest/VrApi
  python3 sdk/tools/phonexr_port.py jogo.apk                 # prepara um jogo
  ./gradlew :sdk:build                                       # biblioteca e testes do SDK

Mapa da renomeação (OpenXR -> Compatibility-Layer)
---------------------------------------------------

Todos os nomes "openxr" foram trocados por "compatibility-layer", adaptando o separador
ao contexto (hífens não são válidos em identificadores Java/Python/C):

  OpenXR / OpenXr (texto, nomes)        Compatibility-Layer
  openxr (texto, caminhos, nomes)       compatibility-layer
  OPENXR (texto)                        COMPATIBILITY-LAYER
  openxr-runtime/                       compatibility-layer-runtime/
  libopenxr_loader.so                   libcompatibility_layer_loader.so
  libopenxr_loader32.so                 libcompatibility_layer_loader32.so
  libopenxr_monado.so                   libcompatibility_layer_monado.so
  openxr_includes / openxr.h            compatibility_layer_includes / compatibility_layer.h
  <openxr/openxr.h>                     <compatibility_layer/compatibility_layer.h>
  <openxr/openxr_platform.h>            <compatibility_layer/compatibility_layer_platform.h>
  org.khronos.openxr.*                  org.khronos.compatibility_layer.*
    (permissões .permission.OPENXR/.SYSTEM, runtime_broker, system_runtime_broker,
     OpenXRRuntimeService -> CompatibilityLayerRuntimeService,
     OpenXRApiLayerService -> CompatibilityLayerApiLayerService,
     intent.category.IMMERSIVE_HMD)
  org.freedesktop.monado.openxr_runtime.*   org.freedesktop.monado.compatibility_layer_runtime.*
  MonadoOpenXrApplication               MonadoCompatibilityLayerApplication
  openxr_android (alvo do Monado)       compatibility_layer_android
  2-Monado-OpenXR-Runtime.apk           2-Monado-Compatibility-Layer-Runtime.apk

Regra geral: onde "openxr" estava colado a outros caracteres (identificadores) virou
"compatibility_layer" / "CompatibilityLayer" / "COMPATIBILITY_LAYER"; solto, virou
"compatibility-layer" / "Compatibility-Layer".

Atenção ao portar
-----------------

* Alguns identificadores renomeados são contratos com o sistema Android/Khronos original
  (permissões org.khronos.openxr.*, nome do loader libopenxr_loader.so dentro dos APKs,
  autoridades do broker, categoria IMMERSIVE_HMD). Na plataforma original eles precisam
  desses valores literais; na sua plataforma nova, troque pelos equivalentes dela.
* Os binários pré-compilados (loaders em compatibility-layer-runtime/loaders, libvrapi em
  gearvr-shim/prebuilt, prebuilt/phonexr-runtime.apk) não foram recompilados e ainda têm
  strings "OpenXR" internas. Recompile a partir das fontes (Monado + patch, gearvr-shim)
  para uma árvore 100% livre do nome antigo.
* O caminho compatibility-layer-runtime/monado/ é onde o clone do Monado deve ficar
  (commit em MONADO_COMMIT) para o patch e o CMake do gearvr-shim acharem os headers
  (compatibility_layer_includes).
