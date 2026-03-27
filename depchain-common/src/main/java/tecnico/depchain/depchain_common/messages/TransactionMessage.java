package tecnico.depchain.depchain_common.messages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Transport-layer message for submitting transactions from client to server.
 * Uses plain Java types (not Besu types) so it can live in depchain-common
 * without requiring the Hyperledger Besu dependency.
 *
 * The server converts this to a {@code Transaction} record (with Besu types)
 * after deserialization.
 */
public class TransactionMessage implements Serializable {
	private static long txSeqCounter = 0;

	private final int clientId;
	private final long seqNum;

	// Transaction fields (plain strings for transport)
	private final BigInteger nonce;
	private final String from;      // hex address "0x..."
	private final String to;        // hex address "0x..." or null for contract deploy
	private final String gasPrice;  // decimal string
	private final long gasLimit;
	private final String value;     // decimal string (Wei)
	private final String data;      // hex string "0x..."

	// Ed25519 signature over getSignableBytes()
	private byte[] signature;

	public TransactionMessage(int clientId, BigInteger nonce, String from, String to,
							  String gasPrice, long gasLimit, String value, String data) {
		this.clientId = clientId;
		this.seqNum = txSeqCounter++;
		this.nonce = nonce;
		this.from = from;
		this.to = to;
		this.gasPrice = gasPrice;
		this.gasLimit = gasLimit;
		this.value = value;
		this.data = data;
	}

	// ── Getters ─────────────────────────────────────────────────────────

	public int getClientId() { return clientId; }
	public long getSeqNum() { return seqNum; }
	public BigInteger getNonce() { return nonce; }
	public String getFrom() { return from; }
	public String getTo() { return to; }
	public String getGasPrice() { return gasPrice; }
	public long getGasLimit() { return gasLimit; }
	public String getValue() { return value; }
	public String getData() { return data; }
	public byte[] getSignature() { return signature; }

	// ── Signing & Verification ──────────────────────────────────────────

	/**
	 * Produces a deterministic canonical byte representation of all
	 * semantically relevant transaction fields. This is what gets signed.
	 *
	 * Format: nonce|from|to|gasPrice|gasLimit|value|data
	 * (pipe-separated, UTF-8 encoded)
	 */
	public byte[] getSignableBytes() {
		StringBuilder sb = new StringBuilder();
		sb.append(nonce != null ? nonce.toString() : "0").append('|');
		sb.append(from != null ? from : "null").append('|');
		sb.append(to != null ? to : "null").append('|');
		sb.append(gasPrice != null ? gasPrice : "0").append('|');
		sb.append(gasLimit).append('|');
		sb.append(value != null ? value : "0").append('|');
		sb.append(data != null ? data : "");
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Signs the transaction with the client's Ed25519 private key.
	 * This replaces the Ethereum v/r/s (secp256k1) concept.
	 */
	public void sign(PrivateKey key) {
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initSign(key);
			sig.update(getSignableBytes());
			this.signature = sig.sign();
		} catch (Exception e) {
			throw new RuntimeException("Failed to sign TransactionMessage", e);
		}
	}

	/**
	 * Verifies the Ed25519 signature using the sender's public key.
	 */
	public boolean verify(PublicKey key) {
		if (this.signature == null) return false;
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initVerify(key);
			sig.update(getSignableBytes());
			return sig.verify(this.signature);
		} catch (Exception e) {
			return false;
		}
	}

	// ── Serialization ───────────────────────────────────────────────────

	public byte[] serialize() {
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		try {
			ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
			objectStream.writeObject(this);
			objectStream.flush();
			byteStream.flush();
		} catch (IOException e) {
			return null;
		}
		return byteStream.toByteArray();
	}

	public static TransactionMessage deserialize(byte[] data) {
		ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
		try {
			ObjectInputStream objectStream = new ObjectInputStream(byteStream);
			return (TransactionMessage) objectStream.readObject();
		} catch (IOException | ClassNotFoundException e) {
			return null;
		}
	}
}
