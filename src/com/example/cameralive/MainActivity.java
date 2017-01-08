package com.example.cameralive;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;

import com.example.cameralive.R;
import com.github.faucamp.simplertmp.DefaultRtmpPublisher;
import com.github.faucamp.simplertmp.RtmpHandler;
import com.github.faucamp.simplertmp.RtmpHandler.RtmpListener;
import com.github.faucamp.simplertmp.output.FlvWriter;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodec.CodecException;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;


public class MainActivity extends Activity implements RtmpHandler.RtmpListener{
	
	private static final String TAG = "MainActivity";
	private static final int MEDIA_TYPE_VIDEO = 2;
	private static final int MEDIA_TYPE_IMAGE = 1;
	private static final int OPT_RECORD = 0;
	private static final int OPT_STREAM = 1;
	private int streamstate = 0;
	private int recordstate = 0;
	private int playstate = 0;
	private Camera mCamera = null;
	private Camera.Parameters mParam = null;
	private int mCameraId = 1;
	
	public static String Vmimetype = "video/avc";
	public String Amimetype = "audio/aac";
	public static int mBitrate = 1024*1024;
	public static int mResolutionX = 1280;
	public static int mResolutionY = 720;
	public static int mFps = 15;
	public int mOrientation = 90;
	public static String dsturl = "rtmp://192.168.1.107:1935/rtmp/live";
	public static int YUVQ_num = 5;
	public static int AVCQ_num = 30;
	public static int AACQ_num = 20;
	public EditText mEditURL = null;
	
	private CameraPreview mPreview;
	private PlayerPreview mPlayerPreview;
    
    private MediaRecorder mMediaRecorder = null;
    
    public MediaCodec mEncoder = null;
    public MediaFormat mEncFormat= null;
    
    public MediaCodec mDecoder = null;
    public MediaFormat mDecFormat= null;
    
    public static ArrayBlockingQueue<byte[]> YUVQueue = new ArrayBlockingQueue<byte[]>(YUVQ_num); 
    
    public static ArrayBlockingQueue<BufferUnit> AVCQueue = new ArrayBlockingQueue<BufferUnit>(AVCQ_num);
    
    public static ArrayBlockingQueue<BufferUnit> AACQueue = new ArrayBlockingQueue<BufferUnit>(AVCQ_num);
    
	public static FlvMuxer mFlvMuxer;
	public static int vtrack = -1;
	public static int atrack = -1;
	
	public static FlvDeMuxer mFlvDeMuxer = null;
	public FlvWriter mFlvWriter =null;
	
	public AudioLive mAL;
	
	private SoundPool mSoundPool;
	int mSoundID;
	int mSoundID1;
	
	private void CheckPermission(){
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED){
			ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA,
					Manifest.permission.WRITE_EXTERNAL_STORAGE,
					Manifest.permission.INTERNET,
					Manifest.permission.RECORD_AUDIO},1);
		}
	}
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mEditURL = (EditText) findViewById(R.id.URL);
        mEditURL.setText(dsturl);
        mSoundPool = new SoundPool(10, AudioManager.STREAM_SYSTEM, 5);
        mSoundID = mSoundPool.load(this,R.raw.stream, 1);
        mSoundID1 = mSoundPool.load(this,R.raw.play, 1);
        
        CheckPermission();
    }
    private void initCamera(){
        try{
        	mCamera =  Camera.open(mCameraId);
        }catch(Exception e){
        	e.printStackTrace(); 
        	Log.e(TAG, "Camera.open"+e);
        }   
        
        mParam = mCamera.getParameters();
        mParam.setPictureFormat(ImageFormat.NV21);
        mParam.setPreviewSize(mResolutionX,mResolutionY);
        mParam.setPreviewFrameRate(mFps);
        mCamera.setDisplayOrientation(mOrientation);
        mCamera.setParameters(mParam);
       
        mPreview = new CameraPreview(this, mCamera);
        FrameLayout preview = (FrameLayout) findViewById(R.id.CameraPreview); 
        preview.addView(mPreview);
    }
    private void releaseCamera(){
    	FrameLayout preview = (FrameLayout) findViewById(R.id.CameraPreview); 
    	preview.removeView(mPreview);
    	mCamera.stopPreview();
    	mCamera.release();
    }
    
    /** Create a file Uri for saving an image or video */
    private static Uri getOutputMediaFileUri(int type){
          return Uri.fromFile(getOutputMediaFile(type));
    }

    /** Create a File for saving an image or video */
    private static File getOutputMediaFile(int type){
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                  Environment.DIRECTORY_MOVIES), "MyCameraApp");
        // This location works best if you want the created images to be shared
        // between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (! mediaStorageDir.exists()){
            if (! mediaStorageDir.mkdirs()){
                Log.d("MyCameraApp", "failed to create directory");
                return null;
            }
        }

        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File mediaFile;
        if (type == MEDIA_TYPE_IMAGE){
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
            "IMG_"+ timeStamp + ".jpg");
        } else if(type == MEDIA_TYPE_VIDEO) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
            "VID_"+ timeStamp + ".mp4");
        } else {
            return null;
        }

        return mediaFile;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void MediaRecorderStart(){
        mMediaRecorder = new MediaRecorder();
        mCamera.unlock();
        mMediaRecorder.setCamera(mCamera);
        
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_720P));   

        mMediaRecorder.setOutputFile(getOutputMediaFile(MEDIA_TYPE_VIDEO).toString());       

        mMediaRecorder.setOrientationHint(270); 
        try {
			mMediaRecorder.prepare();
		} catch (IllegalStateException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
		mMediaRecorder.start();
    }
    
    public void MediaRecorderStop(){
   		if (mMediaRecorder != null) {
			mMediaRecorder.stop();
	        mMediaRecorder.reset();   // clear recorder configuration
	        mMediaRecorder.release(); // release the recorder object
	        mMediaRecorder = null;
	        mCamera.lock();           // lock camera for later use
	    }
    }
    private int getSupportColorFormat() {
        int numCodecs = MediaCodecList.getCodecCount();
        MediaCodecInfo codecInfo = null;
        for (int i = 0; i < numCodecs && codecInfo == null; i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (!info.isEncoder()) {
                continue;
            }
            String[] types = info.getSupportedTypes();
            boolean found = false;
            for (int j = 0; j < types.length && !found; j++) {
                if (types[j].equals(Vmimetype)) {
                    System.out.println("found");
                    found = true;
                }
            }
            if (!found)
                continue;
            codecInfo = info;
        }

        Log.e("AvcEncoder", "Found " + codecInfo.getName() + " supporting " + Vmimetype);

        // Find a color profile that the codec supports
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(Vmimetype);
        Log.e("AvcEncoder",
                "length-" + capabilities.colorFormats.length + "==" + Arrays.toString(capabilities.colorFormats));

        for (int i = 0; i < capabilities.colorFormats.length; i++) {

            switch (capabilities.colorFormats[i]) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:

                Log.e("AvcEncoder", "supported color format::" + capabilities.colorFormats[i]);
                return capabilities.colorFormats[i];
            default:
                Log.e("AvcEncoder", "unsupported color format " + capabilities.colorFormats[i]);
                break;
            }
        }

        return -1;
    }

    public void MediaStreamStart(){
    	/*init video encoder*/
    	try {
			mEncoder = MediaCodec.createEncoderByType(Vmimetype);
		} catch (IOException e) {
			e.printStackTrace();
		}
    	int m_SupportColorFormat = getSupportColorFormat();
    	mEncFormat = MediaFormat.createVideoFormat(Vmimetype, mResolutionX, mResolutionY);
    	mEncFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);  
    	mEncFormat.setInteger(MediaFormat.KEY_FRAME_RATE,mFps);  
    	mEncFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
    	mEncFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, m_SupportColorFormat);
    	mEncoder.configure(mEncFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
    	mEncoder.start();	
    	
    	/*init audio encoder*/
    	mAL = new AudioLive(0, AACQueue);
    			
    	/*init rtmp publisher*/
    	dsturl = mEditURL.getText().toString().trim();
    	mEditURL.setText(dsturl);
    	mFlvMuxer = new FlvMuxer(new RtmpHandler(this));
    	vtrack = mFlvMuxer.addTrack(mEncFormat);
    	atrack = mFlvMuxer.addTrack(mAL.getFormat());
    	mFlvMuxer.setVideoResolution(mResolutionX,mResolutionY);
    	mFlvMuxer.start(dsturl);
    	
    	StartEncoderThread();
    	mCamera.setPreviewCallback(new Camera.PreviewCallback(){
			@Override
			public void onPreviewFrame(byte[] data, Camera camera) {
				putYUVData(data,data.length);
			}	
    	});
 		
    }
    public void MediaStreamStop(){
    	mAL.stop();
    	StopEncoderThread();
    	mFlvMuxer.stop();
    	mFlvMuxer = null;
    	mCamera.setPreviewCallback(null);
    	mEncoder.stop();
    	mEncoder.release();
    }
	public boolean isRuning = false;
	private int TIMEOUT_USEC = 12000;
	Thread EncoderThread;
	public void StopEncoderThread(){
		isRuning = false;
		try {
			EncoderThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public void StartEncoderThread(){
		EncoderThread = new Thread(new Runnable() {
			@Override
			public void run() {
				isRuning = true;
				byte[] input = null;
				long pts =  0;
				long generateIndex = 0;
				MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
				ByteBuffer[] inputBuffers = mEncoder.getInputBuffers();
				ByteBuffer[] outputBuffers = mEncoder.getOutputBuffers();
				
				byte[] yuv420sp = new byte[mResolutionX*mResolutionY*3/2];
				
				while (isRuning) {
					if (YUVQueue.size() >0){
						input = YUVQueue.poll();
						NV21ToNV12(input,yuv420sp,mResolutionX,mResolutionY);
						input = yuv420sp;
					}
					if (input != null) {
						try {
							long startMs = System.currentTimeMillis();
							int inputBufferIndex = mEncoder.dequeueInputBuffer(-1);
							if (inputBufferIndex >= 0) {
								pts = (System.nanoTime() / 1000);//computePresentationTime(generateIndex);
								ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
								inputBuffer.clear();
								inputBuffer.put(input);
								mEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, pts, 0);
								generateIndex += 1;
							}
							
							int outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
							if (outputBufferIndex >= 0) {
								ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
								mFlvMuxer.writeSampleData(vtrack, outputBuffer, bufferInfo);
								mEncoder.releaseOutputBuffer(outputBufferIndex, false);
							}

						} catch (Throwable t) {
							t.printStackTrace();
						}
					} else {
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			}
		});
		EncoderThread.start();	
		mAL.start();
	}
	/**
     * Generates the presentation time for frame N, in microseconds.
     */
    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000000 / mFps;
    }
    public void putYUVData(byte[] buffer, int length) {  
        if (YUVQueue.size() >= YUVQ_num) {  
            YUVQueue.poll();  
        }  
        YUVQueue.add(buffer);  
    } 
    private void NV21ToNV12(byte[] nv21,byte[] nv12,int width,int height){
		if(nv21 == null || nv12 == null)return;
		int framesize = width*height;
		int i = 0,j = 0;
		System.arraycopy(nv21, 0, nv12, 0, framesize);
		for(i = 0; i < framesize; i++){
			nv12[i] = nv21[i];
		}
		for (j = 0; j < framesize/2; j+=2)
		{
		  nv12[framesize + j-1] = nv21[j+framesize];
		}
		for (j = 0; j < framesize/2; j+=2)
		{
		  nv12[framesize + j] = nv21[j+framesize-1];
		}
	}
    public void PlayCmd(View view){
    	if(playstate==0){
    		findViewById(R.id.streambtn).setEnabled(false);
    		findViewById(R.id.recordbtn).setEnabled(false);
    		mSoundPool.play(mSoundID1, 1, 1, 0, 0, 1);
 
    		dsturl = mEditURL.getText().toString().trim();
    		mFlvDeMuxer = new FlvDeMuxer(dsturl);
    		mFlvDeMuxer.FDStart();
    		
    		/*init audio decoder*/
        	mAL = new AudioLive(1, AACQueue);
        	mAL.start();
        	
    		mPlayerPreview = new PlayerPreview(this);
    		FrameLayout preview = (FrameLayout) findViewById(R.id.PlayerPreview);
            preview.addView(mPlayerPreview);
            mPlayerPreview.Start();
    		((ImageView) view).setImageDrawable(getResources().getDrawable(R.drawable.btn_play_start));
    		playstate = 1;
    	}else if(playstate==1){
    		mAL.stop();
    		mFlvDeMuxer.FDClose();
    		
    		mPlayerPreview.Stop();
    		
    		FrameLayout preview = (FrameLayout) findViewById(R.id.PlayerPreview); 
        	preview.removeView(mPlayerPreview);
    		mSoundPool.play(mSoundID1, 1, 1, 0, 0, 1);
    		findViewById(R.id.streambtn).setEnabled(true);
    		findViewById(R.id.recordbtn).setEnabled(true);
    		((ImageView) view).setImageDrawable(getResources().getDrawable(R.drawable.btn_play_stop));
    		playstate = 0;
    	}
    }
    
    public void RecordCmd(View view){
    	if(recordstate==0){
    		findViewById(R.id.streambtn).setEnabled(false);
    		findViewById(R.id.playbtn).setEnabled(false);
    		
    		initCamera();
    		new Handler().postDelayed(new Runnable(){    
    		    public void run() {    
    		    	MediaRecorderStart();  
    		    }    
    		}, 500);
    		
    		//dsturl = mEditURL.getText().toString().trim();
    		//mSoundPool.play(mSoundID, 1, 1, 0, 0, 1);
    		
    		((ImageView) view).setImageDrawable(getResources().getDrawable(R.drawable.btn_record_start));
    		recordstate = 1;
    	}else if(recordstate==1){	
    		
    		MediaRecorderStop();
    		releaseCamera();    		
    		
    		//mSoundPool.play(mSoundID, 1, 1, 0, 0, 1);
    		findViewById(R.id.streambtn).setEnabled(true);
    		findViewById(R.id.playbtn).setEnabled(true);
    		((ImageView) view).setImageDrawable(getResources().getDrawable(R.drawable.btn_record_stop));
    		recordstate = 0;
    	}
    }
    public void StreamCmd(View view){
    	if(streamstate==0){
    		//stop --> start
    		findViewById(R.id.recordbtn).setEnabled(false);
    		findViewById(R.id.playbtn).setEnabled(false);
    		initCamera();  		
			mSoundPool.play(mSoundID, 1, 1, 0, 0, 1);
			new Handler().postDelayed(new Runnable(){    
    		    public void run() {    
    		    	MediaStreamStart(); 
    		    }    
    		}, 500);   		
    		((ImageView) view).setImageDrawable(getResources().getDrawable(R.drawable.btn_stream_start));
    		streamstate = 1;
    	}else if(streamstate==1){
    		//start --> stop
    		MediaStreamStop();
    		releaseCamera();
    		mSoundPool.play(mSoundID, 1, 1, 0, 0, 1);
    		findViewById(R.id.recordbtn).setEnabled(true);
    		findViewById(R.id.playbtn).setEnabled(true);
    		((ImageView) view).setImageDrawable(getResources().getDrawable(R.drawable.btn_stream_stop));
    		streamstate = 0;
    	}
    }

	@Override
	public void onRtmpConnecting(String msg) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpConnected(String msg) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpVideoStreaming() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpAudioStreaming() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpStopped() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpDisconnected() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpVideoFpsChanged(double fps) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpVideoBitrateChanged(double bitrate) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpAudioBitrateChanged(double bitrate) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpSocketException(SocketException e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpIOException(IOException e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpIllegalArgumentException(IllegalArgumentException e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void onRtmpIllegalStateException(IllegalStateException e) {
		// TODO Auto-generated method stub
		
	}
}
