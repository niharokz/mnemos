package vault

import (
	"testing"
	"time"
)

func TestAttachName(t *testing.T) {
	now := time.Date(2026, 10, 13, 9, 30, 5, 0, time.UTC)
	for in, want := range map[string]string{
		"My photo (1).JPEG":   "My-photo-1.jpg",
		"image.png":           "image-20261013-093005.jpg",
		"":                    "image-20261013-093005.jpg",
		"../../etc/passwd":    "passwd.jpg",
		`C:\Users\me\cat.png`: "cat.jpg",
		"नमस्ते.png":          "नमस्ते.jpg",
		".hidden":             "image-20261013-093005.jpg",
	} {
		if got := attachName(in, ".jpg", now); got != want {
			t.Errorf("attachName(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestRelLink(t *testing.T) {
	for _, c := range [][3]string{
		{"day.md", "attachments/a.png", "attachments/a.png"},
		{"notes/day.md", "notes/attachments/a b.png", "attachments/a%20b.png"},
		{"notes/x/day.md", "img/a.png", "../../img/a.png"},
	} {
		if got := RelLink(c[0], c[1]); got != c[2] {
			t.Errorf("RelLink(%q, %q) = %q, want %q", c[0], c[1], got, c[2])
		}
	}
}

func TestImageType(t *testing.T) {
	for in, want := range map[string]string{
		"\x89PNG\r\n\x1a\n0000":                    "image/png",
		"\xff\xd8\xff\xe0":                         "image/jpeg",
		"GIF89a":                                   "image/gif",
		"RIFF\x00\x00\x00\x00WEBPVP8 ":             "image/webp",
		"\x00\x00\x00\x1cftypavif\x00\x00\x00\x00": "image/avif",
		"<svg xmlns='http://www.w3.org/2000/svg'>": "",
		"plain text":                               "",
	} {
		if got := ImageType([]byte(in)); got != want {
			t.Errorf("ImageType(%q) = %q, want %q", in, got, want)
		}
	}
}
