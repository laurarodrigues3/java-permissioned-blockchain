package tecnico.depchain.depchain_common.messages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class ConfirmMessage implements Serializable {
	private long seqNum;
	private boolean accepted;

	public ConfirmMessage(long seqNum, boolean accepted) {
		this.seqNum = seqNum;
		this.accepted = accepted;
	}

	public long getSeqNum() { return seqNum; }
	public boolean getAccepted() { return accepted; }

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

	public static ConfirmMessage deserialize(byte[] data) {
		ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
		try {
			ObjectInputStream objectStream = new ObjectInputStream(byteStream);
			return (ConfirmMessage)objectStream.readObject();
		}
		catch (IOException e) { return null; }
		catch (ClassNotFoundException e) { return null; }
	}
}
