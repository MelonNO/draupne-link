/*
 *  This file is part of DroidDrone.
 *
 *  DroidDrone is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  DroidDrone is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with DroidDrone.  If not, see <https://www.gnu.org/licenses/>.
 *
 *  Modified for DRAUPNE Link (pc.34): one channel, not two. The phone's microphones give one sound anyway, and the
 *  live audio to the PC (PcAudio, 48 kbit/s) gets twice the bits per channel for it; the phone's own recording takes
 *  the format from here.
 */

package de.droiddrone.flight;

import static de.droiddrone.common.Logcat.log;
import static de.droiddrone.common.Utils.getNextPow2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

import de.droiddrone.common.MediaCommon;

public class AudioSource {
    private static final int sampleRate = 48000;
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private static final int encoding = AudioFormat.ENCODING_PCM_16BIT;
    private static final int[] audioSources = {MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT};
    private final Context context;
    private final ArrayBlockingQueue<byte[]> mp4Buffer = new ArrayBlockingQueue<>(30);
    private final ArrayBlockingQueue<byte[]> streamBuffer = new ArrayBlockingQueue<>(30);
    /**
     * DRAUPNE Link (pc.38, the review's L4): how long a consumer waits for the microphone's next buffer (about 10 ms
     * of sound each) - the encoder loops used to ask again at once, a CPU core spinning on an empty queue.
     */
    static final long PCM_WAIT_MS = 15;
    private volatile AudioRecord audioRecord;
    private int bytesToRead;
    private AudioFormat audioFormat;
    private volatile boolean isInitialized;
    private volatile boolean usedInMp4;
    private volatile boolean usedInStream;
    private int audioSourceId;
    /**
     * pc.38 (L4): the reader thread's generation. A quick close and initialize left the old reader running beside the
     * new one (both saw isInitialized true again, both read the new AudioRecord); each reader now stops as soon as
     * its generation has passed, and reads only the AudioRecord it was started with.
     */
    private volatile int readerGeneration;

    public AudioSource(Context context){
        this.context = context;
        isInitialized = false;
        usedInMp4 = false;
        usedInStream = false;
        audioSourceId = 0;
    }

    public synchronized boolean initialize(int consumerId){
        if (isInitialized){
            setConsumer(consumerId);
            return true;
        }
        audioFormat = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();
        bytesToRead = AudioRecord.getMinBufferSize(audioFormat.getSampleRate(), channelConfig, audioFormat.getEncoding());
        bytesToRead = getNextPow2(bytesToRead) / 2;
        boolean started = startAudioRecord();
        if (!started) return false;
        isInitialized = true;
        setConsumer(consumerId);
        final int generation = ++readerGeneration;
        final AudioRecord record = audioRecord;
        final int toRead = bytesToRead;
        Thread audioThread = new Thread(() -> readLoop(generation, record, toRead));
        audioThread.setDaemon(false);
        audioThread.setName("audioThread");
        audioThread.start();
        return true;
    }

    /** One reader for one AudioRecord, until its generation passes (close, or a new initialize). */
    private void readLoop(int generation, AudioRecord record, int toRead) {
        ByteBuffer buf = ByteBuffer.allocateDirect(toRead);
        while (generation == readerGeneration){
            try {
                if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING || record.getState() != AudioRecord.STATE_INITIALIZED) {
                    Thread.sleep(20); // pc.38: was 1 ms, a thousand wake-ups a second while the microphone starts
                    continue;
                }
                buf.position(0);
                int size = record.read(buf, toRead, AudioRecord.READ_BLOCKING);
                if (size <= 0) {
                    Thread.sleep(20); // an error (the record released under us, a dead microphone): not at full speed
                    continue;
                }
                if (generation != readerGeneration) return; // closed while this read waited: its sound isn't the new one's
                byte[] data = new byte[size];
                buf.get(data, 0, size);
                if (usedInMp4) mp4Buffer.offer(data);
                if (usedInStream) streamBuffer.offer(data);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                return; // the record was released: a newer reader (if any) has its own
            }
        }
    }

    /** The next buffer for this consumer, waiting up to {@link #PCM_WAIT_MS} for it (pc.38: no busy loop); null if none. */
    public byte[] getBufferData(int consumerId){
        try {
            if (consumerId == MediaCommon.mp4AudioConsumerId) return mp4Buffer.poll(PCM_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (consumerId == MediaCommon.streamAudioConsumerId) return streamBuffer.poll(PCM_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    public long getTimestamp(){
        AudioTimestamp timestamp = new AudioTimestamp();
        audioRecord.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC);
        return timestamp.nanoTime/1000;
    }

    public AudioFormat getAudioFormat(){
        return audioFormat;
    }

    public int getBufferSize(){
        return bytesToRead;
    }

    private boolean startAudioRecord(){
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            log("startAudioRecord error: no permission");
            return false;
        }
        try {
            audioRecord = new AudioRecord.Builder()
                    .setAudioSource(audioSources[audioSourceId])
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bytesToRead * 4)
                    .build();
            audioRecord.startRecording();
        }catch (Exception e){
            if (audioSourceId < audioSources.length - 1){
                audioSourceId++;
                return startAudioRecord();
            }
            log("AudioRecord error: " + e);
            return false;
        }
        return true;
    }

    private void setConsumer(int consumerId){
        if (consumerId == MediaCommon.mp4AudioConsumerId) usedInMp4 = true;
        if (consumerId == MediaCommon.streamAudioConsumerId) usedInStream = true;
    }

    public synchronized void stop(int consumerId){
        if (consumerId == MediaCommon.mp4AudioConsumerId) {
            usedInMp4 = false;
            mp4Buffer.clear();
        }
        if (consumerId == MediaCommon.streamAudioConsumerId) {
            usedInStream = false;
            streamBuffer.clear();
        }
        if (!usedInMp4 && !usedInStream) close();
    }

    public synchronized void close(){
        if (!isInitialized) return;
        isInitialized = false;
        readerGeneration++; // pc.38: the reader stops at its next turn, even if an initialize follows at once
        usedInMp4 = false;
        usedInStream = false;
        try {
            audioRecord.stop();
        }catch (Exception e){
            e.printStackTrace();
        }
        mp4Buffer.clear();
        streamBuffer.clear();
        audioRecord.release();
    }
}
