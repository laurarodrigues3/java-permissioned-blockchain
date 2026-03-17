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

public class StringMessage implements Serializable {
	private static long txSeqNum = 0;

	private int clientId;
	private String content;
	private long seqNum;
	private byte[] signature;

	public StringMessage(int clientId, String content) {
		this.clientId = clientId;
		this.content = content;
		this.seqNum = txSeqNum++;
	}

	public StringMessage(String content) {
		this.clientId = -1;
		this.content = content;
		seqNum = txSeqNum++;
	}

	public int getClientId() { return clientId; }
	public String getContent() { return content; }
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
		byte[] contentBytes = content.getBytes();
		ByteBuffer buffer = ByteBuffer.allocate(8 + contentBytes.length);
		buffer.putLong(seqNum);
		buffer.put(contentBytes);
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

	public static StringMessage deserialize(byte[] data) {
		ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
		try {
			ObjectInputStream objectStream = new ObjectInputStream(byteStream);
			return (StringMessage)objectStream.readObject();
		}
		catch (IOException e) { return null; }
		catch (ClassNotFoundException e) { return null; }
	}
}
