package com.google.android.youtube.pro.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
// ... (rest of imports)
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;

public class MediaMuxerUtils {

    private static final String TAG = "YTPRO_MEDIA";

    public interface MuxCallback {
        void onSuccess(File outputFile);
        void onFailure(Exception e);
    }

    /**
     * Fixes seekability of a video file by re-muxing it.
     * This is useful for fragmented MP4s or files with missing/broken headers.
     */
    public static void fixSeekability(Context context, File sourceFile, MuxCallback callback) {
        fixSeekability(context, Uri.fromFile(sourceFile), sourceFile.getName(), callback);
    }

    public static void fixSeekability(Context context, Uri sourceUri, String displayName, MuxCallback callback) {
        new Thread(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaMuxer muxer = null;
            File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            File ytProDir = new File(downloadDir, "YTPRO");
            if (!ytProDir.exists()) {
                if (!ytProDir.mkdirs()) {
                    Log.e(TAG, "Failed to create YTPRO directory");
                }
            }
            
            File outputFile = new File(ytProDir, "fixed_" + displayName);
            Uri outputUri = null;
            ParcelFileDescriptor pfd = null;
            ParcelFileDescriptor sourcePfd = null;

            try {
                sourcePfd = context.getContentResolver().openFileDescriptor(sourceUri, "r");
                if (sourcePfd == null) throw new Exception("Failed to open source URI");
                
                extractor.setDataSource(sourcePfd.getFileDescriptor());
                
                int outFormat = displayName.toLowerCase().endsWith(".webm")
                        ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                        : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;

                // Create muxer for fixed output
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = context.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, outputFile.getName());
                    values.put(MediaStore.Downloads.MIME_TYPE, displayName.toLowerCase().endsWith(".webm") ? "video/webm" : "video/mp4");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/YTPRO");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    outputUri = resolver.insert(MediaStore.Downloads.getContentUri("external"), values);
                    if (outputUri == null) throw new Exception("MediaStore insert failed");
                    pfd = resolver.openFileDescriptor(outputUri, "rw");
                    if (pfd == null) throw new Exception("openFileDescriptor failed");
                    muxer = new MediaMuxer(pfd.getFileDescriptor(), outFormat);
                } else {
                    muxer = new MediaMuxer(outputFile.getAbsolutePath(), outFormat);
                }

                int trackCount = extractor.getTrackCount();
                int videoTrackIndex = -1;
                int audioTrackIndex = -1;
                int muxerVideoTrackIndex = -1;
                int muxerAudioTrackIndex = -1;
                
                int maxVideoWidth = -1;

                // Find the best video track (highest resolution)
                for (int i = 0; i < trackCount; i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        int width = format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
                        if (width > maxVideoWidth) {
                            maxVideoWidth = width;
                            videoTrackIndex = i;
                        }
                    }
                }
                
                // Find the first audio track
                for (int i = 0; i < trackCount; i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        break;
                    }
                }

                // Add selected tracks to muxer
                if (videoTrackIndex != -1) {
                    MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
                    extractor.selectTrack(videoTrackIndex);
                    muxerVideoTrackIndex = muxer.addTrack(format);
                    Log.d(TAG, "Selected video track: " + format.getString(MediaFormat.KEY_MIME) + " " + maxVideoWidth + "px");
                }
                if (audioTrackIndex != -1) {
                    MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
                    extractor.selectTrack(audioTrackIndex);
                    muxerAudioTrackIndex = muxer.addTrack(format);
                    Log.d(TAG, "Selected audio track: " + format.getString(MediaFormat.KEY_MIME));
                }

                if (muxerVideoTrackIndex == -1 && muxerAudioTrackIndex == -1) {
                    throw new Exception("No valid tracks found to re-mux");
                }

                muxer.start();

                // Increase buffer size to 8MB for high-res videos
                ByteBuffer buffer = ByteBuffer.allocate(8 * 1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                long videoStartTime = -1;
                long audioStartTime = -1;

                while (true) {
                    int sampleTrackIndex = extractor.getSampleTrackIndex();
                    if (sampleTrackIndex < 0) break;

                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;

                    int targetMuxerIndex = -1;
                    if (sampleTrackIndex == videoTrackIndex) {
                        targetMuxerIndex = muxerVideoTrackIndex;
                        if (videoStartTime == -1) videoStartTime = extractor.getSampleTime();
                    } else if (sampleTrackIndex == audioTrackIndex) {
                        targetMuxerIndex = muxerAudioTrackIndex;
                        if (audioStartTime == -1) audioStartTime = extractor.getSampleTime();
                    }

                    if (targetMuxerIndex != -1) {
                        info.offset = 0;
                        info.size = sampleSize;
                        info.flags = extractor.getSampleFlags();
                        
                        // Zero-base the presentation time to avoid seek issues
                        long startTime = (sampleTrackIndex == videoTrackIndex) ? videoStartTime : audioStartTime;
                        info.presentationTimeUs = extractor.getSampleTime() - startTime;
                        
                        if (info.presentationTimeUs >= 0) {
                            muxer.writeSampleData(targetMuxerIndex, buffer, info);
                        }
                    }
                    extractor.advance();
                }

                muxer.stop();
                muxer.release();
                muxer = null;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(outputUri, values, null, null);
                }

                // If original was a file, try to replace it to keep the original name
                if ("file".equals(sourceUri.getScheme())) {
                    String path = sourceUri.getPath();
                    if (path != null) {
                        File sourceFile = new File(path);
                        File finalFile = new File(sourceFile.getAbsolutePath());
                        if (sourceFile.exists()) {
                            deleteFile(context, sourceFile);
                            if (outputFile.renameTo(finalFile)) {
                                outputFile = finalFile;
                            }
                        }
                    }
                }

                MediaScannerConnection.scanFile(context, new String[]{outputFile.getAbsolutePath()}, null, (path, scanUri) -> {
                    Log.d(TAG, "Seekability fix complete: " + path);
                    if (callback != null) {
                        new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(new File(path)));
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Fix seekability failed: " + e.getMessage(), e);
                if (outputFile.exists()) outputFile.delete();
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(e));
                }
            } finally {
                extractor.release();
                if (muxer != null) {
                    try { muxer.stop(); } catch (Exception ignored) {}
                    try { muxer.release(); } catch (Exception ignored) {}
                }
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
                if (sourcePfd != null) {
                    try { sourcePfd.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    public static void muxVideoAudio(Context context,
                                     File videoFile,
                                     File audioFile,
                                     File outputFile,
                                     MuxCallback callback) {
        new Thread(() -> {
            MediaExtractor videoExtractor = new MediaExtractor();
            MediaExtractor audioExtractor = new MediaExtractor();
            MediaMuxer muxer = null;
            Uri outputUri = null;
            ParcelFileDescriptor pfd = null;

            try {
                boolean isWebm = outputFile.getName().endsWith(".webm");
                int outFormat = isWebm
                        ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                        : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;

                // ── Create muxer ──────────────────────────────────────────────
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = context.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, outputFile.getName());
                    values.put(MediaStore.Downloads.MIME_TYPE, isWebm ? "video/webm" : "video/mp4");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/YTPRO");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    outputUri = resolver.insert(
                            MediaStore.Downloads.getContentUri("external"), values);

                    if (outputUri == null) throw new Exception("MediaStore insert returned null for output");

                    pfd = resolver.openFileDescriptor(outputUri, "rw");
                    if (pfd == null) throw new Exception("openFileDescriptor returned null");

                    FileDescriptor fd = pfd.getFileDescriptor();
                    muxer = new MediaMuxer(fd, outFormat);

                } else {
                    // API 21-28
                    muxer = new MediaMuxer(outputFile.getAbsolutePath(), outFormat);
                }

                // ── Set data sources ──────────────────────────────────────────
                // Both video and audio files were written by our app.
                // On API 29+ they physically exist on disk even though created
                // via MediaStore, so getAbsolutePath() works fine for MediaExtractor.
                try {
                    videoExtractor.setDataSource(videoFile.getAbsolutePath());
                } catch (Exception e) {
                    throw new Exception("Failed to read video file: " + e.getMessage());
                }

                if (audioFile != null && audioFile.exists()) {
                    try {
                        audioExtractor.setDataSource(audioFile.getAbsolutePath());
                    } catch (Exception e) {
                        throw new Exception("Failed to read audio file: " + e.getMessage());
                    }
                }

                // ── Video track ───────────────────────────────────────────────
                int muxerVideoTrackIndex = -1;
                for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                    MediaFormat format = videoExtractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        // Check if codec is supported on this device/API
                        if (!isCodecSupported(mime)) {
                            throw new Exception("Video codec not supported on this device: " + mime);
                        }
                        videoExtractor.selectTrack(i);
                        muxerVideoTrackIndex = muxer.addTrack(format);
                        break;
                    }
                }

                if (muxerVideoTrackIndex < 0) {
                    throw new Exception("No video track found in file");
                }

                // ── Audio track ───────────────────────────────────────────────
                int muxerAudioTrackIndex = -1;
                if (audioFile != null && audioFile.exists()) {
                    for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                        MediaFormat format = audioExtractor.getTrackFormat(i);
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) {
                            if (!isCodecSupported(mime)) {
                                // Audio codec not supported — log and skip
                                // instead of crashing, mux video only
                                Log.w(TAG, "Audio codec not supported, muxing video only: " + mime);
                                break;
                            }
                            audioExtractor.selectTrack(i);
                            muxerAudioTrackIndex = muxer.addTrack(format);
                            break;
                        }
                    }
                }

                // ── Start muxer ───────────────────────────────────────────────
                try {
                    muxer.start();
                } catch (IllegalStateException e) {
                    throw new Exception("Muxer failed to start: " + e.getMessage());
                }

                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                // ── Write video ───────────────────────────────────────────────
                try {
                    while (true) {
                        int sampleSize = videoExtractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) break;
                        info.offset = 0;
                        info.size = sampleSize;
                        info.presentationTimeUs = videoExtractor.getSampleTime();
                        info.flags = videoExtractor.getSampleFlags();
                        muxer.writeSampleData(muxerVideoTrackIndex, buffer, info);
                        videoExtractor.advance();
                    }
                } catch (Exception e) {
                    throw new Exception("Failed writing video samples: " + e.getMessage());
                }

                // ── Write audio ───────────────────────────────────────────────
                if (muxerAudioTrackIndex >= 0) {
                    buffer.clear();
                    try {
                        while (true) {
                            int sampleSize = audioExtractor.readSampleData(buffer, 0);
                            if (sampleSize < 0) break;
                            info.offset = 0;
                            info.size = sampleSize;
                            info.presentationTimeUs = audioExtractor.getSampleTime();
                            info.flags = audioExtractor.getSampleFlags();
                            muxer.writeSampleData(muxerAudioTrackIndex, buffer, info);
                            audioExtractor.advance();
                        }
                    } catch (Exception e) {
                        throw new Exception("Failed writing audio samples: " + e.getMessage());
                    }
                }

                // ── Stop muxer ────────────────────────────────────────────────
                try {
                    muxer.stop();
                    muxer.release();
                    muxer = null;
                } catch (IllegalStateException e) {
                    throw new Exception("Muxer failed to stop: " + e.getMessage());
                }

                // ── Finalize output in MediaStore ─────────────────────────────
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(outputUri, values, null, null);
                }
                
                // Explicitly scan the file to ensure duration is calculated for seekability
                MediaScannerConnection.scanFile(context, new String[]{outputFile.getAbsolutePath()}, null, (path, uri) -> {
                    Log.d(TAG, "Muxing scan finished: " + path);
                    if (callback != null) {
                        new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(new File(path)));
                    }
                });

                if (pfd != null) { pfd.close(); pfd = null; }

                Log.d(TAG, "Muxing successful: " + outputFile.getName());

            } catch (Exception e) {
                Log.e(TAG, "Mux failed: " + e.getMessage());

                // If output was created in MediaStore but muxing failed, delete it
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                    try {
                        context.getContentResolver().delete(outputUri, null, null);
                    } catch (Exception ignored) {}
                } else {
                    // API 21-28 — delete the broken output file
                    if (outputFile.exists()) outputFile.delete();
                }

                if (callback != null) {
                    final Exception err = e;
                    new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(err));
                }

            } finally {
                try { videoExtractor.release(); } catch (Exception ignored) {}
                try { audioExtractor.release(); } catch (Exception ignored) {}
                try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
                try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}

                // ── Delete temp input files ───────────────────────────────────
                // On API 29+ query MediaStore to get Uri then delete via resolver
                // On API 21-28 direct File.delete() works fine
                deleteFile(context, videoFile);
                if (audioFile != null) deleteFile(context, audioFile);
            }
        }).start();
    }

    // Deletes a file correctly for the current API level
    private static void deleteFile(Context context, File file) {
        if (!file.exists()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Query MediaStore for the Uri of this file by display name
            Uri collection = MediaStore.Downloads.getContentUri("external");
            String[] projection = {MediaStore.Downloads._ID};
            String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
            String[] args = {file.getName()};

            try (Cursor cursor = context.getContentResolver().query(
                    collection, projection, selection, args, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                    Uri uri = Uri.withAppendedPath(collection, String.valueOf(id));
                    context.getContentResolver().delete(uri, null, null);
                    Log.d(TAG, "Deleted via MediaStore: " + file.getName());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete via MediaStore: " + e.getMessage());
            }
        } else {
            // API 21-28
            file.delete();
        }
    }

    // Checks if a codec mime type is supported on this device
    private static boolean isCodecSupported(String mime) {
        try {
            android.media.MediaCodecList list =
                    new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
            for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
                for (String supported : info.getSupportedTypes()) {
                    if (supported.equalsIgnoreCase(mime)) return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Codec check failed: " + e.getMessage());
            // If check itself fails, let it try anyway
            return true;
        }
        return false;
    }
}