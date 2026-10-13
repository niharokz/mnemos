package vault

import (
	"bytes"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// AttachDir is the folder, next to a note, that its images go into. The Android
// app uses the same rule, so a note and its images move together.
const AttachDir = "attachments"

// ErrNotImage is returned when uploaded bytes are not one of the image formats below.
var ErrNotImage = errors.New("only PNG, JPEG, GIF, WebP and AVIF images can be added")

// imageExt maps a sniffed content type to the extension the file is saved with.
var imageExt = map[string]string{
	"image/png":  ".png",
	"image/jpeg": ".jpg",
	"image/gif":  ".gif",
	"image/webp": ".webp",
	"image/avif": ".avif",
}

// ImageType sniffs the bytes (never trusting a name or a header) and returns the
// content type, or "" when it is not a supported image. SVG is left out on purpose:
// it can carry scripts.
func ImageType(b []byte) string {
	if len(b) >= 12 && string(b[4:8]) == "ftyp" && (string(b[8:12]) == "avif" || string(b[8:12]) == "avis") {
		return "image/avif" // not known to http.DetectContentType
	}
	ct := http.DetectContentType(b)
	if _, ok := imageExt[ct]; ok {
		return ct
	}
	return ""
}

var unsafeName = regexp.MustCompile(`[^\p{L}\p{M}\p{N}._-]+`)

// attachName turns a suggested file name into a safe one with the right extension:
// "My photo (1).JPEG" → "My-photo-1.jpg"; an empty or generic name gets a time stamp.
func attachName(suggested, ext string, now time.Time) string {
	base := strings.TrimSuffix(path.Base(strings.ReplaceAll(suggested, "\\", "/")), path.Ext(suggested))
	base = strings.Trim(unsafeName.ReplaceAllString(base, "-"), "-.")
	switch strings.ToLower(base) {
	case "", "image", "blob", "pasted", "clipboard": // what browsers call pasted images
		base = "image-" + now.Format("20060102-150405")
	}
	if r := []rune(base); len(r) > 80 {
		base = string(r[:80])
	}
	return base + ext
}

// SaveAttachment stores an image for the note at notePath in "<note folder>/attachments/"
// and returns the vault path of the new file. The name is made safe and never
// overwrites: "photo.jpg" becomes "photo 2.jpg" when taken.
func (v *Vault) SaveAttachment(notePath, suggested string, data []byte) (string, error) {
	ct := ImageType(data)
	if ct == "" {
		return "", ErrNotImage
	}
	note, err := v.Clean(notePath)
	if err != nil {
		return "", err
	}
	dir := path.Join(path.Dir(note), AttachDir)
	if path.Dir(note) == "." {
		dir = AttachDir
	}
	name := attachName(suggested, imageExt[ct], time.Now())

	v.mu.Lock()
	defer v.mu.Unlock()
	clean, abs, err := v.Resolve(path.Join(dir, name))
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
		return "", perm(err, "create", dir)
	}
	stem, ext := strings.TrimSuffix(clean, path.Ext(clean)), path.Ext(clean)
	for n := 2; ; n++ {
		if _, err := os.Lstat(abs); errors.Is(err, os.ErrNotExist) {
			break
		}
		if n > 999 {
			return "", ErrExists
		}
		if clean, abs, err = v.Resolve(fmt.Sprintf("%s %d%s", stem, n, ext)); err != nil {
			return "", err
		}
	}
	return clean, perm(atomicWrite(abs, data, 0o644), "write", clean)
}

// RelLink is the markdown link from the note's folder to target, with spaces and
// other special characters escaped so it works in any markdown renderer.
func RelLink(notePath, target string) string {
	from := path.Dir(notePath)
	rel := target
	if from != "." && strings.HasPrefix(target, from+"/") {
		rel = strings.TrimPrefix(target, from+"/")
	} else if from != "." {
		rel = strings.Repeat("../", strings.Count(from, "/")+1) + target
	}
	var b bytes.Buffer
	for i, part := range strings.Split(rel, "/") {
		if i > 0 {
			b.WriteByte('/')
		}
		b.WriteString(strings.NewReplacer(" ", "%20", "(", "%28", ")", "%29", "<", "%3C", ">", "%3E").Replace(part))
	}
	return b.String()
}
