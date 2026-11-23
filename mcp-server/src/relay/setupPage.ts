import type { SetupEntry } from "../core/setupLinks.js";

/**
 * The setup page a new phone opens from the relay's one-time link (or its QR code).
 * Plain HTML, no scripts from elsewhere, Portuguese or English from Accept-Language.
 */
export const RELEASES_URL = "https://github.com/gabrielalberton/droidhand/releases/latest";

/** What the app opens: the invite in a URL fragment, the same format as the QR invite. */
export function appLink(e: Pick<SetupEntry, "relayUrl" | "token" | "name">): string {
  return `droidhand://connect#relay=${e.relayUrl}&token=${e.token}&name=${encodeURIComponent(e.name)}`;
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

const T = {
  pt: {
    title: "Configurar o DroidHand",
    hello: (n: string) => `Este link configura <b>${n}</b>. Siga na ordem, neste celular.`,
    s1: "Baixe e instale o app",
    s1b: "Abra a página, toque em <b>droidhand-…apk</b> e instale (permita instalar pelo navegador quando o Android pedir).",
    dl: "Baixar o DroidHand",
    s2: "Conecte o app ao computador",
    s2b: "Com o app instalado, toque no botão. Ele abre o DroidHand já com o endereço e a chave.",
    open: "Abrir no DroidHand",
    s3: "Dê as permissões",
    s3b: "Na tela inicial do app, toque em cada item de <b>Permissões</b> (acessibilidade, notificações, bateria).",
    s4: "Pareie o ADB pelo app (opcional)",
    s4b: "Em <b>Permissões → Configurações seguras → Parear pelo app</b>. Precisa de Wi-Fi e da depuração sem fio; o app lê o código sozinho e o computador conclui o resto.",
    note: "Este link vale uma vez: deixa de funcionar quando este celular conectar, ou em 24 h. Não compartilhe.",
    gone: "Este link de configuração não vale mais (já foi usado, expirou ou o computador reiniciou). Peça um novo.",
  },
  en: {
    title: "Set up DroidHand",
    hello: (n: string) => `This link sets up <b>${n}</b>. Follow the steps in order, on this phone.`,
    s1: "Download and install the app",
    s1b: "Open the page, tap <b>droidhand-…apk</b> and install it (allow installs from the browser when Android asks).",
    dl: "Download DroidHand",
    s2: "Connect the app to the computer",
    s2b: "With the app installed, tap the button. It opens DroidHand with the address and key filled in.",
    open: "Open in DroidHand",
    s3: "Grant the permissions",
    s3b: "On the app's home screen, tap each row under <b>Permissions</b> (accessibility, notifications, battery).",
    s4: "Pair ADB from the app (optional)",
    s4b: "In <b>Permissions → Secure settings → Pair from the app</b>. Needs Wi-Fi and wireless debugging; the app reads the code itself and the computer does the rest.",
    note: "This link works once: it stops working when this phone connects, or after 24 h. Do not share it.",
    gone: "This setup link is no longer valid (already used, expired, or the computer restarted). Ask for a new one.",
  },
};

export function pickLang(acceptLanguage: string | undefined): "pt" | "en" {
  return /^\s*pt/i.test(acceptLanguage ?? "") ? "pt" : "en";
}

const CSS = `
:root{--bg:#f6f7f9;--fg:#15171a;--card:#fff;--mut:#5b6270;--acc:#2f6fed;--line:#e3e6eb}
@media (prefers-color-scheme:dark){:root{--bg:#111316;--fg:#eceef1;--card:#1a1d21;--mut:#9aa1ad;--acc:#7aa5ff;--line:#2a2e34}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:24px 16px 40px}h1{font-size:24px;margin:0 0 8px}
p{margin:6px 0}.mut{color:var(--mut);font-size:14px}
ol{list-style:none;padding:0;margin:20px 0;counter-reset:s}
li{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin:0 0 12px;counter-increment:s}
li h2{font-size:17px;margin:0 0 4px}li h2::before{content:counter(s) ". ";color:var(--acc)}
a.btn{display:block;text-align:center;background:var(--acc);color:#fff;text-decoration:none;font-weight:600;border-radius:10px;padding:12px;margin-top:10px}
`;

function shell(lang: string, title: string, body: string): string {
  return `<!doctype html><html lang="${lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
    `<meta name="referrer" content="no-referrer"><meta name="robots" content="noindex"><title>${title}</title><style>${CSS}</style></head><body><main>${body}</main></body></html>`;
}

export function renderSetupPage(e: SetupEntry, lang: "pt" | "en"): string {
  const t = T[lang];
  return shell(lang, t.title, `<h1>${t.title}</h1><p>${t.hello(esc(e.name))}</p><ol>` +
    `<li><h2>${t.s1}</h2><p>${t.s1b}</p><a class="btn" href="${RELEASES_URL}" rel="noreferrer">${t.dl}</a></li>` +
    `<li><h2>${t.s2}</h2><p>${t.s2b}</p><a class="btn" href="${esc(appLink(e))}">${t.open}</a></li>` +
    `<li><h2>${t.s3}</h2><p>${t.s3b}</p></li>` +
    `<li><h2>${t.s4}</h2><p>${t.s4b}</p></li></ol><p class="mut">${t.note}</p>`);
}

export function renderGonePage(lang: "pt" | "en"): string {
  const t = T[lang];
  return shell(lang, t.title, `<h1>${t.title}</h1><p>${t.gone}</p>`);
}
