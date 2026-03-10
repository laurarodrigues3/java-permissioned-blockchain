package tecnico.depchain.links;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.function.BiConsumer;
import java.net.DatagramSocket;
import java.io.IOException;
import java.net.DatagramPacket;

public class FairLossLink extends P2PLink implements Runnable {
	private DatagramSocket sock;
	private Thread receiverThread;
	private InetSocketAddress remote;
	private volatile boolean running = true;

	public FairLossLink(BiConsumer<byte[], InetSocketAddress> rxHandler, InetSocketAddress local,
			InetSocketAddress remote) throws SocketException {
		super(rxHandler);

		this.remote = remote;

		sock = new DatagramSocket(null);
		sock.setReuseAddress(true);
		sock.bind(local);

		receiverThread = new Thread(this);
		receiverThread.setDaemon(true);
		receiverThread.start();
	}

	public void transmit(byte[] data) {
		if (!running) return;
		var packet = new DatagramPacket(data, data.length, remote);
		try {
			sock.send(packet);
		} catch (IOException e) {
			// Ignore
		}
	}

	public void run() {
		byte[] rxBuffer = new byte[64 * 1024];
		DatagramPacket packet = new DatagramPacket(rxBuffer, rxBuffer.length);
		while (running) {
			packet.setLength(rxBuffer.length);
			try {
				sock.receive(packet);
			} catch (IOException e) {
				continue;
			}

			if (running && rxHandler != null) {
				byte[] received = new byte[packet.getLength()];
				System.arraycopy(rxBuffer, 0, received, 0, packet.getLength());
				rxHandler.accept(received, remote);
			}
		}
	}

	@Override
	public void close() {
		running = false;
		sock.close();
	}
}
