package pod.babashka.mysql;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.net.SocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.channels.SocketChannel;

import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.conf.RuntimeProperty;
import com.mysql.cj.protocol.ExportControlled;
import com.mysql.cj.protocol.ServerSession;
import com.mysql.cj.protocol.SocketConnection;
import com.mysql.cj.protocol.SocketFactory;
import com.mysql.cj.ServerVersion;

public class UnixSocketFactory implements SocketFactory {
    private Socket rawSocket;
    private Socket sslSocket;

    public UnixSocketFactory() {
    }

    @SuppressWarnings({"unchecked", "exports"})
    @Override
    public <T extends Closeable> T connect(String hostname, int portNumber, PropertySet props,
					   int loginTimeout) throws IOException {
	RuntimeProperty<String> prop = props.getStringProperty("pod.babashka.mysql.file");
	String sock;
	if (prop != null && !prop.isExplicitlySet()) {
	    sock = prop.getStringValue();
	} else {
	    sock = "/tmp/mysql.sock";
	}
	final Path socketPath = new File(sock).toPath();
	System.out.println("connecting to socket:" + socketPath);
	
	SocketAddress address = UnixDomainSocketAddress.of(socketPath);
	this.rawSocket = new UnixSocket(SocketChannel.open(address));
	System.out.println("class: " + this.rawSocket);
	//System.out.println("is: " + this.rawSocket.getInputStream());
	this.sslSocket = rawSocket;
	return (T) rawSocket;
    }

    @SuppressWarnings({"unchecked", "exports"})
    @Override
    public <T extends Closeable> T performTlsHandshake(SocketConnection socketConnection,
						       ServerSession serverSession) throws IOException {
	ServerVersion version = serverSession == null ? null : serverSession.getServerVersion();
		this.sslSocket = ExportControlled.performTlsHandshake(this.rawSocket,
								      socketConnection,
								      version);
	return (T) this.sslSocket;
    }
}
