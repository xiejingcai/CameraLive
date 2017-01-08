package com.example.cameralive;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class PlayerPreview extends SurfaceView implements SurfaceHolder.Callback {

	private static final String SAMPLE = Environment.getExternalStorageDirectory() + "/video.mp4";
	
	private PlayerThread mPlayer = null;
	private SurfaceHolder mHolder = null;
	private Boolean isNeedExit = false;

	public PlayerPreview(Context context) {
		super(context);
		mHolder = getHolder();
		mHolder.addCallback(this);
		// TODO Auto-generated constructor stub
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		// The Surface has been created, now tell the decoder where to draw the preview.
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // If your preview can change or rotate, take care of those events here.
        // Make sure to stop the preview before resizing or reformatting it.
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
	}
	
	public void Start(){
		if (mPlayer == null) {
			mPlayer = new PlayerThread(mHolder.getSurface());
			mPlayer.start();
		}
	}
	public void Stop(){
		if (mPlayer != null) {
			isNeedExit = true;	
			mPlayer.exit();
			try {
				mPlayer.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
		
	private class PlayerThread extends Thread {	
		
		private Surface surface;
		private MediaCodec decoder=null;
		private MediaFormat format;
		private BufferUnit sample = null;
		private long pTS = 0;
		private int size = 0;
		private int type = 0;
		private byte[] h264startcode = {(byte)0,(byte)0,(byte)0,(byte)1};
		private byte[] sps;
		private byte[] pps;
		private int pTSdelta = 1000*1000/MainActivity.mFps;
		private long startMs = 0;
		
		Thread mFeedThread;
		Thread mRenderThread;
		
		private void tagDataParse(byte[] tbf) {
			int nal_startpos = 5;// first nal start position, nal size
			int tbf_length = tbf.length;
			int sps_len = 0;
			int pps_len = 0;
			
			if (tbf[1] == 0) {// config, sps pps				
				type = 0;
				size = tbf_length - 1;
				sps_len = ((tbf[11]&0xff)<<8)|(tbf[12]&0xff);
				pps_len = ((tbf[13+sps_len+1]&0xff)<<8)|(tbf[13+sps_len+2]&0xff);
				sps = new byte[sps_len+4];
				pps = new byte[pps_len+4];				
				System.arraycopy(h264startcode, 0, sps, 0, 4);
				System.arraycopy(tbf, 13, sps, 4, sps_len);
				System.arraycopy(h264startcode, 0, pps, 0, 4);
				System.arraycopy(tbf, 13+sps_len+3, pps, 4, pps_len);	
			} else if (tbf[1] == 1) {//nulu
				if(0x17==(tbf[0]&0xff)){//key frame
					type = 1;
				}else if(0x27==(tbf[0]&0xff)){//inter frame
					type = 2;
				}
				size = tbf_length-nal_startpos;
				System.arraycopy(h264startcode, 0, tbf, nal_startpos, 4);				
			} else {				
				type = 3;
				size = 0;
			}
		}
		
		public PlayerThread(Surface surface) {
			this.surface = surface;			
		}	 			        	       
		public void exit(){
			try {
				mFeedThread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			try {
				mRenderThread.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			if(decoder != null){				
				decoder.stop();
				decoder.release();
				decoder = null;
			}
		}
		@Override
		public void run() {

			try {
				decoder = MediaCodec.createDecoderByType(MainActivity.Vmimetype);
			} catch (IOException e1) {
				e1.printStackTrace();
			}
			format = MediaFormat.createVideoFormat(MainActivity.Vmimetype, MainActivity.mResolutionX,MainActivity.mResolutionY );
			format.setInteger(MediaFormat.KEY_ROTATION, 270);
			decoder.configure(format, surface, null, 0);
			decoder.start();		
		
			mFeedThread = new Thread(new Runnable(){
				@Override
				public void run() {
					startMs = 0;
					ByteBuffer[] inputBuffers = decoder.getInputBuffers();	
					int inIndex = -1;
					while ((!isNeedExit) && (decoder != null)) {
						if(sample==null){
							sample = MainActivity.AVCQueue.poll();	
							if(sample!=null){
								tagDataParse(sample.buffer);	
								pTS = sample.pts*1000;
							}else{
								try {
									sleep(5);
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
								continue;
							}
						}
						
						inIndex = decoder.dequeueInputBuffer(10000);
						if (inIndex >= 0) {
							ByteBuffer buffer = inputBuffers[inIndex];
							if (type == 0) {
								buffer.put(sps);
								decoder.queueInputBuffer(inIndex, 0,
										sps.length, pTS,
										MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
								
								inIndex = decoder.dequeueInputBuffer(10000);
								if (inIndex >= 0) {
									buffer = inputBuffers[inIndex];
									buffer.put(pps);
									decoder.queueInputBuffer(inIndex, 0,
											pps.length, pTS,
											MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
								}
							} else if (type == 1) {
								buffer.put(sample.buffer);
								decoder.queueInputBuffer(inIndex, 5, size, pTS,
										MediaCodec.BUFFER_FLAG_KEY_FRAME);
							} else if (type == 2) {
								buffer.put(sample.buffer);
								decoder.queueInputBuffer(inIndex, 5, size, pTS,
										0);
							}
							sample = null;
						}						
					}
					Log.e("mFeedThread::run","End");
				}
			});
			mFeedThread.start();
			Log.e("mFeedThread::start","Start");
						
			mRenderThread = new Thread(new Runnable() {				
				@Override
				public void run() {
					BufferInfo info = new BufferInfo();
					int outIndex = -1;												
					while ((!isNeedExit) && (decoder != null)) {
						outIndex = decoder.dequeueOutputBuffer(info, 10000);
						if (outIndex >= 0) {	
							if(decoder!=null){
								decoder.releaseOutputBuffer(outIndex, true);
							}
						}											
					}
					Log.e("mRenderThread::run","End");
				}
			});
			mRenderThread.start();
			Log.e("mRenderThread::start","Start");
		}
	}
}
