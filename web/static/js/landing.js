// landing.js — the only script on the about page. It sends people who meant to
// open the app straight there: the installed home-screen app (older installs
// started at "/") and old note links (/#/n/…) from before the app moved to /app.
const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
if (standalone || location.hash.startsWith("#/n/") || location.hash === "#/new") {
  location.replace("/app" + location.hash);
}
