# GP Guide — справочник по групповой политике Windows Server

Android-приложение на русском языке: офлайн-руководство по созданию и внедрению GPO, проверке результата, диагностике типовых ошибок и production rollout. В приложении есть поиск по справочнику и встроенная проверка GitHub Releases: APK скачивается в закрытое хранилище приложения и передаётся системному установщику Android, без браузера.

## Сборка Android

Откройте проект в Android Studio (Gradle 8.9, Android Gradle Plugin 8.7.3, JDK 17) либо в окружении с Android SDK:

```bash
gradle assembleDebug
```

APK появится в `app/build/outputs/apk/debug/`.

## Релизы и автообновление

GitHub Actions на push в `main` или `arena/**` собирает debug APK для проверки проекта. `versionCode` — порядковый `GITHUB_RUN_NUMBER`, `versionName` — `1.0.<номер запуска>`. Артефакт CI хранится 90 дней. Подписанный release APK собирается при ручном запуске workflow с включённой публикацией и только после настройки секретов подписи.

Чтобы опубликовать версию для встроенного обновлятора, запустите workflow вручную и задайте `Publish this signed build as a GitHub Release for in-app updates = true`. Action создаст GitHub Release с тегом `v1.0.<номер>` и APK. Приложение автоматически проверяет последний опубликованный Release при запуске; пользователь также может запустить проверку кнопкой. Для обновлений репозиторий и GitHub Releases должны быть публичными: персональный токен GitHub в приложение не встраивается.

**Ограничение Android:** sideload-приложение не может незаметно установить обновление само. После загрузки APK внутри приложения Android попросит разрешить установку неизвестных приложений для GP Guide и показать системный диалог установки; пользователь подтверждает обновление. Это не скачивание в браузер, но файл APK загружается приложением во внутреннее хранилище для системного установщика. Полностью автоматические установки доступны через Google Play In-App Updates либо для устройств, которыми управляет MDM/device-owner.

## Безопасное хранение ключа подписи

Я не добавляю приватный keystore в Git-репозиторий: такой ключ нельзя публиковать в исходниках. Украденный ключ позволяет подписывать вредоносные обновления от имени приложения; раскрытие его в истории Git невозможно надёжно отменить. Workflow получает постоянный ключ только из GitHub Actions Secrets.

Добавьте в GitHub → Settings → Secrets and variables → Actions:

- `ANDROID_KEYSTORE_BASE64` — keystore в Base64 (`base64 -w 0 release.jks` на Linux; `base64 < release.jks | tr -d '\n'` на macOS).
- `ANDROID_KEYSTORE_PASSWORD` — пароль keystore.
- `ANDROID_KEY_ALIAS` — alias.
- `ANDROID_KEY_PASSWORD` — пароль ключа.

Создайте keystore один раз в защищённой среде и сохраните зашифрованную резервную копию, например:

```bash
keytool -genkeypair -v -keystore release.jks -alias gp-guide \
  -keyalg RSA -keysize 3072 -validity 10000
```

Добавляйте в GitHub только его Base64-значение как Secret, не в файлы/логи. Не меняйте keystore между выпусками: Android примет обновление только если подпись совместима с установленным приложением. Потеря ключа может сделать обновление установленных sideload-копий невозможным. Для Google Play включите Play App Signing и отдельно сохраните upload key.

## Источники

- [Group Policy processing](https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/manage/group-policy/group-policy-processing)
- [Group Policy Management Console](https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/manage/group-policy/group-policy-management-console)
- [Applying Group Policy troubleshooting guidance](https://learn.microsoft.com/en-us/troubleshoot/windows-server/group-policy/applying-group-policy-troubleshooting-guidance)
- [Group Policy WMI filters troubleshooting](https://learn.microsoft.com/en-us/troubleshoot/windows-server/group-policy/group-policy-wmi-filters-denied-slow-logon-boot)
- [Group Policy error events 7016/1091/1202](https://learn.microsoft.com/en-us/troubleshoot/windows-server/group-policy/group-policy-error-7016-1091-1202)

Справочник — практическая отправная точка, а не замена проверке в вашей среде и официальной документации конкретной версии Windows Server/client.
