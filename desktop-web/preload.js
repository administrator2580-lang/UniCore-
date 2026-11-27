const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('unicoreDesktop', {
  isElectron: true,
  platform: process.platform,
  openExternalUrl: (url) => ipcRenderer.invoke('app:open-external', url),
  getCachedDriveState: () => ipcRenderer.invoke('drive:get-cached-state'),
  syncDriveFolders: (folders) => ipcRenderer.invoke('drive:sync-folders', folders),
  downloadAndOpenDriveFile: (item) => ipcRenderer.invoke('drive:download-open', item),
  deleteOfflineDriveFile: (fileId) => ipcRenderer.invoke('drive:delete-offline', fileId),
  openLocalCourseFile: (payload) => ipcRenderer.invoke('course:open-local-file', payload),
  getDesktopRuntimeInfo: () => ipcRenderer.invoke('app:runtime-info')
});
