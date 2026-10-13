// landing.js — the only script on the about page. It sends people who meant to
// open the app straight there: the installed home-screen app and old links to
// notes, folders or shortcuts (/#/n/…, /#/new …) from before the app moved to /app.
const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
if (standalone || /^#\/([nfc]\/|new$|capture$|search$)/.test(location.hash)) {
  location.replace("/app" + location.hash);
}
