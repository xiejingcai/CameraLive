package com.example.cameralive;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;

import android.os.Environment;
import android.util.Log;

import com.github.faucamp.simplertmp.DefaultRtmpClient;
import com.github.faucamp.simplertmp.Util;
import com.github.faucamp.simplertmp.output.FlvWriter;
import com.github.faucamp.simplertmp.output.RtmpStreamWriter;
import com.github.faucamp.simplertmp.packets.ContentData;
import com.github.faucamp.simplertmp.packets.Data;
import com.github.faucamp.simplertmp.packets.RtmpHeader;

public class FlvDeMuxer extends RtmpStreamWriter {
	
	DefaultRtmpClient mDefaultRtmpClient = null;
	
	private int newStartPTSdelta = 0;
	
	private String rtmp_host = null;
	private String rtmp_port = null;
	private String rtmp_app = null;
	private String rtmp_path = null;
	
	public void url_parser(String s){
		int tindex_start = 7;
		int tindex_end = s.indexOf(':', tindex_start);
		rtmp_host = s.substring(tindex_start, tindex_end);
	
		tindex_start = tindex_end +1;
		tindex_end = s.indexOf('/', tindex_start);
		rtmp_port = s.substring(tindex_start, tindex_end);
	
		tindex_start = tindex_end +1;
		tindex_end = s.indexOf('/', tindex_start);
		rtmp_app = s.substring(tindex_start, tindex_end);
		
		tindex_start = tindex_end +1;
		rtmp_path = s.substring(tindex_start);
	}
	public FlvDeMuxer(String r_url){
		url_parser(r_url);
		mDefaultRtmpClient = new DefaultRtmpClient(rtmp_host,Integer.valueOf(rtmp_port),rtmp_app);
		newStartPTSdelta = 0;
	}
	public void RtmpStart(){
		
		try {
			mDefaultRtmpClient.connect();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		try {
			mDefaultRtmpClient.playAsync(rtmp_path,this);
		} catch (IllegalStateException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	public void RtmpStop(){
		mDefaultRtmpClient.closeStream();
	}
	public void FDStart(){
		Thread mThread = new Thread( new Runnable(){

			@Override
			public void run() {
				// TODO Auto-generated method stub
				RtmpStart();
			}
			
		});
		mThread.start();
	}
	public void FDClose(){
		Thread mThread = new Thread( new Runnable(){

			@Override
			public void run() {
				// TODO Auto-generated method stub
				RtmpStop();
			}
			
		});
		mThread.start();
	}
	
    @Override
    public void write(Data dataPacket) throws IOException {
        final RtmpHeader header = dataPacket.getHeader();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(header.getPacketLength());
        dataPacket.writeBody(baos);
        write(header.getMessageType(), baos.toByteArray(), header.getAbsoluteTimestamp());
    }

    @Override
    public void write(ContentData packet) throws IOException {
        final RtmpHeader header = packet.getHeader();
        write(header.getMessageType(), packet.getData(), header.getAbsoluteTimestamp());
    }
 
	private void write(final RtmpHeader.MessageType packetType,
			final byte[] data, final int packetTimestamp) throws IOException {
		switch (packetType.getValue()) {
		case 0x09:
			if (0x7 == (data[0] & 0x7)) {
				if(MainActivity.AVCQueue.size() < MainActivity.AVCQ_num){
					if(newStartPTSdelta==0)
						newStartPTSdelta = packetTimestamp;
					int cTS = packetTimestamp - newStartPTSdelta;
					BufferUnit bu = new BufferUnit(cTS,data);
					MainActivity.AVCQueue.add(bu);					
				}			
			}
			break;
		case 0x08:
			if(0xa0==(data[0] & 0xa0)){
				if(MainActivity.AACQueue.size()< MainActivity.AACQ_num){
					if(newStartPTSdelta==0)
						newStartPTSdelta = packetTimestamp;
					int cTS = packetTimestamp - newStartPTSdelta;
					BufferUnit bu = new BufferUnit(cTS,data);
					MainActivity.AACQueue.add(bu);
				}
			}
			break;
		}
	}
}
