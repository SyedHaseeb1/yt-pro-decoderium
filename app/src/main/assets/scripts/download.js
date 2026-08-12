/**
 * YT Pro - Download Manager
 * Handles video/audio stream fetching and file writing
 */

class YTProDownloader {
  constructor() {
    this.pendingPorts = {};
    this.setupMessageListener();
  }

  setupMessageListener() {
    window.addEventListener("message", (event) => {
      if (typeof event.data === "string" && event.data.startsWith("PORT_FOR:") && event.ports.length > 0) {
        const fileName = event.data.substring(9);
        if (this.pendingPorts[fileName]) {
          this.pendingPorts[fileName](event.ports[0]);
          delete this.pendingPorts[fileName];
        }
      }
    });
  }

  requestBinaryPort(fileName) {
    return new Promise((resolve) => {
      this.pendingPorts[fileName] = resolve;
      window.Android?.requestBinaryPort?.(fileName);
    });
  }

  async pipeToDisk(stream, fileName, expectedBytes, elDetailsOrCallback, elProgress) {
    console.log(`[DOWNLOAD] Starting download: ${fileName}, expected: ${expectedBytes} bytes`);

    // Support both UI element updates and callback-based progress
    const isUIMode = elDetailsOrCallback?.children !== undefined;
    const onProgress = isUIMode ? null : elDetailsOrCallback;

    const filePort = await this.requestBinaryPort(fileName);
    if (!filePort) {
      console.error(`[DOWNLOAD] Failed to get port for ${fileName}`);
      if (isUIMode) {
        window.Android?.showToast?.("Failed to open file for writing");
      } else {
        onProgress?.({ error: "Failed to open file" });
      }
      return 0;
    }

    console.log(`[DOWNLOAD] Port acquired for ${fileName}`);

    const reader = stream.getReader();
    let total = 0;
    let lastLogMB = -1;
    const totalMB = expectedBytes > 0 ? (expectedBytes / (1024 * 1024)).toFixed(2) : '?';

    try {
      const CHUNK_SIZE = 1024 * 512;
      let chunkCount = 0;

      while (true) {
        const { done, value } = await reader.read();

        if (done) {
          console.log(`[DOWNLOAD] Stream ended for ${fileName}`);
          break;
        }

        if (value?.length > 0) {
          let offset = 0;
          while (offset < value.length) {
            const chunkBuffer = value.slice(offset, offset + CHUNK_SIZE).buffer;

            try {
              filePort.postMessage(chunkBuffer);
              chunkCount++;
            } catch (e) {
              console.error(`[DOWNLOAD] Failed to send chunk ${chunkCount}:`, e);
              throw e;
            }

            const bytesWritten = chunkBuffer.byteLength;
            offset += bytesWritten;
            total += bytesWritten;

            const currentMBFloor = Math.floor(total / (1024 * 1024));
            if (currentMBFloor > lastLogMB) {
              const downloadedMB = (total / (1024 * 1024)).toFixed(2);
              const percent = expectedBytes > 0 ? Math.round((total / expectedBytes) * 100) : -1;

              if (isUIMode) {
                if (elDetailsOrCallback?.children?.[0]) {
                  elDetailsOrCallback.children[0].innerHTML = ` ${downloadedMB} MB / ${totalMB} MB`;
                }
                if (elProgress) {
                  elProgress.style.width = percent + "%";
                  elProgress.innerHTML = percent + "%";
                }
              } else {
                onProgress?.({
                  downloaded: downloadedMB,
                  total: totalMB,
                  percent: percent,
                  bytes: total
                });
              }

              window.Android?.onDownloadProgress?.(percent, total);
              console.log(`[DOWNLOAD] ${fileName}: ${downloadedMB}MB / ${totalMB}MB (${percent}%)`);
              lastLogMB = currentMBFloor;
            }

            await new Promise(r => setTimeout(r, 5));
          }
        }
      }

      console.log(`[DOWNLOAD] Completed reading stream for ${fileName}, total chunks: ${chunkCount}, total bytes: ${total}`);
    } catch (error) {
      console.error(`[DOWNLOAD] Error writing to disk:`, error);
      if (isUIMode) {
        window.Android?.showToast?.(`Download failed: ${error.message}`);
      } else {
        onProgress?.({ error: error.message });
      }
      throw error;
    } finally {
      try {
        filePort.postMessage("END");
        console.log(`[DOWNLOAD] Sent END signal to ${fileName}`);
      } catch (e) {
        console.error(`[DOWNLOAD] Failed to send END signal:`, e);
      }
      reader.releaseLock();
    }

    const finalMB = (total / (1024 * 1024)).toFixed(2);

    if (isUIMode) {
      if (elDetailsOrCallback?.children?.[0]) {
        elDetailsOrCallback.children[0].innerHTML = ` ${finalMB} MB / ${totalMB} MB`;
      }
      if (elProgress) {
        elProgress.style.width = "100%";
        elProgress.innerHTML = "100%";
      }
    } else {
      onProgress?.({
        downloaded: finalMB,
        total: totalMB,
        percent: 100,
        bytes: total,
        complete: true
      });
    }

    console.log(`[DOWNLOAD] Download complete for ${fileName}: ${total} bytes`);
    return total;
  }

  async downloadStream(stream, fileName, expectedBytes) {
    return this.pipeToDisk(stream, fileName, expectedBytes, (progress) => {
      if (progress?.error) {
        console.error(`[DOWNLOAD] ${progress.error}`);
        window.Android?.showToast?.(progress.error);
      } else if (progress?.complete) {
        console.log(`[DOWNLOAD] Completed: ${fileName} (${progress.bytes} bytes)`);
      } else {
        console.log(`[DOWNLOAD] ${progress?.downloaded}MB / ${progress?.total}MB (${progress?.percent}%)`);
      }
    });
  }
}

// Export as global instance
window.ytProDownloader = new YTProDownloader();

// Legacy function for backward compatibility
window.downloadStream = async (stream, fileName, expectedBytes) => {
  return window.ytProDownloader.downloadStream(stream, fileName, expectedBytes);
};
