package tecnico.depchain.depchain_common.messages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class StringMessage implements Serializable {
	private static long txSeqNum = 0;

	private String content;
	private long seqNum;

	public StringMessage(String content) {
		this.content = content;
		seqNum = txSeqNum++;
	}

	public String getContent() { return content; }
	public long getSeqNum() { return seqNum; }

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
