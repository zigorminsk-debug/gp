package main

import (
	"embed"
	"log"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

//go:embed all:frontend/dist
var assets embed.FS

// These values are filled by GitHub Actions with the same run number as the Android build.
var versionName = "1.0.1"
var versionCode = "1"

func main() {
	app := NewApp()
	err := wails.Run(&options.App{
		Title:  "GP Guide",
		Width:  1100,
		Height: 850,
		MinWidth: 720,
		MinHeight: 640,
		AssetServer: &assetserver.Options{Assets: assets},
		BackgroundColour: &options.RGBA{R: 8, G: 17, B: 31, A: 1},
		OnStartup: app.startup,
		Bind: []interface{}{app},
	})
	if err != nil {
		log.Fatal(err)
	}
}
