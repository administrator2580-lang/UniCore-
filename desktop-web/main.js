const { app, BrowserWindow, ipcMain, session, shell } = require('electron');
const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const path = require('node:path');

const ALLOWED_EXTERNAL_PROTOCOLS = new Set(['https:']);
const ALLOWED_GOOGLE_HOSTS = new Set([
  'drive.google.com',
  'drive.usercontent.google.com',
  'docs.google.com',
  'accounts.google.com'
]);
const PREFERRED_STATIC_PORTS = [45821, 45822, 45823, 0];
const BLOCKED_EXTENSIONS = new Set([
  'exe', 'apk', 'dex', 'jar', 'so', 'sh', 'bat', 'cmd', 'js', 'vbs', 'ps1', 'msi', 'elf', 'scr', 'com'
]);
const MAX_DRIVE_DOWNLOAD_BYTES = 20 * 1024 * 1024;
const MAX_INLINE_PREVIEW_BYTES = 10 * 1024 * 1024;
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
  return (
    lower.endsWith('.googleusercontent.com') ||
    lower.endsWith('.usercontent.google.com') ||
    lower.endsWith('.googlevideo.com')
  );
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

function createStaticHandler(resolvedDist) {
  return (req, res) => {
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
  };
}

function startStaticDistServer(distDir) {
  const resolvedDist = path.resolve(distDir);
  const tryPort = (index) =>
    new Promise((resolve, reject) => {
      if (index >= PREFERRED_STATIC_PORTS.length) {
        reject(new Error('Unable to bind loopback server port.'));
        return;
      }
      const portCandidate = PREFERRED_STATIC_PORTS[index];
      const server = http.createServer(createStaticHandler(resolvedDist));
      server.once('error', (err) => {
        if (err?.code === 'EADDRINUSE' && index + 1 < PREFERRED_STATIC_PORTS.length) {
          tryPort(index + 1).then(resolve, reject);
        } else {
          reject(err);
        }
      });
      // Stable localhost port keeps Firebase Auth IndexedDB/localStorage persistent across restarts
      server.listen(portCandidate, '127.0.0.1', () => {
        const address = server.address();
        staticServer = server;
        resolve(`http://localhost:${address.port}`);
      });
    });

  return tryPort(0);
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

function extractDriveConfirmUrl(html, baseUrl) {
  const formMatch = /<form\b[^>]*action=["']([^"']+)["'][^>]*>([\s\S]*?)<\/form>/i.exec(html);
  if (formMatch) {
    try {
      const target = new URL(formMatch[1].replace(/&amp;/g, '&'), baseUrl);
      const inputRegex = /<input\b[^>]*name=["']([^"']+)["'][^>]*value=["']([^"']*)["'][^>]*>/gi;
      let m;
      while ((m = inputRegex.exec(formMatch[2])) !== null) {
        target.searchParams.set(m[1], m[2]);
      }
      if (!target.searchParams.has('confirm')) target.searchParams.set('confirm', 't');
      return target.toString();
    } catch {
      // ignore
    }
  }
  const hrefMatch = /href=["'](https:\/\/[^"']*confirm=[^"']+)["']/i.exec(html);
  if (hrefMatch?.[1]) {
    return hrefMatch[1].replace(/&amp;/g, '&');
  }
  return '';
}

function downloadHttpsBinary(urlString, resolveDestPath, redirectsLeft = 6) {
  return new Promise((resolve, reject) => {
    const parsed = validateHttpsUrl(urlString);
    if (!parsed || !isAllowedGoogleDownloadHost(parsed.hostname)) {
      reject(new Error('URL host not permitted for direct Drive download.'));
      return;
    }

    const req = https.get(
      parsed,
      {
        timeout: 25000,
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
          const htmlChunks = [];
          let htmlBytes = 0;
          res.on('data', (chunk) => {
            htmlBytes += chunk.length;
            if (htmlBytes <= 512 * 1024) htmlChunks.push(chunk);
          });
          res.on('end', () => {
            const html = Buffer.concat(htmlChunks).toString('utf8');
            const confirmUrl = redirectsLeft > 0 ? extractDriveConfirmUrl(html, parsed) : '';
            if (confirmUrl) {
              downloadHttpsBinary(confirmUrl, resolveDestPath, redirectsLeft - 1).then(resolve, reject);
            } else {
              resolve({ fallback: true });
            }
          });
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
            resolve({ fallback: false, path: destFilePath, contentType, sizeBytes: totalBytes });
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

    let downloadUrl = `https://drive.usercontent.google.com/download?id=${itemId}&export=download&confirm=t`;
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
        downloadUrl: `https://drive.usercontent.google.com/download?id=${itemId}&export=download&confirm=t`
      });
    }
  }
  return results;
}

async function fetchFolderFilesRecursively(rootFolder, maxDepth = 3, maxSubfoldersPerRoot = 30) {
  const rootTitle = String(rootFolder?.title || 'Study Library').trim() || 'Study Library';
  const rootFolderId = String(rootFolder?.folderId || '');
  if (!/^[a-zA-Z0-9_-]{10,128}$/.test(rootFolderId)) return [];

  const visitedFolderIds = new Set();
  const seenFileIds = new Set();
  const collectedFiles = [];
  const queue = [{ folderId: rootFolderId, title: rootTitle, pathPrefix: '', depth: 0 }];
  let subfoldersProcessed = 0;

  while (queue.length > 0) {
    const current = queue.shift();
    if (!visitedFolderIds.add(current.folderId)) continue;

    let html;
    try {
      html = await fetchHttpsText(`https://drive.google.com/embeddedfolderview?id=${current.folderId}#list`);
    } catch (err) {
      if (current.depth === 0) throw err;
      continue;
    }

    const entries = parseEmbeddedFolderHtml(html, {
      folderId: current.folderId,
      title: current.title
    });

    for (const entry of entries) {
      if (entry.isSubFolder) {
        if (
          current.depth < maxDepth &&
          subfoldersProcessed < maxSubfoldersPerRoot &&
          /^[a-zA-Z0-9_-]{10,128}$/.test(entry.fileId) &&
          !visitedFolderIds.has(entry.fileId)
        ) {
          subfoldersProcessed += 1;
          const nextPath = current.pathPrefix ? `${current.pathPrefix} / ${entry.name}` : entry.name;
          queue.push({
            folderId: entry.fileId,
            title: entry.name,
            pathPrefix: nextPath,
            depth: current.depth + 1
          });
        }
      } else {
        const dedupeKey = `${rootFolderId}::${entry.fileId}`;
        if (!seenFileIds.has(dedupeKey)) {
          seenFileIds.add(dedupeKey);
          collectedFiles.push({
            ...entry,
            folderId: rootFolderId,
            rootFolderTitle: rootTitle,
            subFolderPath: current.pathPrefix,
            folderTitle: current.pathPrefix || rootTitle,
            isSubFolder: false
          });
        }
      }
    }
  }

  return collectedFiles;
}

function registerIpcHandlers() {
  const getCacheDir = () => {
    const dir = path.resolve(app.getPath('userData'), 'course_cache');
    fs.mkdirSync(dir, { recursive: true });
    return dir;
  };

  const getDownloadsDir = () => {
    const dir = path.resolve(app.getPath('downloads'), 'UniCore');
    fs.mkdirSync(dir, { recursive: true });
    return dir;
  };

  const saveCopyToDownloads = (srcPath, fileName) => {
    try {
      const downloadsDir = getDownloadsDir();
      const target = path.resolve(downloadsDir, fileName);
      if (target.startsWith(downloadsDir + path.sep)) {
        fs.copyFileSync(srcPath, target);
        return target;
      }
    } catch {
      // Non-fatal
    }
    return null;
  };

  const buildPreviewPayload = (filePath, explicitMimeType) => {
    try {
      const stats = fs.statSync(filePath);
      const ext = path.extname(filePath).toLowerCase();
      const mimeType =
        explicitMimeType ||
        (ext === '.pdf'
          ? 'application/pdf'
          : ext === '.png'
            ? 'image/png'
            : ext === '.jpg' || ext === '.jpeg'
              ? 'image/jpeg'
              : ext === '.webp'
                ? 'image/webp'
                : ext === '.txt' || ext === '.md' || ext === '.csv'
                  ? 'text/plain'
                  : 'application/octet-stream');
      if (mimeType.startsWith('text/') && stats.size > 0 && stats.size <= 2 * 1024 * 1024) {
        const textContent = fs.readFileSync(filePath, 'utf8').replace(/\s+/g, ' ').trim().slice(0, 28000);
        return { base64: null, textContent, mimeType };
      }
      if (
        stats.size > 0 &&
        stats.size <= MAX_INLINE_PREVIEW_BYTES &&
        (mimeType === 'application/pdf' || mimeType === 'image/png' || mimeType === 'image/jpeg' || mimeType === 'image/webp')
      ) {
        return {
          base64: fs.readFileSync(filePath).toString('base64'),
          textContent: null,
          mimeType
        };
      }
      return { base64: null, textContent: null, mimeType };
    } catch {
      return { base64: null, textContent: null, mimeType: explicitMimeType || 'application/octet-stream' };
    }
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
        const nestedFiles = await fetchFolderFilesRecursively({
          folderId,
          title: String(folder?.title || '')
        });
        collected.push(...nestedFiles);
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
    const openExternally = item.openExternally !== false;
    const skipDownloadsCopy = item.skipDownloadsCopy === true;

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
      const downloadsPath = skipDownloadsCopy ? null : saveCopyToDownloads(existingPath, existing);
      const preview = buildPreviewPayload(existingPath);
      if (openExternally) {
        await shell.openPath(downloadsPath || existingPath);
      }
      return {
        localPath: existingPath,
        downloadsPath,
        fileName: existing,
        mimeType: preview.mimeType,
        base64: preview.base64,
        textContent: preview.textContent
      };
    }

    const candidates = [
      item.downloadUrl,
      `https://drive.usercontent.google.com/download?id=${fileId}&export=download&confirm=t`,
      `https://drive.google.com/uc?export=download&id=${fileId}&confirm=t`
    ].filter((u, idx, arr) => Boolean(u) && arr.indexOf(u) === idx);

    let res = { fallback: true };
    for (const candidateUrl of candidates) {
      res = await downloadHttpsBinary(candidateUrl, ({ headerFileName, contentType }) => {
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
      if (!res.fallback && res.path) break;
    }

    if (!res.fallback && res.path) {
      const finalName = path.basename(res.path);
      const downloadsPath = skipDownloadsCopy ? null : saveCopyToDownloads(res.path, finalName);
      const preview = buildPreviewPayload(res.path, res.contentType);
      if (openExternally) {
        await shell.openPath(downloadsPath || res.path);
      }
      return {
        localPath: res.path,
        downloadsPath,
        fileName: finalName,
        mimeType: preview.mimeType,
        base64: preview.base64,
        textContent: preview.textContent
      };
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
    const safeName = sanitizeDownloadFileName(fileName, 'material.pdf').slice(0, 90);
    const cacheDir = getCacheDir();
    const targetPath = path.resolve(cacheDir, `course_${courseId}_${safeName}`);
    if (!targetPath.startsWith(cacheDir + path.sep)) {
      throw new Error('Invalid target cache path.');
    }

    const bytes = Buffer.from(base64Data, 'base64');
    fs.writeFileSync(targetPath, bytes);
    const downloadsPath = saveCopyToDownloads(targetPath, safeName);
    const openError = await shell.openPath(downloadsPath || targetPath);
    if (openError) {
      throw new Error(openError);
    }
    return { localPath: targetPath, downloadsPath, fileName: safeName };
  });
}

async function createWindow() {
  session.defaultSession.setUserAgent(sanitizeUserAgent(session.defaultSession.getUserAgent()));

  const iconCandidates = [
    path.join(__dirname, 'dist', 'assets', 'icon.png'),
    path.join(__dirname, 'resources', 'icon.png')
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
    if (url === 'about:blank') {
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
