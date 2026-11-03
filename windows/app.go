package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

const releasesAPI = "https://api.github.com/repos/zigorminsk-debug/gp/releases/latest"

const maxInstallerSize = 150 * 1024 * 1024

type App struct {
	ctx context.Context
}

type UpdateStatus struct {
	Available bool   `json:"available"`
	Version   string `json:"version"`
	Message   string `json:"message"`
}

type releaseInfo struct {
	TagName string `json:"tag_name"`
	Assets  []struct {
		Name               string `json:"name"`
		BrowserDownloadURL string `json:"browser_download_url"`
	} `json:"assets"`
}

func NewApp() *App { return &App{} }

func (a *App) startup(ctx context.Context) { a.ctx = ctx }

func (a *App) GetVersionName() string { return versionName }

func (a *App) CheckForUpdates() UpdateStatus {
	release, err := latestRelease()
	if err != nil {
		return UpdateStatus{Message: "Не удалось проверить обновления GitHub. Проверьте интернет и повторите позже."}
	}
	latestCode := codeFromTag(release.TagName)
	installedCode, _ := strconv.ParseInt(versionCode, 10, 64)
	if latestCode <= installedCode {
		return UpdateStatus{Message: "Установлена актуальная версия " + versionName + "."}
	}
	if _, _, err := windowsAssets(release); err != nil {
		return UpdateStatus{Version: release.TagName, Message: "Релиз " + release.TagName + " найден, но Windows setup или SHA-256 не опубликованы."}
	}
	return UpdateStatus{
		Available: true,
		Version:   release.TagName,
		Message:   "Доступно обновление " + release.TagName + ". Нажмите «Загрузить и установить». Дистрибутив загрузится внутри программы.",
	}
}

func (a *App) InstallAvailableUpdate() UpdateStatus {
	release, err := latestRelease()
	if err != nil {
		return UpdateStatus{Message: "Не удалось получить данные релиза. Проверьте интернет и повторите позже."}
	}
	latestCode := codeFromTag(release.TagName)
	installedCode, _ := strconv.ParseInt(versionCode, 10, 64)
	if latestCode <= installedCode {
		return UpdateStatus{Message: "Установлена актуальная версия " + versionName + "."}
	}
	installerURL, checksumURL, err := windowsAssets(release)
	if err != nil {
		return UpdateStatus{Message: "В релизе нет подходящего Windows setup и контрольной суммы."}
	}

	installer, err := downloadAsset(installerURL, maxInstallerSize)
	if err != nil {
		return UpdateStatus{Message: "Не удалось загрузить Windows setup. Попробуйте позже."}
	}
	checksumFile, err := downloadAsset(checksumURL, 1024)
	if err != nil {
		return UpdateStatus{Message: "Не удалось проверить контрольную сумму обновления."}
	}
	fields := strings.Fields(strings.TrimPrefix(string(checksumFile), "\ufeff"))
	if len(fields) == 0 {
		return UpdateStatus{Message: "Контрольная сумма обновления имеет неверный формат."}
	}
	sum := sha256.Sum256(installer)
	if !strings.EqualFold(fields[0], hex.EncodeToString(sum[:])) {
		return UpdateStatus{Message: "Контрольная сумма setup не совпала. Установка отменена."}
	}
	if len(installer) < 2 || installer[0] != 'M' || installer[1] != 'Z' {
		return UpdateStatus{Message: "Загруженный файл не является Windows-приложением. Установка отменена."}
	}

	file, err := os.CreateTemp("", "gp-guide-update-*-setup.exe")
	if err != nil {
		return UpdateStatus{Message: "Не удалось сохранить установщик во временную папку Windows."}
	}
	installerPath := file.Name()
	if _, err = file.Write(installer); err != nil {
		file.Close()
		os.Remove(installerPath)
		return UpdateStatus{Message: "Не удалось записать установщик."}
	}
	if err = file.Close(); err != nil {
		os.Remove(installerPath)
		return UpdateStatus{Message: "Не удалось подготовить установщик."}
	}

	cmd := exec.Command(installerPath, "/S")
	if err = cmd.Start(); err != nil {
		os.Remove(installerPath)
		return UpdateStatus{Message: "Windows не смог запустить установщик обновления."}
	}
	go func() {
		_ = cmd.Wait()
		_ = os.Remove(installerPath)
	}()
	go func() {
		time.Sleep(1200 * time.Millisecond)
		runtime.Quit(a.ctx)
	}()
	return UpdateStatus{Version: release.TagName, Message: "Установщик запущен. GP Guide закроется, чтобы Windows мог заменить файлы приложения."}
}

func latestRelease() (releaseInfo, error) {
	client := &http.Client{Timeout: 20 * time.Second}
	req, err := http.NewRequest(http.MethodGet, releasesAPI, nil)
	if err != nil {
		return releaseInfo{}, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "GP-Guide-Windows-Updater")
	resp, err := client.Do(req)
	if err != nil {
		return releaseInfo{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return releaseInfo{}, fmt.Errorf("GitHub API returned %s", resp.Status)
	}
	var release releaseInfo
	if err := json.NewDecoder(io.LimitReader(resp.Body, 2*1024*1024)).Decode(&release); err != nil {
		return releaseInfo{}, err
	}
	return release, nil
}

func windowsAssets(release releaseInfo) (installerURL, checksumURL string, err error) {
	for _, asset := range release.Assets {
		if strings.HasSuffix(strings.ToLower(asset.Name), "-windows-setup.exe") &&
			strings.HasPrefix(asset.BrowserDownloadURL, "https://github.com/") {
			installerURL = asset.BrowserDownloadURL
		}
		if strings.HasSuffix(strings.ToLower(asset.Name), "-windows-setup.exe.sha256") &&
			strings.HasPrefix(asset.BrowserDownloadURL, "https://github.com/") {
			checksumURL = asset.BrowserDownloadURL
		}
	}
	if installerURL == "" || checksumURL == "" {
		return "", "", fmt.Errorf("Windows installer assets not found")
	}
	return installerURL, checksumURL, nil
}

func downloadAsset(url string, limit int64) ([]byte, error) {
	if !strings.HasPrefix(url, "https://github.com/") {
		return nil, fmt.Errorf("unexpected download host")
	}
	client := &http.Client{Timeout: 5 * time.Minute}
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "GP-Guide-Windows-Updater")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("download returned %s", resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > limit {
		return nil, fmt.Errorf("download exceeds size limit")
	}
	return body, nil
}

func codeFromTag(tag string) int64 {
	parts := strings.Split(strings.TrimPrefix(strings.TrimPrefix(tag, "v"), "V"), ".")
	if len(parts) == 0 {
		return -1
	}
	code, err := strconv.ParseInt(parts[len(parts)-1], 10, 64)
	if err != nil {
		return -1
	}
	return code
}

