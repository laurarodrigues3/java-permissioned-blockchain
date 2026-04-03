package tecnico.depchain.depchain_common.links;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// Authenticated Perfect link implementation
// Uses an ephemeral X25519 Diffie-Hellman handshake (authenticated by Ed25519)
// to derive a per-session HMAC-SHA256 key for message authentication.
// Deduplicates messages to guarantee at-most-once delivery.

public class AuthenticatedPerfectLink extends P2PLink {
	private StubbornLink lower;
	private final PrivateKey ownKey;    // Ed25519 identity key (handshake auth only)
	private final PublicKey remoteKey;  // Ed25519 remote identity key (handshake auth only)

	// Ephemeral DH key exchange
	private final KeyPair dhKeyPair;   // Ephemeral X25519 key pair
	private volatile byte[] hmacKey;   // Derived HMAC key (null until handshake completes)
	private volatile boolean handshakeComplete = false;
	private final List<byte[]> pendingMessages = new ArrayList<>();

	// Wire-format type tags
	private static final byte MSG_HANDSHAKE = 0x01;
	private static final byte MSG_DATA      = 0x02;
	private static final int  HMAC_LENGTH   = 32; // HMAC-SHA256

	// Deduplication
	private long txSeqNum = 0;
	private long highWaterMark = -1;
	private final Set<Long> outOfOrder = new HashSet<>();

	public AuthenticatedPerfectLink(
			BiConsumer<byte[], InetSocketAddress> rxHandler, InetSocketAddress local, InetSocketAddress remote,
			PrivateKey ownKey, PublicKey remoteKey)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException {
		super(rxHandler);

		this.ownKey = ownKey;
		this.remoteKey = remoteKey;

		lower = new StubbornLink(this::internalRxHandler, local, remote);

		// Generate ephemeral X25519 key pair for Diffie-Hellman exchange
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
		dhKeyPair = kpg.generateKeyPair();

		// Immediately send DH handshake (StubbornLink retransmits until ACKed)
		sendHandshake();
	}

	// ── Handshake ──────────────────────────────────────────────────────

	/**
	 * Sends our ephemeral X25519 public key, signed with our Ed25519
	 * identity key so the remote can authenticate the DH exchange.
	 *
	 * Wire format: [MSG_HANDSHAKE (1)] [dhPubKeyLen (2)] [dhPubKey] [Ed25519 sig]
	 */
	private void sendHandshake() {
		try {
			byte[] dhPubEncoded = dhKeyPair.getPublic().getEncoded();

			// Build the portion that is signed: type + length + DH public key
			ByteBuffer signedPortion = ByteBuffer.allocate(1 + 2 + dhPubEncoded.length);
			signedPortion.put(MSG_HANDSHAKE);
			signedPortion.putShort((short) dhPubEncoded.length);
			signedPortion.put(dhPubEncoded);
			byte[] dataToSign = signedPortion.array();

			// Sign with Ed25519 identity key
			Signature sig = Signature.getInstance("Ed25519");
			sig.initSign(ownKey);
			sig.update(dataToSign);
			byte[] sigBytes = sig.sign();

			// Final message: [signedPortion][Ed25519 signature]
			ByteBuffer msg = ByteBuffer.allocate(dataToSign.length + sigBytes.length);
			msg.put(dataToSign);
			msg.put(sigBytes);

			lower.transmit(msg.array());
		} catch (Exception e) {
			throw new RuntimeException("DH handshake send failed", e);
		}
	}

	/**
	 * Processes a received DH handshake: verifies the Ed25519 identity
	 * signature, performs X25519 key agreement, derives the HMAC key,
	 * and flushes any messages that were queued before handshake completion.
	 */
	private void handleHandshake(byte[] bytes) {
		if (handshakeComplete) return; // idempotent
		try {
			ByteBuffer buf = ByteBuffer.wrap(bytes);
			buf.get(); // skip type byte
			short dhPubLen = buf.getShort();
			if (dhPubLen <= 0 || dhPubLen > bytes.length - 3) return;

			byte[] dhPubEncoded = new byte[dhPubLen];
			buf.get(dhPubEncoded);

			byte[] sigBytes = new byte[buf.remaining()];
			if (sigBytes.length == 0) return;
			buf.get(sigBytes);

			// Verify Ed25519 signature over [type + length + dhPubKey]
			byte[] signedPortion = new byte[1 + 2 + dhPubLen];
			System.arraycopy(bytes, 0, signedPortion, 0, signedPortion.length);

			Signature sig = Signature.getInstance("Ed25519");
			sig.initVerify(remoteKey);
			sig.update(signedPortion);
			if (!sig.verify(sigBytes)) return; // invalid identity, drop

			// Reconstruct remote's ephemeral X25519 public key
			KeyFactory kf = KeyFactory.getInstance("X25519");
			PublicKey remoteDhPub = kf.generatePublic(new X509EncodedKeySpec(dhPubEncoded));

			// X25519 key agreement → shared secret
			KeyAgreement ka = KeyAgreement.getInstance("X25519");
			ka.init(dhKeyPair.getPrivate());
			ka.doPhase(remoteDhPub, true);
			byte[] sharedSecret = ka.generateSecret();

			// Derive HMAC key: SHA-256(sharedSecret)
			byte[] derivedKey = MessageDigest.getInstance("SHA-256").digest(sharedSecret);

			synchronized (pendingMessages) {
				this.hmacKey = derivedKey;
				this.handshakeComplete = true;

				// Flush messages that were queued before handshake completed
				for (byte[] pending : pendingMessages) {
					transmitAuthenticated(pending);
				}
				pendingMessages.clear();
			}
		} catch (Exception e) {
			// Handshake verification or key agreement failed — drop silently
		}
	}

	// ── Transmit / Receive ─────────────────────────────────────────────

	public void transmit(byte[] data) {
		if (!handshakeComplete) {
			synchronized (pendingMessages) {
				if (!handshakeComplete) {
					pendingMessages.add(data);
					return;
				}
			}
		}
		transmitAuthenticated(data);
	}

	/**
	 * Wire format: [MSG_DATA (1)] [HMAC-SHA256 (32)] [seqNum (8)] [payload]
	 */
	private void transmitAuthenticated(byte[] data) {
		long seq = txSeqNum++;

		// Prepend sequence number to payload
		ByteBuffer seqBuffer = ByteBuffer.allocate(8 + data.length);
		seqBuffer.putLong(seq);
		seqBuffer.put(data);
		byte[] dataWithSeq = seqBuffer.array();

		// HMAC over (seq + payload)
		byte[] hmac = computeHMAC(dataWithSeq);

		ByteBuffer buffer = ByteBuffer.allocate(1 + HMAC_LENGTH + dataWithSeq.length);
		buffer.put(MSG_DATA);
		buffer.put(hmac);
		buffer.put(dataWithSeq);

		lower.transmit(buffer.array());
	}

	// Handler for lower receive — dispatches on message type
	private void internalRxHandler(byte[] bytes, InetSocketAddress remote) {
		if (bytes.length < 1) return;

		byte type = bytes[0];

		if (type == MSG_HANDSHAKE) {
			handleHandshake(bytes);
			return;
		}

		if (type == MSG_DATA) {
			handleData(bytes, remote);
			return;
		}
		// Unknown type — drop silently
	}

	private void handleData(byte[] bytes, InetSocketAddress remote) {
		if (!handshakeComplete) return; // can't verify yet

		// Minimum: 1 (type) + 32 (HMAC) + 8 (seq) = 41
		if (bytes.length < 1 + HMAC_LENGTH + 8) return;

		ByteBuffer buf = ByteBuffer.wrap(bytes);
		buf.get(); // skip type

		byte[] receivedHmac = new byte[HMAC_LENGTH];
		buf.get(receivedHmac);

		byte[] payload = new byte[buf.remaining()]; // seq + actual data
		buf.get(payload);

		// Verify HMAC (constant-time comparison)
		byte[] expectedHmac = computeHMAC(payload);
		if (!MessageDigest.isEqual(receivedHmac, expectedHmac)) return;

		// Extract sequence number for deduplication
		ByteBuffer payloadBuffer = ByteBuffer.wrap(payload);
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

	// ── HMAC helper ────────────────────────────────────────────────────

	private byte[] computeHMAC(byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
			return mac.doFinal(data);
		} catch (Exception e) {
			throw new RuntimeException("HMAC computation failed", e);
		}
	}

	@Override
	public void close() {
		lower.close();
	}
}
