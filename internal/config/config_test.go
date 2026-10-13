package config

import (
	"os"
	"strings"
	"testing"
)

// clear unsets every MNEMOS_ / SAMANOTE_ variable for the test.
func clear(t *testing.T) {
	for _, kv := range os.Environ() {
		k, _, _ := strings.Cut(kv, "=")
		if strings.HasPrefix(k, "MNEMOS_") || strings.HasPrefix(k, "SAMANOTE_") {
			t.Setenv(k, "")
			os.Unsetenv(k)
		}
	}
}

func TestSamanoteFallbackAndDefaults(t *testing.T) {
	clear(t)
	t.Setenv("SAMANOTE_NOTES", t.TempDir())
	t.Setenv("SAMANOTE_USER", "nihar")
	t.Setenv("SAMANOTE_PASSWORD", "a-real-one")
	t.Setenv("MNEMOS_AUTOSAVE_MS", "2000")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.User != "nihar" || c.Password != "a-real-one" || c.AutosaveMS != 2000 {
		t.Fatalf("fallback: %+v", c)
	}
	if !c.DemoEnabled || c.DemoUser != "demouser" || c.CaptureNote != "inbox/note.md" || c.TemplatesDir != "templates" {
		t.Fatalf("defaults: %+v", c)
	}
}

func TestRefusals(t *testing.T) {
	for name, env := range map[string]map[string]string{
		"placeholder":   {"MNEMOS_PASSWORD": "change-me"},
		"same as demo":  {"MNEMOS_PASSWORD": "a-real-one", "MNEMOS_USER": "demouser"},
		"capture leaks": {"MNEMOS_PASSWORD": "a-real-one", "MNEMOS_CAPTURE": "../x.md"},
		"no password":   {},
	} {
		t.Run(name, func(t *testing.T) {
			clear(t)
			t.Setenv("MNEMOS_NOTES", t.TempDir())
			for k, v := range env {
				t.Setenv(k, v)
			}
			if _, err := Load(); err == nil {
				t.Fatal("accepted")
			}
		})
	}
}
