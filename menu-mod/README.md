# MineHouse Menu

Клиентский мод для сборки MineHouse (Fabric 26.3). Меняет главное меню:

- логотип MineHouse вместо Minecraft;
- первая кнопка «▶ Играть на MineHouse» сразу подключает к серверу, под ней онлайн
  (пинг раз в 30 секунд);
- «Одиночная игра» и «Сетевая игра» в одном ряду, вместо Realms кнопки «Сайт» и
  «Discord»;
- свои сплэши (`assets/minecraft/texts/splashes.txt`).

- автообновление сборки: при запуске сверяется с `https://www.minehouse.pro/modpack/manifest.json`,
  докачивает новые и изменённые файлы (sha512), а после выхода из игры отдельный
  процесс (`UpdateApplier`) подменяет их и удаляет файлы, которых в сборке больше
  нет. Список установленных сборкой файлов лежит в `config/minehouse-pack.json`,
  чужие моды игрока не трогаются. Без этого файла (dev-запуск) обновление выключено.

Адрес сервера и ссылки в `config/minehouse-menu.properties` (сборка кладёт его
сама, `discord.url` пустой = кнопки Discord нет).

Проверка: `xvfb-run ./gradlew runSelftestClient` открывает игру, ждёт на
титульном экране, сохраняет `run-selftest/screenshots/minehouse-menu.png` и
пишет в лог `MINEHOUSE_MENU_OK`. Логотип собран из `public/brand/logo-mark.png`
и шрифта Unbounded.
