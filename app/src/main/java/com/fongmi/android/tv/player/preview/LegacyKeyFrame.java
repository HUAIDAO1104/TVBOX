package com.fongmi.android.tv.player.preview;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaCodecList;
import android.media.MediaDataSource;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.nio.ByteBuffer;

/** Android 9 retrievers can return a black RGB frame for a valid keyframe. Read the decoded YUV image directly. */
final class LegacyKeyFrame {
    private LegacyKeyFrame() {}

    static Bitmap read(PreviewDataSource source, long timeMs) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            // Releasing an extractor may close its input. The retriever fallback shares the
            // same pages, cancellation and byte budget; the outer request owns the source.
            ex.setDataSource(new MediaDataSource() {
                @Override public int readAt(long pos, byte[] b, int off, int len) throws java.io.IOException {
                    return source.readAt(pos, b, off, len);
                }
                @Override public long getSize() throws java.io.IOException { return source.getSize(); }
                @Override public void close() {}
            });
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat format = ex.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                // Keep thumbnail decoding separate from playback, with a size, time and input budget.
                if ((long)format.getInteger(MediaFormat.KEY_WIDTH) * format.getInteger(MediaFormat.KEY_HEIGHT) > 3840L * 2160) return null;
                ex.selectTrack(i);
                ex.seekTo(timeMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                int rotation = format.containsKey(MediaFormat.KEY_ROTATION) ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                // Do not reserve a second hardware playback decoder on older televisions.
                String software = softwareDecoder(mime);
                if (software == null) return null;
                decoder = MediaCodec.createByCodecName(software);
                decoder.configure(format, null, null, 0);
                decoder.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long deadline = SystemClock.elapsedRealtime() + 1200;
                boolean eos = false;
                int inputs = 0;
                while (source.availableForPreview() && SystemClock.elapsedRealtime() < deadline) {
                    if (!eos) {
                        int input = decoder.dequeueInputBuffer(2000);
                        if (input >= 0) {
                            ByteBuffer buffer = decoder.getInputBuffer(input);
                            if (buffer == null) return null;
                            int n = inputs < 12 ? ex.readSampleData(buffer, 0) : -1;
                            eos = n < 0;
                            decoder.queueInputBuffer(input, 0, Math.max(n, 0), eos ? 0 : ex.getSampleTime(),
                                    eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                            if (!eos) { ex.advance(); inputs++; }
                        }
                    }
                    int output = decoder.dequeueOutputBuffer(info, 3000);
                    if (output < 0) continue;
                    try {
                        if (info.size > 0) try (Image image = decoder.getOutputImage(output)) {
                            if (image != null && image.getFormat() == ImageFormat.YUV_420_888) return thumbnail(image, rotation);
                        }
                    } finally { decoder.releaseOutputBuffer(output, false); }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
                break;
            }
        } catch (Exception | OutOfMemoryError ignored) {
            // Device codec may not support image output. The caller can still try the retriever.
        } finally {
            if (decoder != null) try { decoder.release(); } catch (Exception ignored) {}
            ex.release();
        }
        return null;
    }

    private static String softwareDecoder(String mime) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            String name = info.getName().toLowerCase(java.util.Locale.ROOT);
            if (info.isEncoder() || !(name.startsWith("c2.android.") || name.startsWith("omx.google."))) continue;
            for (String type : info.getSupportedTypes()) if (mime.equalsIgnoreCase(type)) return info.getName();
        }
        return null;
    }

    private static Bitmap thumbnail(Image image, int rotation) {
        Rect crop = image.getCropRect();
        double scale = Math.min(1d, Math.min(320d / crop.width(), 180d / crop.height()));
        int w = Math.max(1, (int)(crop.width() * scale)), h = Math.max(1, (int)(crop.height() * scale));
        int[] rgb = new int[w * h];
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yPlane = planes[0].getBuffer(), uPlane = planes[1].getBuffer(), vPlane = planes[2].getBuffer();
        for (int y = 0; y < h; y++) {
            int sy = crop.top + y * crop.height() / h;
            int luma = yPlane.position() + sy * planes[0].getRowStride();
            int uRow = uPlane.position() + (sy / 2) * planes[1].getRowStride();
            int vRow = vPlane.position() + (sy / 2) * planes[2].getRowStride();
            for (int x = 0; x < w; x++) {
                int sx = crop.left + x * crop.width() / w;
                int c = Math.max(0, (yPlane.get(luma + sx * planes[0].getPixelStride()) & 255) - 16) * 298;
                int u = (uPlane.get(uRow + (sx / 2) * planes[1].getPixelStride()) & 255) - 128;
                int v = (vPlane.get(vRow + (sx / 2) * planes[2].getPixelStride()) & 255) - 128;
                rgb[y*w+x] = 0xff000000 | channel(c + 409*v) << 16 | channel(c - 100*u - 208*v) << 8 | channel(c + 516*u);
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(rgb, w, h, Bitmap.Config.ARGB_8888);
        if (rotation == 0) return bitmap;
        Matrix matrix = new Matrix(); matrix.postRotate(rotation);
        Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true);
        if (oriented != bitmap) bitmap.recycle();
        return oriented;
    }

    private static int channel(int value) { return Math.min(255, Math.max(0, (value + 128) >> 8)); }
}
