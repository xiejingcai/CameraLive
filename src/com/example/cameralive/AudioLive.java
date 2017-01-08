package com.example.cameralive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.MediaRecorder.AudioSource;
import android.util.Log;

public class AudioLive {

	/*
	 * pcm frame buffer queue while encoding
	 * aac frame buffer queue while decoding
	 * */
	private ArrayBlockingQueue<BufferUnit> queue;
	
	/*mode 
	 * 0: encode
	 * 1: decode
	 * */
	private int mode = 0;
	
	private AudioRecord mAR;
	private MediaCodec encoder;
	private MediaCodec decoder;
	private MediaCodec.BufferInfo info;
	private AudioTrack mAT;
	private MediaFormat mMF;
	private Thread mThread;
	private int buffer_size = 0;
	private int min_buffer_size = 0;
	
	private byte[] adtsHeader = new byte[7];
	private static final int SAMPLE_RATE = 44100;	// 44.1[KHz] is only setting guaranteed to be available on all devices.
    private static final int BIT_RATE = 64000;
	public static final int SAMPLES_PER_FRAME = 1024;	// AAC, bytes/frame/channel
	public static final int FRAMES_PER_BUFFER = 25; 	// AAC, frame/buffer/sec
	
	private boolean isNeedExit = false;
	
	public AudioLive(int m, ArrayBlockingQueue<BufferUnit> bq){
		mode = m;
		queue = bq;
		if (mode == 0) {
			min_buffer_size = AudioRecord.getMinBufferSize(SAMPLE_RATE,
					AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
			buffer_size = SAMPLES_PER_FRAME * FRAMES_PER_BUFFER;
			
			if (buffer_size < min_buffer_size)
				buffer_size = ((min_buffer_size / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;
			
			mAR = new AudioRecord(AudioSource.MIC, SAMPLE_RATE,
					AudioFormat.CHANNEL_IN_MONO,
					AudioFormat.ENCODING_PCM_16BIT, buffer_size);
			mAR.startRecording();

			mMF = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
					SAMPLE_RATE, 1);
			mMF.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
			mMF.setInteger(MediaFormat.KEY_AAC_PROFILE,
					MediaCodecInfo.CodecProfileLevel.AACObjectLC);
			mMF.setInteger(MediaFormat.KEY_CHANNEL_MASK,
					AudioFormat.CHANNEL_IN_MONO);
			try {
				encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			encoder.configure(mMF, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			encoder.start();
			info = new MediaCodec.BufferInfo();			
		} else {
			buffer_size = AudioTrack
					.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
							AudioFormat.ENCODING_PCM_16BIT);
			mAT = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
					AudioFormat.CHANNEL_OUT_STEREO,
					AudioFormat.ENCODING_PCM_16BIT, buffer_size,
					AudioTrack.MODE_STREAM);
			mAT.play();
			
			mMF = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
					SAMPLE_RATE, 1);
			mMF.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
			mMF.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
			mMF.setInteger(MediaFormat.KEY_AAC_PROFILE,
					MediaCodecInfo.CodecProfileLevel.AACObjectLC);
			mMF.setInteger(MediaFormat.KEY_CHANNEL_MASK,
					AudioFormat.CHANNEL_IN_MONO);
			mMF.setInteger(MediaFormat.KEY_IS_ADTS,1);
			
			try {
				decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			decoder.configure(mMF, null, null, 0);
			decoder.start();
			info = new MediaCodec.BufferInfo();
		}
	}
	public MediaFormat getFormat(){
		return mMF;
	}

	public void start() {
		isNeedExit = false;
		mThread = new Thread(new Runnable() {
			@Override
			public void run() {
				final ByteBuffer buf = ByteBuffer
						.allocateDirect(SAMPLES_PER_FRAME);
				while (!isNeedExit) {
					if (mode == 0) {
						Encode_Push(buf);
					}else{
						//Decode_Rend(buf);
					}
				}
			}
		});
		mThread.start();
	}

	protected void Decode_Rend(ByteBuffer buf) {
		buf.clear();
		BufferUnit sample = queue.poll();
		if (sample != null) {
			if (sample.buffer[1] == 0){
				System.arraycopy(sample.buffer, 4,adtsHeader , 0, 7);	
				byte[] aacconfig = new byte[2];
				aacconfig[0] = sample.buffer[2];
				aacconfig[0] = sample.buffer[3];
				buf.clear();
				buf.put(aacconfig);
				mMF.setByteBuffer("csd-0",buf);
				decoder.configure(mMF, null, null, 0);
				decoder.start();
				return ;
			}
			int inIndex = decoder.dequeueInputBuffer(10000);
			if (inIndex >= 0) {
				Log.e("AudioLive", "raw ...");
				ByteBuffer buffer = decoder.getInputBuffer(inIndex);
				buffer.clear();
				byte[] aacbuffer = new byte[7+sample.buffer.length-2];
				System.arraycopy(adtsHeader,0,aacbuffer,0,7);
				System.arraycopy(sample.buffer,2,aacbuffer,7,sample.buffer.length-2);
				buffer.put(aacbuffer);						
				decoder.queueInputBuffer(inIndex, 0, sample.buffer.length - 2 + 7, sample.pts, 0);
			}
			
			int outIndex = decoder.dequeueOutputBuffer(info, 10000);
			if(outIndex>=0){
				Log.e("AudioLive", "pcm ...");
				ByteBuffer pcmbuffer = decoder.getOutputBuffer(outIndex);
				mAT.write(pcmbuffer, info.size,AudioTrack.WRITE_NON_BLOCKING);
				decoder.releaseOutputBuffer(outIndex, false);
			}
		}
	}
	protected void Encode_Push(ByteBuffer buf) {
		buf.clear();
		int readBytes = mAR.read(buf, SAMPLES_PER_FRAME); 
		if (readBytes > 0) {
			buf.position(readBytes);
			buf.flip();
			
			int inIndex = encoder.dequeueInputBuffer(10000);
			if(inIndex>=0){
				ByteBuffer buffer = encoder.getInputBuffer(inIndex);
				buffer.clear();
				buffer.put(buf);
				encoder.queueInputBuffer(inIndex, 0, readBytes, (System.nanoTime() / 1000), 0);
			}
			int outIndex = encoder.dequeueOutputBuffer(info, 10000);
			if(outIndex>=0){
				ByteBuffer buffer = encoder.getOutputBuffer(outIndex);
				MainActivity.mFlvMuxer.writeSampleData(MainActivity.atrack, buffer, info);
				encoder.releaseOutputBuffer(outIndex, false);
			}
		}
	}
	public void stop(){
		isNeedExit = true;
		try {
			mThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		if(mode == 0){			
			if(encoder!=null){
				encoder.stop();
				encoder.release();
				encoder = null;
			}
			if(mAR!=null){
				mAR.stop();
				mAR.release();
				mAR = null;
			}
		}else{
			if(decoder!=null){
				decoder.stop();
				decoder.release();
				decoder = null;
			}
			if(mAT!=null){
				mAT.stop();
				mAT.release();
				mAT = null;
			}
		}
	}
	
}
