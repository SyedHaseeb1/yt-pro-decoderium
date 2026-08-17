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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class MediaMuxerUtils {

    private static final String TAG = "YTPRO_MEDIA";

    public interface MuxCallback {
        void onSuccess(File outputFile);
        void onFailure(Exception e);
    }

    public static void fixSeekability(Context context, File sourceFile, MuxCallback callback) {
        fixSeekability(context, Uri.fromFile(sourceFile), sourceFile.getName(), callback);
    }

    public static void fixSeekability(Context context, Uri sourceUri, String displayName, MuxCallback callback) {
        new Thread(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaMuxer muxer = null;

            // Use internal cache directory to avoid EPERM/Scoped Storage issues during muxing
            File tempFile = new File(context.getCacheDir(), "fixing_" + System.currentTimeMillis() + "_" + displayName);
            ParcelFileDescriptor sourcePfd = null;

            try {
                sourcePfd = context.getContentResolver().openFileDescriptor(sourceUri, "r");
                if (sourcePfd == null) throw new Exception("Failed to open source URI");

                extractor.setDataSource(sourcePfd.getFileDescriptor());

                int trackCount = extractor.getTrackCount();
                int videoTrackIndex = -1;
                int audioTrackIndex = -1;
                String videoMime = null;
                int maxVideoWidth = -1;

                // 1. First pass: Find tracks and detect codec
                String audioMime = null;
                for (int i = 0; i < trackCount; i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;

                    if (mime.startsWith("video/")) {
                        int width = format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
                        if (width > maxVideoWidth) {
                            maxVideoWidth = width;
                            videoTrackIndex = i;
                            videoMime = mime;
                        }
                    } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                        audioTrackIndex = i;
                        audioMime = mime;
                    }
                }

                // 2. Check if AV1 - use FFmpeg for remuxing
                boolean isAV1 = videoMime != null && videoMime.contains("av01");
                if (isAV1) {
                    remuxWithFFmpeg(context, sourceUri, displayName, tempFile, callback);
                    return;
                }

                // 3. Decide Output Format (VP9 must be WEBM for stability)
                int outFormat;
                boolean isVP9 = videoMime != null && videoMime.contains("vp9");

                boolean isWebMOnlyAudio = audioMime != null && (
                    audioMime.contains("opus") ||
                    audioMime.contains("vorbis")
                );

                if (isVP9 || isWebMOnlyAudio || displayName.toLowerCase().endsWith(".webm")) {
                    outFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
                } else {
                    outFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
                }

                muxer = new MediaMuxer(tempFile.getAbsolutePath(), outFormat);

                int muxerVideoTrackIndex = -1;
                int muxerAudioTrackIndex = -1;

                if (videoTrackIndex != -1) {
                    extractor.selectTrack(videoTrackIndex);
                    muxerVideoTrackIndex = muxer.addTrack(extractor.getTrackFormat(videoTrackIndex));
                }
                if (audioTrackIndex != -1) {
                    extractor.selectTrack(audioTrackIndex);
                    muxerAudioTrackIndex = muxer.addTrack(extractor.getTrackFormat(audioTrackIndex));
                }

                if (muxerVideoTrackIndex == -1 && muxerAudioTrackIndex == -1) {
                    throw new Exception("No valid tracks found");
                }

                muxer.start();

                ByteBuffer buffer = ByteBuffer.allocate(8 * 1024 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                // Get global start offset to keep sync
                long minStartTime = Long.MAX_VALUE;
                for (int i = 0; i < trackCount; i++) {
                    extractor.selectTrack(i);
                    long time = extractor.getSampleTime();
                    if (time != -1 && time < minStartTime) minStartTime = time;
                    extractor.unselectTrack(i);
                }
                if (minStartTime == Long.MAX_VALUE) minStartTime = 0;
                
                if (videoTrackIndex != -1) extractor.selectTrack(videoTrackIndex);
                if (audioTrackIndex != -1) extractor.selectTrack(audioTrackIndex);

                while (true) {
                    int sampleTrackIndex = extractor.getSampleTrackIndex();
                    if (sampleTrackIndex < 0) break;

                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;

                    int targetIndex = (sampleTrackIndex == videoTrackIndex) ? muxerVideoTrackIndex :
                                     (sampleTrackIndex == audioTrackIndex) ? muxerAudioTrackIndex : -1;

                    if (targetIndex != -1) {
                        info.offset = 0;
                        info.size = sampleSize;
                        info.flags = extractor.getSampleFlags();
                        info.presentationTimeUs = Math.max(0, extractor.getSampleTime() - minStartTime);
                        muxer.writeSampleData(targetIndex, buffer, info);
                    }
                    extractor.advance();
                }

                muxer.stop();
                muxer.release();
                muxer = null;

                // ── Cleanup and File Placement ──

                // 1. Create final filename with suffix and correct extension
                String baseName = displayName;
                String extension = "";
                int lastDot = displayName.lastIndexOf('.');
                if (lastDot > 0) {
                    baseName = displayName.substring(0, lastDot);
                    extension = displayName.substring(lastDot);
                }
                
                // Override extension if we changed the container to WebM
                if (outFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM) {
                    extension = ".webm";
                }
                
                String finalName = baseName + "_ytpro" + extension;

                // 2. Delete the original unseekable file
                if ("content".equals(sourceUri.getScheme())) {
                    context.getContentResolver().delete(sourceUri, null, null);
                } else {
                    File oldFile = new File(sourceUri.getPath());
                    if (oldFile.exists()) oldFile.delete();
                }

                // 3. Move the fixed file from internal cache to public Download/YTPRO
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, finalName);
                    values.put(MediaStore.Downloads.MIME_TYPE, finalName.toLowerCase().endsWith(".webm") ? "video/webm" : "video/mp4");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/YTPRO");
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    
                    Uri finalUri = context.getContentResolver().insert(MediaStore.Downloads.getContentUri("external"), values);
                    if (finalUri != null) {
                        try (OutputStream os = context.getContentResolver().openOutputStream(finalUri);
                             InputStream is = new FileInputStream(tempFile)) {
                            byte[] ioBuf = new byte[1024 * 1024];
                            int len;
                            while ((len = is.read(ioBuf)) != -1) {
                                os.write(ioBuf, 0, len);
                            }
                        }
                    }
                } else {
                    File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                    File ytProDir = new File(downloadDir, "YTPRO");
                    if (!ytProDir.exists()) ytProDir.mkdirs();
                    File finalFile = new File(ytProDir, finalName);
                    tempFile.renameTo(finalFile);
                    MediaScannerConnection.scanFile(context, new String[]{finalFile.getAbsolutePath()}, null, null);
                }
                
                tempFile.delete();

                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(new File(finalName)));
                }

            } catch (Exception e) {
                Log.e(TAG, "Fix failed", e);
                if (tempFile.exists()) tempFile.delete();
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(e));
                }
            } finally {
                extractor.release();
                if (muxer != null) { try { muxer.stop(); } catch (Exception ignored) {} try { muxer.release(); } catch (Exception ignored) {} }
                if (sourcePfd != null) { try { sourcePfd.close(); } catch (Exception ignored) {} }
            }
        }).start();
    }

    private static void remuxWithFFmpeg(Context context, Uri sourceUri, String displayName, File tempFile, MuxCallback callback) {
        try {
            // Get source file path
            String sourceFile;
            if ("content".equals(sourceUri.getScheme())) {
                File cacheFile = new File(context.getCacheDir(), "ffmpeg_src_" + System.currentTimeMillis());
                try (InputStream is = context.getContentResolver().openInputStream(sourceUri);
                     OutputStream os = new java.io.FileOutputStream(cacheFile)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        os.write(buffer, 0, len);
                    }
                }
                sourceFile = cacheFile.getAbsolutePath();
            } else {
                sourceFile = sourceUri.getPath();
            }

            // Remux with FFmpeg to WebM (preserves all codecs)
            String ffmpegCmd = String.format("-i \"%s\" -c copy -y \"%s\"",
                sourceFile, tempFile.getAbsolutePath());

            int returnCode = FFmpegKit.execute(ffmpegCmd).getReturnCode().getValue();
            if (returnCode != 0) {
                throw new Exception("FFmpeg remux failed with code: " + returnCode);
            }

            // Delete original and move fixed file
            if ("content".equals(sourceUri.getScheme())) {
                context.getContentResolver().delete(sourceUri, null, null);
            } else {
                File oldFile = new File(sourceUri.getPath());
                if (oldFile.exists()) oldFile.delete();
            }

            // Move fixed file to Downloads
            String baseName = displayName;
            int lastDot = displayName.lastIndexOf('.');
            if (lastDot > 0) {
                baseName = displayName.substring(0, lastDot);
            }
            String finalName = baseName + "_ytpro.webm";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, finalName);
                values.put(MediaStore.Downloads.MIME_TYPE, "video/webm");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/YTPRO");
                values.put(MediaStore.Downloads.IS_PENDING, 0);

                Uri finalUri = context.getContentResolver().insert(MediaStore.Downloads.getContentUri("external"), values);
                if (finalUri != null) {
                    try (OutputStream os = context.getContentResolver().openOutputStream(finalUri);
                         InputStream is = new FileInputStream(tempFile)) {
                        byte[] ioBuf = new byte[1024 * 1024];
                        int len;
                        while ((len = is.read(ioBuf)) != -1) {
                            os.write(ioBuf, 0, len);
                        }
                    }
                }
            } else {
                File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                File ytProDir = new File(downloadDir, "YTPRO");
                if (!ytProDir.exists()) ytProDir.mkdirs();
                File finalFile = new File(ytProDir, finalName);
                tempFile.renameTo(finalFile);
                MediaScannerConnection.scanFile(context, new String[]{finalFile.getAbsolutePath()}, null, null);
            }

            tempFile.delete();

            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(new File(finalName)));
            }
        } catch (Exception e) {
            Log.e(TAG, "FFmpeg remux failed", e);
            if (tempFile.exists()) tempFile.delete();
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(e));
            }
        }
    }

    public static void muxVideoAudio(Context context, File videoFile, File audioFile, File outputFile, MuxCallback callback) {
        // Not used currently but kept for structure
    }

    private static void deleteFile(Context context, File file) {
        if (!file.exists()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = MediaStore.Downloads.getContentUri("external");
            String[] projection = {MediaStore.Downloads._ID};
            String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
            String[] args = {file.getName()};
            try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, args, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                    Uri uri = Uri.withAppendedPath(collection, String.valueOf(id));
                    context.getContentResolver().delete(uri, null, null);
                }
            } catch (Exception e) {
                Log.e(TAG, "Delete failed", e);
            }
        } else {
            file.delete();
        }
    }
}
