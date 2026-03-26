package tecnico.depchain.depchain_common.messages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

import tecnico.depchain.depchain_common.blockchain.Transaction;

public class TransactionMessage implements Serializable {
	private static long txSeqNum = 0;

	private int clientId;
	private Transaction tx;
	private long seqNum;
	private byte[] signature;

	public TransactionMessage(int clientId, Transaction tx) {
		this.clientId = clientId;
		this.tx = tx;
		this.seqNum = txSeqNum++;
	}

	public TransactionMessage(Transaction tx) {
		this.clientId = -1;
		this.tx = tx;
		seqNum = txSeqNum++;
	}

	public int getClientId() { return clientId; }
	public Transaction getTransaction() { return tx; }
	public long getSeqNum() { return seqNum; }
	public byte[] getSignature() { return signature; }

	public void sign(PrivateKey key) {
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initSign(key);
			sig.update(getSignableBytes());
			this.signature = sig.sign();
		} catch (Exception e) {
			throw new RuntimeException("Failed to sign StringMessage", e);
		}
	}

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

	private byte[] getSignableBytes() {
		byte[] transactionBytes = tx.serialize();
		ByteBuffer buffer = ByteBuffer.allocate(8 + transactionBytes.length);
		buffer.putLong(seqNum);
		buffer.put(transactionBytes);
		return buffer.array();
	}

	public byte[] serialize() {
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		try {
			ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
			objectStream.writeObject(this);
			objectStream.flush();
			byteStream.flush();
		}
		catch (IOException e) {
			return null; //Should not happen
		}

		return byteStream.toByteArray();
	}

	public static TransactionMessage deserialize(byte[] data) {
		ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
		try {
			ObjectInputStream objectStream = new ObjectInputStream(byteStream);
			return (TransactionMessage)objectStream.readObject();
		}
		catch (IOException e) { return null; }
		catch (ClassNotFoundException e) { return null; }
	}
}
