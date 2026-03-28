package tecnico.depchain.depchain_common.messages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import tecnico.depchain.depchain_common.blockchain.SignedTransaction;

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

	private final SignedTransaction signedTx;

	public TransactionMessage(int clientId, SignedTransaction signedTx) {
		this.clientId = clientId;
		this.seqNum = txSeqCounter++;
		this.signedTx = signedTx;
	}

	// ── Getters ─────────────────────────────────────────────────────────

	public int getClientId() { return clientId; }
	public long getSeqNum() { return seqNum; }
	public SignedTransaction getSignedTransaction() { return signedTx; }

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
