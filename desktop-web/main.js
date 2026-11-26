const { app, BrowserWindow, ipcMain, session, shell } = require('electron');
const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const path = require('node:path');

const ALLOWED_EXTERNAL_PROTOCOLS = new Set(['https:']);
const ALLOWED_GOOGLE_HOSTS = new Set([
  'drive.google.com',
  'docs.google.com',
  'accounts.google.com'
]);
const BLOCKED_EXTENSIONS = new Set([
  'exe', 'apk', 'dex', 'jar', 'so', 'sh', 'bat', 'cmd', 'js', 'vbs', 'ps1', 'msi', 'elf', 'scr', 'com'
]);
const MAX_DRIVE_DOWNLOAD_BYTES = 15 * 1024 * 1024;
const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

let mainWindow = null;
let staticServer = null;

function sanitizeUserAgent(rawUa) {
  return String(rawUa || '')
    .replace(/\sElectron\/[^\s]+/gi, '')
    .replace(/\sunicore-desktop-web\/[^\s]+/gi, '')
    .trim();
}

function isAllowedGoogleDownloadHost(hostname) {
  const lower = String(hostname || '').toLowerCase();
  if (ALLOWED_GOOGLE_HOSTS.has(lower)) return true;
  return lower.endsWith('.googleusercontent.com') || lower.endsWith('.googlevideo.com');
}

function validateHttpsUrl(rawUrl) {
  if (typeof rawUrl !== 'string' || !rawUrl.trim()) return null;
  try {
    const parsed = new URL(rawUrl.trim());
    if (!ALLOWED_EXTERNAL_PROTOCOLS.has(parsed.protocol)) return null;
    if (['localhost', '127.0.0.1', '::1', '169.254.169.254'].includes(parsed.hostname.toLowerCase())) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

function startStaticDistServer(distDir) {
  return new Promise((resolve, reject) => {
    const resolvedDist = path.resolve(distDir);
    const server = http.createServer((req, res) => {
      try {
        const reqUrl = new URL(req.url || '/', 'http://127.0.0.1');
        let relPath = decodeURIComponent(reqUrl.pathname || '/');
        if (relPath === '/') relPath = '/index.html';
        const safeRel = relPath.replace(/^\/+/, '');
        let targetPath = path.resolve(resolvedDist, safeRel);
        if (!targetPath.startsWith(resolvedDist + path.sep) && targetPath !== resolvedDist) {
          res.writeHead(403);
          res.end('Forbidden');
          return;
        }
        if (!fs.existsSync(targetPath) || fs.statSync(targetPath).isDirectory()) {
          targetPath = path.join(resolvedDist, 'index.html');
        }
        const ext = path.extname(targetPath).toLowerCase();
        res.writeHead(200, {
          'Content-Type': MIME_TYPES[ext] || 'application/octet-stream',
          'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=86400'
        });
        fs.createReadStream(targetPath).pipe(res);
      } catch {
        res.writeHead(500);
        res.end('Server error');
      }
    });

    server.on('error', reject);
    // Bind strictly to loopback; open via localhost:<port> so Firebase Auth recognizes localhost origin
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      staticServer = server;
      resolve(`http://localhost:${address.port}`);
    });
  });
}

function fetchHttpsText(urlString, redirectsLeft = 4) {
  return new Promise((resolve, reject) => {
    const parsed = validateHttpsUrl(urlString);
    if (!parsed || !isAllowedGoogleDownloadHost(parsed.hostname)) {
      reject(new Error('Invalid Google Drive URL.'));
      return;
    }

    const req = https.get(
      parsed,
      {
        timeout: 15000,
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36'
        }
      },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirectsLeft > 0) {
          const nextUrl = new URL(res.headers.location, parsed).toString();
          res.resume();
          fetchHttpsText(nextUrl, redirectsLeft - 1).then(resolve, reject);
          return;
        }
        if (res.statusCode < 200 || res.statusCode >= 300) {
          res.resume();
          reject(new Error(`HTTP ${res.statusCode} while reading Drive folder.`));
          return;
        }
        const chunks = [];
        let total = 0;
        res.on('data', (chunk) => {
          total += chunk.length;
          if (total <= 4 * 1024 * 1024) chunks.push(chunk);
        });
        res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      }
    );
    req.on('timeout', () => req.destroy(new Error('Request timed out')));
    req.on('error', reject);
  });
}

function extractFileNameFromContentDisposition(header) {
  if (!header || typeof header !== 'string') return '';
  const utf8Match = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;\r\n]+)/i.exec(header);
  if (utf8Match?.[1]) {
    try {
      const decoded = decodeURIComponent(utf8Match[1].trim().replace(/^["']|["']$/g, ''));
      if (decoded) return decoded;
    } catch {}
  }
  const quotedMatch = /filename\s*=\s*"([^"]+)"/i.exec(header);
  if (quotedMatch?.[1]?.trim()) return quotedMatch[1].trim();
  const plainMatch = /filename\s*=\s*([^;\r\n]+)/i.exec(header);
  if (plainMatch?.[1]?.trim()) return plainMatch[1].trim().replace(/^["']|["']$/g, '');
  return '';
}

function sanitizeDownloadFileName(rawName, fallback) {
  const noPath = String(rawName || '').split('/').pop().split('\\').pop();
  const cleaned = noPath
    .replace(/[\u0000-\u001F<>:"/\\|?*]/g, '_')
    .replace(/\s+/g, ' ')
    .replace(/^[.\s]+|[.\s]+$/g, '')
    .slice(0, 100);
  return cleaned || fallback;
}

function downloadHttpsBinary(urlString, resolveDestPath, redirectsLeft = 5) {
  return new Promise((resolve, reject) => {
    const parsed = validateHttpsUrl(urlString);
    if (!parsed || !isAllowedGoogleDownloadHost(parsed.hostname)) {
      reject(new Error('URL host not permitted for direct Drive download.'));
      return;
    }

    const req = https.get(
      parsed,
      {
        timeout: 20000,
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36'
        }
      },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirectsLeft > 0) {
          const nextUrl = new URL(res.headers.location, parsed).toString();
          res.resume();
          downloadHttpsBinary(nextUrl, resolveDestPath, redirectsLeft - 1).then(resolve, reject);
          return;
        }
        if (res.statusCode < 200 || res.statusCode >= 300) {
          res.resume();
          resolve({ fallback: true });
          return;
        }
        const contentType = String(res.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
        if (contentType.startsWith('text/html')) {
          res.resume();
          resolve({ fallback: true });
          return;
        }

        const headerFileName = extractFileNameFromContentDisposition(res.headers['content-disposition']);
        let destFilePath = '';
        try {
          destFilePath = resolveDestPath({ headerFileName, contentType });
        } catch (err) {
          res.resume();
          reject(err);
          return;
        }
        if (!destFilePath) {
          res.resume();
          resolve({ fallback: true });
          return;
        }

        let totalBytes = 0;
        const tmpPath = `${destFilePath}.tmp`;
        const out = fs.createWriteStream(tmpPath);
        let aborted = false;

        res.on('data', (chunk) => {
          if (aborted) return;
          totalBytes += chunk.length;
          if (totalBytes > MAX_DRIVE_DOWNLOAD_BYTES) {
            aborted = true;
            res.destroy();
            out.destroy();
            try { fs.unlinkSync(tmpPath); } catch {}
            resolve({ fallback: true });
          }
        });
        res.pipe(out);
        out.on('finish', () => {
          if (aborted) return;
          if (totalBytes <= 0) {
            try { fs.unlinkSync(tmpPath); } catch {}
            resolve({ fallback: true });
            return;
          }
          try {
            if (fs.existsSync(destFilePath)) fs.unlinkSync(destFilePath);
            fs.renameSync(tmpPath, destFilePath);
            resolve({ fallback: false, path: destFilePath, contentType });
          } catch (err) {
            reject(err);
          }
        });
        out.on('error', (err) => {
          try { fs.unlinkSync(tmpPath); } catch {}
          reject(err);
        });
      }
    );
    req.on('timeout', () => req.destroy(new Error('Download timed out')));
    req.on('error', reject);
  });
}

function unescapeHtml(raw) {
  return String(raw || '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#39;/g, "'")
    .replace(/&#x27;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/&#(\d+);/g, (m, code) => {
      const n = Number.parseInt(code, 10);
      return n >= 32 && n <= 0x10ffff ? String.fromCodePoint(n) : m;
    })
    .replace(/&#x([0-9a-fA-F]+);/g, (m, hex) => {
      const n = Number.parseInt(hex, 16);
      return n >= 32 && n <= 0x10ffff ? String.fromCodePoint(n) : m;
    });
}

function extractTitleFromEntryBlock(block, itemId) {
  const titlePatterns = [
    /<(?:div|span|p)\b[^>]*class=["'][^"']*\bflip-entry-title\b[^"']*["'][^>]*>([\s\S]*?)<\/(?:div|span|p)>/gi,
    /<a\b[^>]*href=["']https:\/\/[^"']+["'][^>]*\b(?:title|aria-label)=["']([^"']+)["']/gi,
    /<a\b[^>]*href=["']https:\/\/[^"']+["'][^>]*>([\s\S]*?)<\/a>/gi
  ];
  for (const regex of titlePatterns) {
    regex.lastIndex = 0;
    let m;
    while ((m = regex.exec(block)) !== null) {
      const cleaned = unescapeHtml(m[1]).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
      if (cleaned && cleaned.toLowerCase() !== 'open') {
        return cleaned.slice(0, 140);
      }
    }
  }
  return `Drive File (${itemId.slice(0, 8)})`;
}

function parseEmbeddedFolderHtml(html, folder) {
  const results = [];
  const seen = new Set();
  const entryStartRegex = /<div\b[^>]*\bid=["']entry-([a-zA-Z0-9_-]{10,128})["'][^>]*>/gi;
  const hrefRegex = /href=["'](https:\/\/[^"']+)["']/i;

  const entryMatches = [];
  let match;
  while ((match = entryStartRegex.exec(html)) !== null) {
    entryMatches.push({ index: match.index, itemId: match[1] });
  }

  for (let i = 0; i < entryMatches.length; i += 1) {
    const itemId = entryMatches[i].itemId;
    if (itemId === folder.folderId || seen.has(itemId)) continue;
    seen.add(itemId);

    const blockStart = entryMatches[i].index;
    const blockEnd = i + 1 < entryMatches.length ? entryMatches[i + 1].index : html.length;
    const block = html.slice(blockStart, blockEnd);

    const cleanTitle = extractTitleFromEntryBlock(block, itemId);
    const href = unescapeHtml(hrefRegex.exec(block)?.[1] || '');
    const isSubFolder =
      href.toLowerCase().includes('/folders/') ||
      block.toLowerCase().includes('folder-icon') ||
      block.toLowerCase().includes('flip-folder');
    const viewUrl = isSubFolder
      ? `https://drive.google.com/drive/folders/${itemId}`
      : href.startsWith('https://')
        ? href
        : `https://drive.google.com/file/d/${itemId}/view`;

    let downloadUrl = `https://drive.google.com/uc?export=download&id=${itemId}`;
    if (/docs\.google\.com\/document\/d\//i.test(href)) {
      downloadUrl = `https://docs.google.com/document/d/${itemId}/export?format=pdf`;
    } else if (/docs\.google\.com\/presentation\/d\//i.test(href)) {
      downloadUrl = `https://docs.google.com/presentation/d/${itemId}/export/pdf`;
    } else if (/docs\.google\.com\/spreadsheets\/d\//i.test(href)) {
      downloadUrl = `https://docs.google.com/spreadsheets/d/${itemId}/export?format=pdf`;
    }

    results.push({
      fileId: itemId,
      folderId: folder.folderId,
      folderTitle: folder.title || 'Drive Folder',
      name: cleanTitle,
      isSubFolder,
      viewUrl,
      downloadUrl
    });
  }

  if (results.length === 0) {
    const anchorRegex = /<a\b([^>]*href=["'](https:\/\/(?:drive|docs)\.google\.com\/(?:file\/d|document\/d|presentation\/d|spreadsheets\/d|drive\/folders)\/([a-zA-Z0-9_-]{10,128})[^"']*)["'][^>]*)>([\s\S]*?)<\/a>/gi;
    let m;
    while ((m = anchorRegex.exec(html)) !== null) {
      const attrs = m[1];
      const href = unescapeHtml(m[2]);
      const itemId = m[3];
      if (itemId === folder.folderId || seen.has(itemId)) continue;
      seen.add(itemId);
      const inner = unescapeHtml(m[4]).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
      const attrTitle = unescapeHtml(/\b(?:title|aria-label)=["']([^"']+)["']/i.exec(attrs)?.[1] || '');
      const title = (inner || attrTitle || `Drive File (${itemId.slice(0, 8)})`).slice(0, 140);
      results.push({
        fileId: itemId,
        folderId: folder.folderId,
        folderTitle: folder.title || 'Drive Folder',
        name: title,
        isSubFolder: href.toLowerCase().includes('/folders/'),
        viewUrl: href,
        downloadUrl: `https://drive.google.com/uc?export=download&id=${itemId}`
      });
    }
  }
  return results;
}

function registerIpcHandlers() {
  const getCacheDir = () => {
    const dir = path.resolve(app.getPath('userData'), 'course_cache');
    fs.mkdirSync(dir, { recursive: true });
    return dir;
  };

  ipcMain.handle('app:runtime-info', () => ({
    version: app.getVersion(),
    platform: process.platform,
    isPackaged: app.isPackaged
  }));

  ipcMain.handle('app:open-external', async (_event, rawUrl) => {
    const parsed = validateHttpsUrl(rawUrl);
    if (!parsed) throw new Error('Only HTTPS links are permitted.');
    await shell.openExternal(parsed.toString());
    return { ok: true };
  });

  ipcMain.handle('drive:sync-folders', async (_event, folders) => {
    if (!Array.isArray(folders)) return { files: [] };
    const collected = [];
    let lastError = null;
    for (const folder of folders.slice(0, 20)) {
      const folderId = String(folder?.folderId || '');
      if (!/^[a-zA-Z0-9_-]{10,128}$/.test(folderId)) continue;
      try {
        const html = await fetchHttpsText(`https://drive.google.com/embeddedfolderview?id=${folderId}#list`);
        collected.push(...parseEmbeddedFolderHtml(html, { folderId, title: String(folder?.title || '') }));
      } catch (err) {
        lastError = err;
      }
    }
    if (collected.length === 0 && lastError) {
      return { files: [], error: lastError.message };
    }
    return { files: collected };
  });

  ipcMain.handle('drive:download-open', async (_event, item) => {
    if (!item || item.isSubFolder) {
      const safe = validateHttpsUrl(item?.viewUrl);
      if (safe) await shell.openExternal(safe.toString());
      return { openedInBrowser: true };
    }
    const fileId = String(item.fileId || '').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 128);
    if (!fileId) throw new Error('Invalid Drive file ID.');

    const cacheRoot = getCacheDir();
    const itemCacheDir = path.resolve(cacheRoot, `gdrive_${fileId}`);
    if (!itemCacheDir.startsWith(cacheRoot + path.sep)) {
      throw new Error('Invalid cache directory.');
    }
    fs.mkdirSync(itemCacheDir, { recursive: true });

    const existing = fs.readdirSync(itemCacheDir).find((name) => {
      if (name.endsWith('.tmp') || /^Drive (Item|File)/i.test(name)) return false;
      const full = path.join(itemCacheDir, name);
      return fs.statSync(full).isFile() && fs.statSync(full).size > 0;
    });
    if (existing) {
      const existingPath = path.join(itemCacheDir, existing);
      await shell.openPath(existingPath);
      return { localPath: existingPath };
    }

    const res = await downloadHttpsBinary(item.downloadUrl, ({ headerFileName, contentType }) => {
      const chosenRaw =
        headerFileName &&
        headerFileName.toLowerCase() !== 'download' &&
        !/^Drive Item/i.test(headerFileName)
          ? headerFileName
          : String(item.name || `drive_file_${fileId.slice(0, 8)}`);
      const safeBase = sanitizeDownloadFileName(chosenRaw, `drive_file_${fileId.slice(0, 8)}`);
      const ext = safeBase.includes('.') ? safeBase.split('.').pop().toLowerCase() : '';
      if (ext && BLOCKED_EXTENSIONS.has(ext)) {
        throw new Error(`Blocked executable extension '.${ext}'.`);
      }
      const mimeExt =
        contentType === 'application/pdf' ? 'pdf' :
        contentType === 'image/png' ? 'png' :
        contentType === 'image/jpeg' ? 'jpg' : 'pdf';
      const finalName = ext && ext.length <= 8 ? safeBase : `${safeBase}.${mimeExt}`;
      const destPath = path.resolve(itemCacheDir, finalName);
      if (!destPath.startsWith(itemCacheDir + path.sep)) {
        throw new Error('Invalid local cache destination.');
      }
      return destPath;
    });

    if (!res.fallback && res.path) {
      await shell.openPath(res.path);
      return { localPath: res.path };
    }

    const safeView = validateHttpsUrl(item.viewUrl);
    if (safeView) await shell.openExternal(safeView.toString());
    return { openedInBrowser: true };
  });

  ipcMain.handle('course:open-local-file', async (_event, payload) => {
    const courseId = String(payload?.courseId || '').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 80);
    const fileName = String(payload?.fileName || 'material.pdf');
    const base64Data = String(payload?.base64 || '');
    if (!courseId || !base64Data) throw new Error('Missing file payload.');

    const ext = fileName.includes('.') ? fileName.split('.').pop().toLowerCase() : 'pdf';
    if (BLOCKED_EXTENSIONS.has(ext)) {
      throw new Error(`Blocked file extension '.${ext}'.`);
    }
    const safeName = fileName.replace(/[^a-zA-Z0-9._\-\u0600-\u06FF ]/g, '_').slice(0, 80) || 'material.pdf';
    const cacheDir = getCacheDir();
    const targetPath = path.resolve(cacheDir, `course_${courseId}_${safeName}`);
    if (!targetPath.startsWith(cacheDir + path.sep)) {
      throw new Error('Invalid target cache path.');
    }

    const bytes = Buffer.from(base64Data, 'base64');
    fs.writeFileSync(targetPath, bytes);
    const openError = await shell.openPath(targetPath);
    if (openError) {
      throw new Error(openError);
    }
    return { localPath: targetPath };
  });
}

async function createWindow() {
  session.defaultSession.setUserAgent(sanitizeUserAgent(session.defaultSession.getUserAgent()));

  const iconCandidates = [
    path.join(__dirname, 'dist', 'assets', 'icon.png'),
    path.join(__dirname, 'build', 'icon.png')
  ];
  const iconPath = iconCandidates.find((p) => fs.existsSync(p));
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 840,
    minWidth: 900,
    minHeight: 640,
    title: 'UniCore',
    icon: iconPath,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  // Allow Firebase Auth / Google Sign-In popup flows, send all other links to default OS browser
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    const parsed = validateHttpsUrl(url);
    if (!parsed) return { action: 'deny' };
    const host = parsed.hostname.toLowerCase();
    const isAuthPopup =
      host === 'accounts.google.com' ||
      host.endsWith('.firebaseapp.com') ||
      host.endsWith('.web.app');
    if (isAuthPopup) {
      return {
        action: 'allow',
        overrideBrowserWindowOptions: {
          width: 520,
          height: 680,
          autoHideMenuBar: true,
          webPreferences: {
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: true
          }
        }
      };
    }
    shell.openExternal(parsed.toString());
    return { action: 'deny' };
  });

  const devUrl = process.env.VITE_DEV_SERVER_URL;
  if (devUrl && /^http:\/\/(localhost|127\.0\.0\.1):\d+/.test(devUrl)) {
    await mainWindow.loadURL(devUrl);
  } else {
    const distUrl = await startStaticDistServer(path.join(__dirname, 'dist'));
    await mainWindow.loadURL(distUrl);
  }
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(async () => {
    registerIpcHandlers();
    await createWindow();
  });

  app.on('window-all-closed', () => {
    if (staticServer) {
      try { staticServer.close(); } catch {}
    }
    if (process.platform !== 'darwin') app.quit();
  });
}
