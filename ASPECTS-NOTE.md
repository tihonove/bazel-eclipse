# Аспекты JetBrains — брать их при развитии BJLS

> Локальная заметка, не для апстрима. Найдено 2026-08-23, подробности и цифры —
> в `../../PLAN.md`, раздел «Аспекты JetBrains: добыты, и они лучше наших».

## Коротко

Аспекты, которые сейчас зашиты в BJLS (`bundles/com.salesforce.bazel.sdk/aspects/`),
запинены на `bazelbuild/intellij@1e99c4` от **апреля 2025** и на Bazel 9 не
работают вообще. Мы починили их четырьмя локальными патчами.

Тратить силы на поддержку этих патчей не надо. У JetBrains есть форк тех же
аспектов, где Bazel 9 уже сделан, он под Apache 2.0 и достаётся из плагина.

## Где взять

```bash
# plugin id 22977 «Bazel» (JetBrains) — актуальный updateId смотреть в
# https://plugins.jetbrains.com/api/plugins/22977/updates?size=3
curl -L -o bazel-plugin.zip \
  https://downloads.marketplace.jetbrains.com/files/22977/1128270/bazel-plugin-2026.2.1.1.zip
unzip -p bazel-plugin.zip \
  bazel-plugin/lib/modules/intellij.libraries.bazel.aspect.sdk.jar > sdk.jar
unzip -p sdk.jar archive_ide.zip > aspects.zip   # 48 КБ, 37 файлов Starlark
```

Уже распакованная копия лежит в `../jb-aspects/` (вместе с исходным
`aspect_ide.zip`). **Лицензия Apache 2.0**: «Copyright 2025 The Bazel Authors.
Copyright 2026 JetBrains s.r.o.» — форк тех же аспектов, брать и менять можно.

Через Maven координаты `org.jetbrains.intellij.deps.bazel:intellij-aspect-sdk`
публично **не** отдаются (проверены cache-redirector.jetbrains.com,
packages.jetbrains.team, Maven Central — 404). Только через плагин.

## Почему они лучше

| Что мы патчили в 1e99c4 | У JetBrains |
|---|---|
| `CcInfo` — голый билтин | `load("@rules_cc//cc:defs.bzl", ...)` |
| `cc_common` — голый билтин | загружается из rules_cc |
| `java_common`, `JavaInfo` | `@rules_java//java:defs.bzl`, `java/common/*` |
| `PyInfo` | `@rules_python//python:defs.bzl` |
| `--incompatible_py2_outputs_are_suffixed` | отсутствует вообще |
| `return struct()` из aspect impl | `return [intellij_info, OutputGroupInfo(...)]` |
| `aspect_tools` не собирается (404 на EAP-снапшот) | тулзы не нужны в таком виде |

Структура: `common/` (artifact_location, ide_info, version), `intellij/`
(aspect.bzl, provider.bzl), `modules/` — по файлу на язык (`java_info.bzl`,
`proto_info.bzl`, `protobuf_info.bzl`, `kotlin_info.bzl`, `cc_info.bzl`, …).

## Что придётся поменять в BJLS

Хорошая новость: это **та же линия аспектов**. Тот же output group
`intellij-info`, те же `*.intellij-info.txt` через `proto.encode_text`.
Плохая: схема переименована.

| Поле | читает BJLS сейчас | пишет JetBrains |
|---|---|---|
| вид правила | `kind_string` | `kind` |
| jar'ы | `jar`, `interface_jar`, `source_jar` | `binary_jars`, `interface_jars`, `source_jars` (списки) |
| имя файла | `<name>-<hash>.intellij-info.txt` | `<hash>.<name>.intellij-info.txt` |

Трогать придётся:
- `bundles/com.salesforce.bazel.importedsource/proto/**` — `IntellijIdeInfo.proto`;
- `com/google/idea/blaze/base/ideinfo/TargetIdeInfo.java` — `fromProto`;
- `com/salesforce/bazel/sdk/aspects/intellij/IntellijAspects.java` — раскладка
  и `ASPECTS_VERSION` (у них другой набор файлов, нет `default/manifest`,
  velocity-шаблонов тоже нет — они версионируют через `common/version.bzl`);
- `JavaAspectsInfo` — маппинг jar'ов.

## Что это снимает

- все четыре наших патча аспектов (`repos/bazel-eclipse/.../import/intellij/aspect/`);
- возможно, патч №13 про `java_proto_library`: у них есть отдельные
  `proto_info.bzl` и `protobuf_info.bzl`, выглядят полнее — надо проверить,
  кладут ли они jar'ы на сам `java_proto_library`;
- зависимость от удалённого JetBrains EAP-снапшота в `aspect_tools`.

## Риски

- Версия аспектов будет догонять релизы плагина, а не наш темп.
- Их аспекты рассчитаны на их же маппер; часть полей может не иметь аналога
  в модели BJLS — сверять по факту.
- Юридически Apache 2.0 в порядке, но происхождение (вскрытый jar из плагина)
  стоит проговорить, если пойдём в апстрим.
