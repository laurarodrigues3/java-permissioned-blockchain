package tecnico.depchain.links;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import tecnico.depchain.DepchainUtils;

// Stubborn link implementation
// Repeatedly send messages until ACKed
// ACKs are empty messages only with the received message ID
// Consequently, empty messages are not allowed

public class StubbornLink extends P2PLink implements Runnable {
	private long txCounter = 0;
	private FairLossLink lower;

	// Stubborn message sending
	private NavigableMap<Long, byte[]> pendingMsgs = new TreeMap<>();
	private Thread stubbornThread;

	public StubbornLink(BiConsumer<byte[], InetSocketAddress> rxHandler, InetSocketAddress local,
			InetSocketAddress remote) throws SocketException {
		super(rxHandler);

		lower = new FairLossLink(this::internalRxHandler, local, remote);

		// Start stubborn thread
		stubbornThread = new Thread(this);
		stubbornThread.start();
	}

	@Override
	public void transmit(byte[] data) {
		if (data.length == 0)
			throw new IllegalArgumentException();

		MessageWithID msg = serializeMsg(data);

		// Send to stubborn thread for repeated transmission
		synchronized (pendingMsgs) {
			pendingMsgs.put(msg.id, msg.payload);
		}
	}

	// Stubborn thread
	public void run() {
		byte[] selected_msg = null;

		// 'Cursor' for round robin resend
		Long lastProcessed = Long.valueOf(-1);

		// Transmit messages until the internal RX handler finds their respective ACKs
		while (true) {

			// Get a pending message
			synchronized (pendingMsgs) {
				if (pendingMsgs.isEmpty()) {
					DepchainUtils.sleep(1);
					continue;
				}

				// Pick entry not recently sent
				var entry = pendingMsgs.higherEntry(lastProcessed);
				if (entry == null) {
					// Reset cursor once end is reached
					lastProcessed = Long.valueOf(-1);
					continue;
				}
				lastProcessed = entry.getKey();
				selected_msg = entry.getValue();
			}

			// Finally send the message
			lower.transmit(selected_msg);
		}
	}

	// Handler for lower receive
	private void internalRxHandler(byte[] bytes, InetSocketAddress remote) {
		// Ignore messages too small to contain ID
		// Prevents crash on malformed messages (by bizantine nodes)
		if (bytes.length < 8)
			return;

		if (isACK(bytes)) {
			handleACK(bytes);
			return;
		}

		MessageWithID msg = deserializeMsg(bytes);

		// Transmit directly though fair loss link
		// Allows multiple delivery but that's within stubborn spec
		byte[] ack = makeACK(msg.id);
		lower.transmit(ack);

		// Deliver message
		rxHandler.accept(msg.payload, remote);
	}

	private void handleACK(byte[] bytes) {
		MessageWithID msg = deserializeMsg(bytes);

		// Simply remove from pending queue
		pendingMsgs.remove(msg.id);
	}

	// === Message building and utils ===
	private MessageWithID serializeMsg(byte[] payload) {
		long id = txCounter++;

		var buffer = ByteBuffer.allocate(8 + payload.length);
		buffer.putLong(id);
		buffer.put(payload);

		return new MessageWithID(id, buffer.array());
	}

	private static MessageWithID deserializeMsg(byte[] bytes) {
		var buffer = ByteBuffer.wrap(bytes);
		long id = buffer.getLong();
		byte[] payload = new byte[bytes.length - 8];
		buffer.get(payload);

		return new MessageWithID(id, payload);
	}

	private static byte[] makeACK(long id) {
		return ByteBuffer.allocate(8).putLong(id).array();
	}

	private static boolean isACK(byte[] bytes) {
		return bytes.length == 8;
	}
}

class MessageWithID {
	Long id;
	byte[] payload;

	public MessageWithID(long id, byte[] payload) {
		this.id = Long.valueOf(id);
		this.payload = payload;
	}
}
