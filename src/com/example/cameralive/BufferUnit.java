package com.example.cameralive;

public class BufferUnit {
	public long pts;
	public byte[] buffer;
	public BufferUnit(long i, byte[] b){
		pts = i;
		buffer = b;
	}
}
