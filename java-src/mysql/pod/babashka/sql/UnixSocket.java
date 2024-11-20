package pod.babashka.mysql;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;

public class UnixSocket extends Socket {

    private SocketChannel channel = null;

    public UnixSocket(SocketChannel channel) {
	this.channel = channel;
    }

    @Override
    public SocketChannel getChannel() {
	return this.channel;
    }

    @Override
    public boolean isConnected() {
	return this.channel.isConnected();
    }

    @Override
    public void close() throws IOException {
	this.channel.close();
    }

    public InputStream getInputStream(){
	final SocketChannel channel = this.channel;
	return new InputStream () {
	    @Override
	    public int read() throws IOException {
		//System.out.print("read() ");
		ByteBuffer buf = ByteBuffer.allocate(1);
		int res = channel.read(buf);
		if (res < 0){
		    return res;
		}
		return buf.get(0) & 0xFF;
	    }
	};
    }

    public OutputStream getOutputStream(){
	final SocketChannel channel = this.channel;
	return new OutputStream() {
	    @Override
	    public void write(int b) throws IOException {
		byte[] ba = new byte[]{(byte)b};
		ByteBuffer buf = ByteBuffer.wrap(ba);
		channel.write(buf);
	    }
	    @Override
	    public void write(byte[] b) throws IOException {
		long r = channel.write(ByteBuffer.wrap(b));
	    };
	};
    }
}
