# MineHouse Events Editor

Клиентский Fabric-мод: редактор шоу для [MineHouse Events](../events-mod/README.md). Входит в сборку
MineHouse (`modpack/minehouse-events-editor.jar`), но работает только у админов: сервер говорит с
ним, только если у игрока есть право `minehouse.events.command` (или оператор 2+).

Сервер хранит шоу и проигрывает предпросмотр, мод только показывает и отправляет правки (канал
`minehouse:show_editor`, по одному JSON на пакет). Как пользоваться — в README серверного мода,
раздел «Редактор».

```
EditorClient     состояние сессии с сервера, выбор, отправка операций
EditorScreen     таймлайн и панель свойств
ShowPickerScreen K вне редактора: выбрать или создать шоу
CueSchema        поля и значения по умолчанию для каждого действия и актёра
WorldOverlay     сцена, места и пути камеры в мире
EditorHud        строка сверху, когда панель спрятана
```

## Сборка

```
./gradlew build                          # jar в build/libs
xvfb-run ./gradlew runSelftestClient     # клиент с выдуманным шоу, скриншот редактора
cp build/libs/minehouse-events-editor-*.jar ../modpack/minehouse-events-editor.jar
```
