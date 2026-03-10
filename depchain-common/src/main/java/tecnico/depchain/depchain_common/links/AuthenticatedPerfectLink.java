package tecnico.depchain.depchain_common.links;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.HashSet;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

// Authenticated Perfect link implementation
// Prepends MACs to messages for authenticity validation
// Deduplicates messages to guarantee at-most-once delivery
// NOTE: Delivery order is NOT implemented here. Consider adding it?

public class AuthenticatedPerfectLink extends P2PLink {
	private StubbornLink lower;
	private Mac outgoing_mac, incoming_mac;
	private long txSeqNum = 0;
	private long highWaterMark = -1;
	private Set<Long> outOfOrder = new HashSet<>();

	//TODO: MAC keys cannot be set in global knowledge (gen and send)
	public AuthenticatedPerfectLink(
			BiConsumer<byte[], InetSocketAddress> rxHandler, InetSocketAddress local, InetSocketAddress remote,
			SecretKey ownKey, SecretKey remoteKey)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException {
		super(rxHandler);

		lower = new StubbornLink(this::internalRxHandler, local, remote);

		//TODO: Generate keys at start
		outgoing_mac = Mac.getInstance("HmacSHA256");
		incoming_mac = Mac.getInstance("HmacSHA256");
		outgoing_mac.init(ownKey);
		incoming_mac.init(remoteKey);

		// Make sure MAC size is the expected
		if (outgoing_mac.getMacLength() != 32)
			throw new RuntimeException("Unexpected MAC length: expected 32 bytes");
	}

	public void transmit(byte[] data) {
		long seq = txSeqNum++;

		// Prepend sequence number to payload
		var seqBuffer = ByteBuffer.allocate(8 + data.length);
		seqBuffer.putLong(seq);
		seqBuffer.put(data);
		byte[] dataWithSeq = seqBuffer.array();

		// Compute MAC over data+seq and prepend it
		byte[] mac_bytes = outgoing_mac.doFinal(dataWithSeq);

		var buffer = ByteBuffer.allocate(32 + dataWithSeq.length);
		buffer.put(mac_bytes);
		buffer.put(dataWithSeq);

		lower.transmit(buffer.array());
	}

	// Handler for lower receive
	private void internalRxHandler(byte[] bytes, InetSocketAddress remote) {
		// Ignore too small to contain MAC + sequence number
		// Prevents crash on malformed messages (by byzantine nodes)
		if (bytes.length < 32 + 8)
			return;

		byte[] received_mac = new byte[32];
		byte[] payload = new byte[bytes.length - 32];
		var buffer = ByteBuffer.wrap(bytes);
		buffer.get(received_mac);
		buffer.get(payload);

		byte[] calculated_mac = incoming_mac.doFinal(payload);

		for (int i = 0; i < calculated_mac.length; ++i) {
			if (calculated_mac[i] != received_mac[i])
				return; // Ignore bad message
		}

		// Extract sequence number for deduplication
		var payloadBuffer = ByteBuffer.wrap(payload);
		long seqNum = payloadBuffer.getLong();

		// Deduplicate using high water mark + out-of-order set
		synchronized (outOfOrder) {
			if (seqNum <= highWaterMark)
				return; // Already delivered
			if (!outOfOrder.add(seqNum))
				return; // Already pending/delivered

			// Advance high water mark as far as possible
			while (outOfOrder.remove(highWaterMark + 1)) {
				highWaterMark++;
			}
		}

		// Deliver actual data (without sequence number)
		byte[] actualData = new byte[payload.length - 8];
		payloadBuffer.get(actualData);
		rxHandler.accept(actualData, remote);
	}

	@Override
	public void close() {
		lower.close();
	}
}
