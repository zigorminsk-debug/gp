# GP Guide — Windows Server Group Policy справочник

Проект собирает два приложения из одного общего HTML-справочника `windows/frontend/index.html`:

- **Android** — WebView-приложение.
- **Windows** — Go desktop-приложение на Wails с нативным WebView2, NSIS `setup.exe` и встроенной проверкой/установкой обновлений.

Содержимое руководства меняйте в `windows/frontend/index.html`: Android Gradle включает этот файл как asset, Windows встраивает его в Go-приложение. UI и поиск поэтому остаются общими для обеих платформ.

## GitHub Actions

Workflow `.github/workflows/android-release.yml` использует общий `GITHUB_RUN_NUMBER` для обеих версий: `versionCode` и суффикс `versionName` совпадают.

На push в `main` или `arena/**` Actions собирает и сохраняет на 90 дней:

- Android debug APK для проверки.
- Windows GUI `setup.exe` и файл SHA-256 для проверки загрузки.

Для публикации релиза запустите workflow вручную и установите `Publish signed Android APK and Windows setup to GitHub Releases = true`. Windows setup добавится к тому же GitHub Release, что и Android APK. Приложения ищут новый выпуск на публичной странице GitHub Releases. Windows updater скачивает setup внутри приложения, сверяет SHA-256 и запускает NSIS installer в тихом режиме; установщик настроен на per-user установку. Android использует системный установщик и требует подтверждения пользователя.

**Подпись Android-релиза:** добавьте в GitHub → Settings → Secrets and variables → Actions секреты `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Без них workflow всё равно собирает Android debug APK и Windows setup artifact, но публикация общего production Release остановится на проверке ключа.

Приватный ключ подписи не хранится в Git-файлах. Создайте его один раз в защищённой среде, сохраните резервную копию в менеджере секретов и загрузите keystore только как GitHub Actions Secret. Не меняйте ключ между Android-релизами. Windows `.exe` сейчас не Authenticode-подписан; для публичного распространения без предупреждений SmartScreen понадобится отдельный сертификат подписи кода.

## Сборка вручную

### Android

Android Studio (Gradle 8.9, AGP 8.7.3, JDK 17) либо установленный Android SDK:

```bash
gradle assembleDebug
```

### Windows

Windows 10/11, Go 1.24+, Node.js LTS, GCC/MinGW, WebView2 SDK и NSIS:

```powershell
cd windows
go mod tidy
go install github.com/wailsapp/wails/v2/cmd/wails@v2.17.1
wails build -platform windows/amd64 -nsis
```

Setup создаётся в `windows/build/bin/`. CI автоматически устанавливает необходимые Go/Wails/NSIS инструменты на Windows runner и Android SDK-компоненты на Linux runner.

## Справочник и источники

Материалы по созданию GPO, LSDOU, Security/WMI filtering, `gpresult`, `gpupdate`, приоритетам, loopback, SYSVOL/DFSR и безопасному production rollout основаны на документации Microsoft Learn:

- [Group Policy processing](https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/manage/group-policy/group-policy-processing)
- [Group Policy Management Console](https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/manage/group-policy/group-policy-management-console)
- [Applying Group Policy troubleshooting guidance](https://learn.microsoft.com/en-us/troubleshoot/windows-server/group-policy/applying-group-policy-troubleshooting-guidance)
- [Group Policy WMI filters troubleshooting](https://learn.microsoft.com/en-us/troubleshoot/windows-server/group-policy/group-policy-wmi-filters-denied-slow-logon-boot)

Примеры — практическая отправная точка; проверяйте их в своей среде и на целевых версиях Windows.
