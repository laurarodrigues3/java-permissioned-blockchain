package tecnico.depchain.depchain_common.links;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.HashSet;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;

// Authenticated Perfect link implementation
// Prepends digital signatures to messages for authenticity validation
// Deduplicates messages to guarantee at-most-once delivery

public class AuthenticatedPerfectLink extends P2PLink {
	private StubbornLink lower;
	private PrivateKey ownKey;
	private PublicKey remoteKey;
	private int sigLength;
	private long txSeqNum = 0;
	private long highWaterMark = -1;
	private Set<Long> outOfOrder = new HashSet<>();

	public AuthenticatedPerfectLink(
			BiConsumer<byte[], InetSocketAddress> rxHandler, InetSocketAddress local, InetSocketAddress remote,
			PrivateKey ownKey, PublicKey remoteKey)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException {
		super(rxHandler);

		this.ownKey = ownKey;
		this.remoteKey = remoteKey;

		lower = new StubbornLink(this::internalRxHandler, local, remote);

		// Determine signature length by signing a test byte
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initSign(ownKey);
			sig.update(new byte[]{0});
			this.sigLength = sig.sign().length;
		} catch (SignatureException e) {
			throw new RuntimeException("Failed to determine Ed25519 signature length", e);
		}
	}

	public void transmit(byte[] data) {
		long seq = txSeqNum++;

		// Prepend sequence number to payload
		var seqBuffer = ByteBuffer.allocate(8 + data.length);
		seqBuffer.putLong(seq);
		seqBuffer.put(data);
		byte[] dataWithSeq = seqBuffer.array();

		// Sign data+seq and prepend signature
		byte[] sigBytes;
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initSign(ownKey);
			sig.update(dataWithSeq);
			sigBytes = sig.sign();
		} catch (Exception e) {
			throw new RuntimeException("Ed25519 signing failed", e);
		}

		var buffer = ByteBuffer.allocate(sigBytes.length + dataWithSeq.length);
		buffer.put(sigBytes);
		buffer.put(dataWithSeq);

		lower.transmit(buffer.array());
	}

	// Handler for lower receive
	private void internalRxHandler(byte[] bytes, InetSocketAddress remote) {
		// Ignore too small to contain signature + sequence number
		// Prevents crash on malformed messages (by byzantine nodes)
		if (bytes.length < sigLength + 8)
			return;

		byte[] receivedSig = new byte[sigLength];
		byte[] payload = new byte[bytes.length - sigLength];
		var buffer = ByteBuffer.wrap(bytes);
		buffer.get(receivedSig);
		buffer.get(payload);

		// Verify signature over payload using remote's public key
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initVerify(remoteKey);
			sig.update(payload);
			if (!sig.verify(receivedSig))
				return; // Invalid signature, drop message
		} catch (Exception e) {
			return; // Verification error, drop message
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
