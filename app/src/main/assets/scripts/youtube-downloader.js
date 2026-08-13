// YouTube Video Downloader
// Inject into YouTube WebView and call:
// window.handleDownloadClick()
//
// Expected Android bridge:
//
// Android.startDownload(
//     downloadUrl,
//     fileName,
//     mimeType
// )
//
// Optional:
//
// Android.onDownloadError(message)
// Android.onDownloadStarted(fileName)

(function () {
    'use strict';

    const CONFIG = {
        API_BASE: 'https://p.savenow.to',

        POLL_INTERVAL: 1000,
        MAX_POLL_TIME: 5 * 60 * 1000,

        // Supported SaveNow format identifiers.
        // The API may reject a format if it is unavailable
        // for a particular video.
        FORMATS: [
            {
                id: 'mp4',
                type: 'video',
                extension: 'mp4',
                mimeType: 'video/mp4',
                label: 'MP4',
                quality: 'Best available'
            },
            {
                id: '360',
                type: 'video',
                extension: 'mp4',
                mimeType: 'video/mp4',
                label: 'MP4',
                quality: '360p'
            },
            {
                id: '480',
                type: 'video',
                extension: 'mp4',
                mimeType: 'video/mp4',
                label: 'MP4',
                quality: '480p'
            },
            {
                id: '720',
                type: 'video',
                extension: 'mp4',
                mimeType: 'video/mp4',
                label: 'MP4',
                quality: '720p'
            },
            {
                id: '1080',
                type: 'video',
                extension: 'mp4',
                mimeType: 'video/mp4',
                label: 'MP4',
                quality: '1080p'
            },
            {
                id: '4k',
                type: 'video',
                extension: 'mp4',
                mimeType: 'video/mp4',
                label: 'MP4',
                quality: '4K'
            },
            {
                id: '8k',
                type: 'video',
                extension: 'mp4',
                mimeType: 'video/mp4',
                label: 'MP4',
                quality: '8K'
            },
            {
                id: 'mp3',
                type: 'audio',
                extension: 'mp3',
                mimeType: 'audio/mpeg',
                label: 'MP3',
                quality: 'Audio'
            }
        ]
    };


    // ============================================================
    // STATE
    // ============================================================

    const state = {
        active: false,

        videoUrl: null,
        videoId: null,
        title: null,
        thumbnail: null,

        wasPlaying: false,

        selectedFormat: null,

        jobId: null,
        progressUrl: null,
        downloadUrl: null,

        pollTimer: null,
        pollStartedAt: null,

        overlay: null
    };


    // ============================================================
    // VIDEO DATA
    // ============================================================

    function getVideoUrl() {
        const url = window.location.href;

        if (
            url.includes('youtube.com/watch') ||
            url.includes('youtube.com/shorts') ||
            url.includes('youtu.be/')
        ) {
            return url;
        }

        return null;
    }


    function extractVideoId(url) {
        if (!url) {
            return null;
        }

        const patterns = [
            /(?:youtube\.com\/watch\?v=)([a-zA-Z0-9_-]{11})/,
            /(?:youtube\.com\/shorts\/)([a-zA-Z0-9_-]{11})/,
            /(?:youtu\.be\/)([a-zA-Z0-9_-]{11})/
        ];

        for (const pattern of patterns) {
            const match = url.match(pattern);

            if (match) {
                return match[1];
            }
        }

        return null;
    }


    function getVideoTitle() {
        try {
            const titleElement =
                document.querySelector('h1 yt-formatted-string');

            if (titleElement && titleElement.textContent) {
                return titleElement.textContent.trim();
            }
        } catch (e) {
            console.log('[VideoDownloader] Title element lookup failed');
        }

        let title = document.title || 'video';

        title = title
            .replace(/\s*-\s*YouTube\s*$/i, '')
            .trim();

        return title || 'video';
    }


    function getVideoDuration() {
        try {
            const video = getVideoElement();

            if (video && Number.isFinite(video.duration)) {
                return Math.floor(video.duration * 1000);
            }
        } catch (e) {
            console.log(
                '[VideoDownloader] Could not get video duration'
            );
        }

        return 0;
    }


    function getThumbnailUrl() {
        try {
            const meta = document.querySelector(
                'meta[property="og:image"]'
            );

            if (meta) {
                return meta.getAttribute('content');
            }
        } catch (e) {
            // Ignore
        }

        const videoId = extractVideoId(getVideoUrl());

        if (videoId) {
            return `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;
        }

        return null;
    }


    // ============================================================
    // VIDEO PLAYER
    // ============================================================

    function getVideoElement() {
        return document.getElementsByClassName(
            'video-stream'
        )[0] || document.querySelector('video');
    }


    function getVideoPlaybackState() {
        try {
            const video = getVideoElement();

            if (!video) {
                return null;
            }

            return {
                isPlaying: !video.paused && !video.ended,
                isPaused: video.paused,
                currentTime: video.currentTime || 0,
                duration: video.duration || 0
            };

        } catch (e) {
            console.log(
                '[VideoDownloader] Failed to get playback state:',
                e
            );

            return null;
        }
    }




    // ============================================================
    // UTILITY
    // ============================================================

    function escapeHtml(value) {
        return String(value || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }


    function sanitizeFileName(name) {
        let result = String(name || 'video');

        result = result
            .replace(/[<>:"/\\|?*\x00-\x1F]/g, '')
            .replace(/\s+/g, ' ')
            .trim();

        if (!result) {
            result = 'video';
        }

        // Keep filenames manageable.
        if (result.length > 150) {
            result = result.substring(0, 150).trim();
        }

        return result;
    }


    function formatFileName(format) {
        const title = sanitizeFileName(state.title);

        return `${title}.${format.extension}`;
    }


    function sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }


    // ============================================================
    // UI
    // ============================================================

    function createOverlay() {
        removeOverlay();

        const overlay = document.createElement('div');

        overlay.id = 'video-downloader-overlay';

        overlay.innerHTML = `
            <div id="vd-backdrop"></div>

            <div id="vd-dialog">

                <div id="vd-header">
                    <div id="vd-title">
                        Download
                    </div>

                    <button
                        id="vd-close"
                        type="button"
                        aria-label="Close"
                    >
                        ×
                    </button>
                </div>

                <div id="vd-content"></div>

            </div>
        `;

        document.body.appendChild(overlay);

        state.overlay = overlay;

        injectStyles();

        const closeButton =
            overlay.querySelector('#vd-close');

        if (closeButton) {
            closeButton.addEventListener(
                'click',
                function () {
                    cancelDownloadFlow();
                }
            );
        }

        return overlay;
    }


    function injectStyles() {
        if (document.getElementById(
            'video-downloader-styles'
        )) {
            return;
        }

        const style = document.createElement('style');

        style.id = 'video-downloader-styles';

        style.textContent = `
            #video-downloader-overlay {
                position: fixed;
                inset: 0;
                z-index: 2147483647;
                font-family:
                    Roboto,
                    Arial,
                    sans-serif;
            }

            #vd-backdrop {
                position: absolute;
                inset: 0;
                background: rgba(0,0,0,0.70);
                backdrop-filter: blur(3px);
            }

            #vd-dialog {
                position: absolute;
                left: 50%;
                top: 50%;
                transform: translate(-50%, -50%);

                width: min(420px, calc(100vw - 32px));
                max-height: calc(100vh - 48px);

                background: #212121;
                color: #fff;

                border-radius: 14px;

                overflow: hidden;

                box-shadow:
                    0 20px 60px rgba(0,0,0,0.55);

                display: flex;
                flex-direction: column;
            }

            #vd-header {
                display: flex;
                align-items: center;
                justify-content: space-between;

                padding: 18px 20px 14px;

                border-bottom:
                    1px solid rgba(255,255,255,0.08);
            }

            #vd-title {
                font-size: 20px;
                font-weight: 600;
            }

            #vd-close {
                border: 0;
                background: transparent;
                color: #aaa;

                font-size: 28px;
                line-height: 1;

                width: 36px;
                height: 36px;

                cursor: pointer;
            }

            #vd-close:hover {
                color: #fff;
            }

            #vd-content {
                padding: 20px;
                overflow-y: auto;
            }

            .vd-video-info {
                display: flex;
                gap: 12px;
                margin-bottom: 20px;
            }

            .vd-thumbnail {
                width: 110px;
                height: 62px;

                object-fit: cover;

                border-radius: 6px;

                background: #111;

                flex-shrink: 0;
            }

            .vd-video-title {
                font-size: 14px;
                line-height: 1.4;

                display: -webkit-box;
                -webkit-line-clamp: 3;
                -webkit-box-orient: vertical;

                overflow: hidden;
            }

            .vd-section-title {
                font-size: 13px;
                font-weight: 600;

                color: #aaa;

                margin:
                    18px 0 8px;
            }

            .vd-format-list {
                display: flex;
                flex-direction: column;
                gap: 8px;
            }

            .vd-format {
                position: relative;

                display: flex;
                align-items: center;

                padding: 13px 14px;

                border:
                    1px solid rgba(255,255,255,0.12);

                border-radius: 9px;

                cursor: pointer;

                transition:
                    background 0.15s,
                    border-color 0.15s;
            }

            .vd-format:hover {
                background:
                    rgba(255,255,255,0.06);
            }

            .vd-format.selected {
                border-color: #3ea6ff;
                background:
                    rgba(62,166,255,0.12);
            }

            .vd-radio {
                width: 18px;
                height: 18px;

                border:
                    2px solid #777;

                border-radius: 50%;

                margin-right: 12px;

                position: relative;

                flex-shrink: 0;
            }

            .vd-format.selected .vd-radio {
                border-color: #3ea6ff;
            }

            .vd-format.selected .vd-radio::after {
                content: '';

                position: absolute;

                width: 8px;
                height: 8px;

                left: 3px;
                top: 3px;

                background: #3ea6ff;

                border-radius: 50%;
            }

            .vd-format-main {
                flex: 1;
            }

            .vd-format-name {
                font-size: 15px;
                font-weight: 500;
            }

            .vd-format-quality {
                font-size: 12px;
                color: #aaa;

                margin-top: 2px;
            }

            .vd-actions {
                display: flex;
                justify-content: flex-end;
                gap: 8px;

                margin-top: 22px;
            }

            .vd-button {
                border: 0;

                padding: 10px 16px;

                border-radius: 7px;

                font-size: 14px;
                font-weight: 500;

                cursor: pointer;
            }

            .vd-button-secondary {
                color: #fff;
                background: transparent;
            }

            .vd-button-primary {
                color: #fff;
                background: #3ea6ff;
            }

            .vd-button-primary:disabled {
                opacity: 0.45;
                cursor: default;
            }

            .vd-status {
                text-align: center;
                padding: 12px 0 8px;
            }

            .vd-status-icon {
                font-size: 38px;
                margin-bottom: 12px;
            }

            .vd-status-title {
                font-size: 17px;
                font-weight: 500;
                margin-bottom: 7px;
            }

            .vd-status-text {
                font-size: 13px;
                color: #aaa;
                line-height: 1.5;
            }

            .vd-progress-container {
                margin-top: 22px;
            }

            .vd-progress-track {
                width: 100%;
                height: 6px;

                background: #444;

                border-radius: 5px;

                overflow: hidden;
            }

            .vd-progress-bar {
                width: 0%;
                height: 100%;

                background: #3ea6ff;

                transition: width 0.3s ease;
            }

            .vd-progress-value {
                text-align: right;

                font-size: 12px;
                color: #aaa;

                margin-top: 7px;
            }

            .vd-error {
                color: #ff8a80;

                font-size: 14px;

                line-height: 1.5;

                text-align: center;
            }

            .vd-spinner {
                width: 32px;
                height: 32px;

                border:
                    3px solid rgba(255,255,255,0.18);

                border-top-color: #3ea6ff;

                border-radius: 50%;

                animation:
                    vd-spin 0.8s linear infinite;

                margin: 0 auto 18px;
            }

            @keyframes vd-spin {
                to {
                    transform: rotate(360deg);
                }
            }
        `;

        document.head.appendChild(style);
    }


    function removeOverlay() {
        const existing =
            document.getElementById(
                'video-downloader-overlay'
            );

        if (existing) {
            existing.remove();
        }

        state.overlay = null;
    }


    function setDialogContent(html) {
        if (!state.overlay) {
            return;
        }

        const content =
            state.overlay.querySelector('#vd-content');

        if (content) {
            content.innerHTML = html;
        }
    }


    // ============================================================
    // FORMAT SELECTION
    // ============================================================

    function showFormatDialog() {
        createOverlay();

        const videoTitle =
            escapeHtml(state.title);

        const thumbnail =
            state.thumbnail
                ? `
                    <img
                        class="vd-thumbnail"
                        src="${escapeHtml(state.thumbnail)}"
                        onerror="this.style.display='none'"
                    />
                  `
                : '';

        const videoFormats =
            CONFIG.FORMATS.filter(
                format => format.type === 'video'
            );

        const audioFormats =
            CONFIG.FORMATS.filter(
                format => format.type === 'audio'
            );

        let html = `
            <div class="vd-video-info">
                ${thumbnail}

                <div class="vd-video-title">
                    ${videoTitle}
                </div>
            </div>

            <div class="vd-section-title">
                VIDEO
            </div>

            <div class="vd-format-list">
        `;

        videoFormats.forEach((format, index) => {
            const selected =
                format.id === '720';

            html += createFormatOption(
                format,
                selected
            );
        });

        html += `
            </div>

            <div class="vd-section-title">
                AUDIO
            </div>

            <div class="vd-format-list">
        `;

        audioFormats.forEach(format => {
            html += createFormatOption(
                format,
                false
            );
        });

        html += `
            </div>

            <div class="vd-actions">
                <button
                    id="vd-cancel"
                    class="vd-button vd-button-secondary"
                    type="button"
                >
                    CANCEL
                </button>

                <button
                    id="vd-confirm"
                    class="vd-button vd-button-primary"
                    type="button"
                >
                    DOWNLOAD
                </button>
            </div>
        `;

        setDialogContent(html);

        state.selectedFormat =
            CONFIG.FORMATS.find(
                format => format.id === '720'
            ) || CONFIG.FORMATS[0];

        bindFormatSelection();

        const cancel =
            document.getElementById('vd-cancel');

        const confirm =
            document.getElementById('vd-confirm');

        cancel.addEventListener(
            'click',
            cancelDownloadFlow
        );

        confirm.addEventListener(
            'click',
            function () {
                startExtraction();
            }
        );
    }


    function createFormatOption(
        format,
        selected
    ) {
        return `
            <div
                class="vd-format ${
                    selected ? 'selected' : ''
                }"
                data-format="${escapeHtml(format.id)}"
            >

                <div class="vd-radio"></div>

                <div class="vd-format-main">

                    <div class="vd-format-name">
                        ${escapeHtml(format.label)}
                    </div>

                    <div class="vd-format-quality">
                        ${escapeHtml(format.quality)}
                    </div>

                </div>

            </div>
        `;
    }


    function bindFormatSelection() {
        const options =
            document.querySelectorAll(
                '#vd-content .vd-format'
            );

        options.forEach(option => {
            option.addEventListener(
                'click',
                function () {

                    options.forEach(item => {
                        item.classList.remove(
                            'selected'
                        );
                    });

                    option.classList.add(
                        'selected'
                    );

                    const id =
                        option.getAttribute(
                            'data-format'
                        );

                    state.selectedFormat =
                        CONFIG.FORMATS.find(
                            format =>
                                format.id === id
                        ) || null;
                }
            );
        });
    }


    // ============================================================
    // API
    // ============================================================

    async function createDownloadJob(format) {
        const apiUrl =
            CONFIG.API_BASE +
            '/api/v2/download?' +
            new URLSearchParams({
                button: '1',
                format: format.id,

                // This tells the service where
                // the request originated.
                iframe_source: location.origin,

                url: state.videoUrl
            });

        console.log(
            '[VideoDownloader] Creating job:',
            format.id
        );

        const response =
            await fetch(apiUrl);

        if (!response.ok) {
            throw new Error(
                `HTTP ${response.status}`
            );
        }

        const data =
            await response.json();

        console.log(
            '[VideoDownloader] Initial response:',
            data
        );

        if (!data || !data.success) {
            throw new Error(
                data?.message ||
                data?.text ||
                'The server could not prepare this download.'
            );
        }

        if (!data.progress_url) {
            throw new Error(
                'The server did not return a progress URL.'
            );
        }

        return data;
    }


    async function pollProgress(progressUrl) {
        state.pollStartedAt = Date.now();

        while (true) {

            if (!state.active) {
                throw new Error(
                    'Download cancelled.'
                );
            }

            if (
                Date.now() -
                state.pollStartedAt >
                CONFIG.MAX_POLL_TIME
            ) {
                throw new Error(
                    'Download preparation timed out.'
                );
            }

            let response;

            try {
                response =
                    await fetch(progressUrl);

            } catch (error) {
                throw new Error(
                    'Network error while checking download progress.'
                );
            }

            if (!response.ok) {
                throw new Error(
                    `Progress request failed (${response.status}).`
                );
            }

            const data =
                await response.json();

            console.log(
                '[VideoDownloader] Progress:',
                data
            );

            if (
                data.success === 0 ||
                data.text === 'Failed' ||
                data.status === 'failed'
            ) {
                throw new Error(
                    data.message ||
                    data.text ||
                    'Download preparation failed.'
                );
            }

            const rawProgress =
                Number(data.progress);

            if (Number.isFinite(rawProgress)) {

                // API uses 0 - 1000.
                const percentage =
                    Math.max(
                        0,
                        Math.min(
                            100,
                            rawProgress / 10
                        )
                    );

                updateProgress(
                    percentage
                );
            }

            if (
                data.success === 1 &&
                data.download_url
            ) {
                return data;
            }

            await sleep(
                CONFIG.POLL_INTERVAL
            );
        }
    }


    // ============================================================
    // EXTRACTION FLOW
    // ============================================================

    async function startExtraction() {
        if (!state.selectedFormat) {
            return;
        }

        showPreparingUI();

        try {

            const job =
                await createDownloadJob(
                    state.selectedFormat
                );

            state.jobId =
                job.id;

            state.progressUrl =
                job.progress_url;

            // The API may have already
            // returned a final URL.
            if (job.url) {
                state.downloadUrl =
                    job.url;
            }

            let result = job;

            if (!state.downloadUrl) {
                result =
                    await pollProgress(
                        state.progressUrl
                    );
            }

            if (!result.download_url &&
                !state.downloadUrl) {
                throw new Error(
                    'Download was prepared but no download URL was returned.'
                );
            }

            state.downloadUrl =
                state.downloadUrl ||
                result.download_url;

            await handleDownloadReady(
                result
            );

        } catch (error) {

            console.error(
                '[VideoDownloader] Download failed:',
                error
            );

            if (
                error.message ===
                'Download cancelled.'
            ) {
                return;
            }

            showErrorUI(
                error.message ||
                'Unable to prepare download.'
            );
        }
    }


    // ============================================================
    // PROGRESS UI
    // ============================================================

    function showPreparingUI() {
        if (!state.overlay) {
            createOverlay();
        }

        setDialogContent(`
            <div class="vd-status">

                <div class="vd-spinner"></div>

                <div class="vd-status-title">
                    Preparing download...
                </div>

                <div class="vd-status-text">
                    ${escapeHtml(
                        state.selectedFormat?.label || ''
                    )}
                    ${
                        state.selectedFormat?.quality
                            ? ' • ' +
                              escapeHtml(
                                  state.selectedFormat.quality
                              )
                            : ''
                    }
                </div>

                <div class="vd-progress-container">

                    <div class="vd-progress-track">
                        <div
                            id="vd-progress-bar"
                            class="vd-progress-bar"
                        ></div>
                    </div>

                    <div
                        id="vd-progress-value"
                        class="vd-progress-value"
                    >
                        0%
                    </div>

                </div>

                <div class="vd-actions">

                    <button
                        id="vd-cancel-progress"
                        class="vd-button vd-button-secondary"
                        type="button"
                    >
                        CANCEL
                    </button>

                </div>

            </div>
        `);

        const cancel =
            document.getElementById(
                'vd-cancel-progress'
            );

        if (cancel) {
            cancel.addEventListener(
                'click',
                cancelDownloadFlow
            );
        }
    }


    function updateProgress(percentage) {
        const bar =
            document.getElementById(
                'vd-progress-bar'
            );

        const value =
            document.getElementById(
                'vd-progress-value'
            );

        if (bar) {
            bar.style.width =
                `${percentage}%`;
        }

        if (value) {
            value.textContent =
                `${Math.round(percentage)}%`;
        }
    }


    // ============================================================
    // DOWNLOAD READY
    // ============================================================

    async function handleDownloadReady(data) {
        console.log(
            '[VideoDownloader] Download ready:',
            state.downloadUrl
        );

        const format =
            state.selectedFormat;

        const fileName =
            formatFileName(format);

        showDownloadStartingUI(
            fileName
        );

        const started =
            triggerAndroidDownload(
                state.downloadUrl,
                fileName,
                format.mimeType
            );

        if (!started) {
            throw new Error(
                'Android download bridge is not available.'
            );
        }

        showDownloadStartedUI(
            fileName
        );

        // Give Android a moment to receive
        // the request before closing UI.
        await sleep(1000);

        finishDownloadFlow();
    }


    function triggerAndroidDownload(
        downloadUrl,
        fileName,
        mimeType
    ) {
        try {

            if (
                window.Android &&
                typeof Android.startDownload ===
                    'function'
            ) {

                Android.startDownload(
                    downloadUrl,
                    fileName,
                    mimeType
                );

                return true;
            }

            /*
             * Compatibility with your existing
             * bridge if you currently have
             * openDownloadDialog().
             *
             * This should preferably be replaced
             * by startDownload() on Android.
             */

            if (
                window.Android &&
                typeof Android.openDownloadDialog ===
                    'function'
            ) {

                Android.openDownloadDialog(
                    downloadUrl
                );

                return true;
            }

        } catch (error) {

            console.error(
                '[VideoDownloader] Android bridge error:',
                error
            );

            try {
                if (
                    window.Android &&
                    typeof Android.onDownloadError ===
                        'function'
                ) {
                    Android.onDownloadError(
                        error.message ||
                        'Download failed.'
                    );
                }
            } catch (_) {
                // Ignore bridge callback errors.
            }
        }

        return false;
    }


    function showDownloadStartingUI(
        fileName
    ) {
        setDialogContent(`
            <div class="vd-status">

                <div class="vd-spinner"></div>

                <div class="vd-status-title">
                    Starting download...
                </div>

                <div class="vd-status-text">
                    ${escapeHtml(fileName)}
                </div>

            </div>
        `);
    }


    function showDownloadStartedUI(
        fileName
    ) {
        setDialogContent(`
            <div class="vd-status">

                <div class="vd-status-icon">
                    ✓
                </div>

                <div class="vd-status-title">
                    Download started
                </div>

                <div class="vd-status-text">
                    ${escapeHtml(fileName)}
                </div>

            </div>
        `);

        try {
            if (
                window.Android &&
                typeof Android.onDownloadStarted ===
                    'function'
            ) {
                Android.onDownloadStarted(
                    fileName
                );
            }
        } catch (_) {
            // Ignore callback errors.
        }
    }


    // ============================================================
    // ERROR UI
    // ============================================================

    function showErrorUI(message) {
        if (!state.overlay) {
            createOverlay();
        }

        setDialogContent(`
            <div class="vd-status">

                <div class="vd-status-icon">
                    ⚠
                </div>

                <div class="vd-status-title">
                    Download failed
                </div>

                <div class="vd-error">
                    ${escapeHtml(message)}
                </div>

                <div class="vd-actions">

                    <button
                        id="vd-error-close"
                        class="vd-button vd-button-primary"
                        type="button"
                    >
                        CLOSE
                    </button>

                </div>

            </div>
        `);

        const close =
            document.getElementById(
                'vd-error-close'
            );

        if (close) {
            close.addEventListener(
                'click',
                function () {
                    finishDownloadFlow();
                }
            );
        }
    }


    // ============================================================
    // CANCEL / CLEANUP
    // ============================================================

    function cancelDownloadFlow() {
        console.log(
            '[VideoDownloader] Cancelling'
        );

        state.active = false;

        if (state.pollTimer) {
            clearTimeout(
                state.pollTimer
            );

            state.pollTimer = null;
        }

        finishDownloadFlow();
    }


    function finishDownloadFlow() {
        state.active = false;

        if (state.pollTimer) {
            clearTimeout(
                state.pollTimer
            );

            state.pollTimer = null;
        }

        removeOverlay();

        state.videoUrl = null;
        state.videoId = null;
        state.title = null;
        state.thumbnail = null;

        state.wasPlaying = false;

        state.selectedFormat = null;

        state.jobId = null;
        state.progressUrl = null;
        state.downloadUrl = null;

        state.pollStartedAt = null;
    }


    // ============================================================
    // MAIN DOWNLOAD BUTTON
    // ============================================================

    function handleDownloadClick() {

        if (state.active) {
            console.log(
                '[VideoDownloader] Download already active'
            );

            return;
        }

        const videoUrl =
            getVideoUrl();

        if (!videoUrl) {
            console.error('Could not detect YouTube video');
            return;
        }

        const videoId =
            extractVideoId(videoUrl);

        if (!videoId) {
            console.error('Could not determine the YouTube video ID');
            return;
        }

        state.active = true;

        state.videoUrl = videoUrl;
        state.videoId = videoId;

        state.title =
            getVideoTitle();

        state.thumbnail =
            getThumbnailUrl();

        const playback =
            getVideoPlaybackState();

        state.wasPlaying =
            playback?.isPlaying === true;

        console.log(
            '[VideoDownloader] Starting download flow'
        );

        console.log(
            '[VideoDownloader] URL:',
            state.videoUrl
        );

        console.log(
            '[VideoDownloader] ID:',
            state.videoId
        );

        console.log(
            '[VideoDownloader] Title:',
            state.title
        );

        console.log(
            '[VideoDownloader] Was playing:',
            state.wasPlaying
        );

        /*
         * Show format selection.
         */
        showFormatDialog();
    }


    // ============================================================
    // PUBLIC API
    // ============================================================

    window.handleDownloadClick =
        handleDownloadClick;


    window.VideoDownloader = {

        handleDownloadClick,

        getVideoUrl,
        extractVideoId,
        getVideoTitle,
        getVideoDuration,
        getThumbnailUrl,

        getVideoPlaybackState,

        cancel: cancelDownloadFlow,

        getState: function () {
            return {
                active: state.active,
                videoUrl: state.videoUrl,
                videoId: state.videoId,
                title: state.title,
                selectedFormat:
                    state.selectedFormat,
                jobId: state.jobId,
                progressUrl:
                    state.progressUrl,
                downloadUrl:
                    state.downloadUrl
            };
        }
    };


    // ============ Video Event Listeners ============
    let lastVideoElement = null;

    const observer = new MutationObserver(() => {
        const video = document.getElementsByClassName('video-stream')[0];

        if (video && video !== lastVideoElement) {
            lastVideoElement = video;
            console.log('[VideoDownloader] New video detected, requesting listener injection');
            Android.reinjectVideoListeners();
        } else if (!video) {
            lastVideoElement = null;
        }
    });

    observer.observe(document.body, {
        childList: true,
        subtree: true
    });

    console.log(
        '[VideoDownloader] Loaded successfully'
    );

})();