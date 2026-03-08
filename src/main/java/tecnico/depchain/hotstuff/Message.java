package tecnico.depchain.hotstuff;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class Message implements Serializable {
	private MsgType type;
	private int viewNumber;
	private int senderId;
	private TreeNode node;
	private QuorumCertificate justify;

	public enum MsgType {
		NEW_VIEW,
		PREPARE,
		PRE_COMMIT,
		COMMIT,
		DECIDE,
	}

	public Message(MsgType type, int viewNumber, int senderId, TreeNode node, QuorumCertificate justify) {
		this.type = type;
		this.viewNumber = viewNumber;
		this.senderId = senderId;
		this.node = node;
		this.justify = justify;
	}

	public MsgType getType() { return type; }
	public int getViewNumber() { return viewNumber; }
	public int getSenderId() { return senderId; }
	public TreeNode getTreeNode() { return node; }
	public QuorumCertificate getJustify() { return justify; }

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

	public static Message deserialize(byte[] data) {
		ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
		try {
			ObjectInputStream objectStream = new ObjectInputStream(byteStream);
			return (Message)objectStream.readObject();
		}
		catch (IOException e) { return null; }
		catch (ClassNotFoundException e) { return null; }
	}
}
