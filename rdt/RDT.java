package rdt;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static rdt.Utility.udp_send;

public class RDT {
	public static final int MSS = 100; // Max segement size in bytes
	public static final int RTO = 500; // Retransmission Timeout in msec
	public static final int ERROR = -1;
	public static final int MAX_BUF_SIZE = 3;  
	public static final int GBN = 1;   // Go back N protocol
	public static final int SR = 2;    // Selective Repeat
	public static final int protocol = GBN;
	public static double lossRate = 0.0;
	public static Random random = new Random(); 
	public static Timer timer = new Timer();	
	private DatagramSocket socket; 
	private InetAddress dst_ip;
	private int dst_port;
	private int local_port;
	public int currentSequence;
	private RDTBuffer sndBuf;
	private RDTBuffer rcvBuf;
	private ReceiverThread rcvThread;  
	
	RDT (String dst_hostname_, int dst_port_, int local_port_) 
	{
		local_port = local_port_;
		dst_port = dst_port_; 
		try {
			 socket = new DatagramSocket(local_port);
			 dst_ip = InetAddress.getByName(dst_hostname_);
		 } catch (IOException e) {
			 System.out.println("RDT constructor: " + e);
		 }
		sndBuf = new RDTBuffer(MAX_BUF_SIZE);
		if (protocol == GBN)
			rcvBuf = new RDTBuffer(1);
		else 
			rcvBuf = new RDTBuffer(MAX_BUF_SIZE);
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port);
	}
	
	RDT (String dst_hostname_, int dst_port_, int local_port_, int sndBufSize, int rcvBufSize)
	{
		local_port = local_port_;
		dst_port = dst_port_;
		 try {
			 socket = new DatagramSocket(local_port);
			 dst_ip = InetAddress.getByName(dst_hostname_);
		 } catch (IOException e) {
			 System.out.println("RDT constructor: " + e);
		 }
		sndBuf = new RDTBuffer(sndBufSize);
		if (protocol == GBN)
			rcvBuf = new RDTBuffer(1);
		else 
			rcvBuf = new RDTBuffer(rcvBufSize);
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port);
	}
	
	public static void setLossRate(double rate) {lossRate = rate;}
	public int send(byte[] data, int size) throws IOException {
		byte[] sendingByteArray = new byte[125];
		RDTBuffer buffer = new RDTBuffer(125);
		RDTSegment sendFrame = new RDTSegment();
		currentSequence++;
		sendFrame.seqNum = currentSequence;
		sendFrame.ackNum = 0;
		udp_send(sendFrame,socket,dst_ip,dst_port);
		new TimeoutHandler(buffer,sendFrame,socket,dst_ip,dst_port);
		return size;
	}
	
	public int receive (byte[] buf, int size) throws IOException {
		System.out.print("receiving OKAY??..recieve function call called\n");
		DatagramPacket pooReceive = new DatagramPacket(buf, size);
		socket.receive(pooReceive);
		RDTSegment recievedSegment = new RDTSegment();
		makeSegment(recievedSegment,pooReceive.toString());
		String printString = pooReceive.toString();
		System.out.print("Recieve function just received THIS message : " + printString);
		return 0; 
	}
	public void close() {
	}
}


class RDTBuffer {
	public RDTSegment[] buf;
	public int size;	
	public int base;
	public int next;
	public Semaphore semMutex;
	public Semaphore semFull;
	public Semaphore semEmpty;
	RDTBuffer (int bufSize) {
		buf = new RDTSegment[bufSize];
		for (int i=0; i<bufSize; i++)
			buf[i] = null;
		size = bufSize;
		base = next = 0;
		semMutex = new Semaphore(1, true);
		semFull =  new Semaphore(0, true);
		semEmpty = new Semaphore(bufSize, true);
	}
	public void putNext(RDTSegment seg) {		
		try {
			semEmpty.acquire(); 
			semMutex.acquire();
				buf[next%size] = seg;
				next++;  
			semMutex.release();
			semFull.release();
		} catch(InterruptedException e) {
			System.out.println("Buffer put(): " + e);
		}
	}
	public RDTSegment getNext() {
		return null;
	}
	public void putSeqNum (RDTSegment seg) {
	}
	public void dump() {
		System.out.println("Dumping the receiver buffer ...");
	}
}



class ReceiverThread extends Thread {
	RDTBuffer rcvBuf, sndBuf;
	DatagramSocket socket;
	InetAddress dst_ip;
	int dst_port;
	ReceiverThread (RDTBuffer rcv_buf, RDTBuffer snd_buf, DatagramSocket s, 
			InetAddress dst_ip_, int dst_port_) {
		rcvBuf = rcv_buf;
		sndBuf = snd_buf;
		socket = s;
		dst_ip = dst_ip_;
		dst_port = dst_port_;
	}	
	public void run() {
		byte[] receive = new byte[65535];
		DatagramPacket DpReceive = new DatagramPacket(receive,receive.length);
		String printString;
		while(true){
			System.out.print("Reciever thread started\n");
			try {
				socket.receive(DpReceive);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			printString = DpReceive.toString();
			System.out.print("Reciever thread just received THIS message : " + printString);
			try {
				TimeUnit.SECONDS.sleep(1);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}
	void makeSegment(RDTSegment seg, byte[] payload) {
		seg.seqNum = Utility.byteToInt(payload, RDTSegment.SEQ_NUM_OFFSET);
		seg.ackNum = Utility.byteToInt(payload, RDTSegment.ACK_NUM_OFFSET);
		seg.flags  = Utility.byteToInt(payload, RDTSegment.FLAGS_OFFSET);
		seg.checksum = Utility.byteToInt(payload, RDTSegment.CHECKSUM_OFFSET);
		seg.rcvWin = Utility.byteToInt(payload, RDTSegment.RCV_WIN_OFFSET);
		seg.length = Utility.byteToInt(payload, RDTSegment.LENGTH_OFFSET);
		for (int i=0; i< seg.length; i++)
			seg.data[i] = payload[i + RDTSegment.HDR_SIZE]; 
	}
}

