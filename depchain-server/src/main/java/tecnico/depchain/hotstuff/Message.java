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
	private byte[] partialSignature;
	private byte[] messageSignature;

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
		this.partialSignature = null;
		this.messageSignature = null;
	}

	public Message(MsgType type, int viewNumber, int senderId, TreeNode node,
			QuorumCertificate justify, byte[] partialSignature) {
		this.type = type;
		this.viewNumber = viewNumber;
		this.senderId = senderId;
		this.node = node;
		this.justify = justify;
		this.partialSignature = partialSignature;
		this.messageSignature = null;
	}

	public MsgType getType() { return type; }
	public int getViewNumber() { return viewNumber; }
	public int getSenderId() { return senderId; }
	public TreeNode getTreeNode() { return node; }
	public QuorumCertificate getJustify() { return justify; }
	public byte[] getPartialSignature() { return partialSignature; }
	public byte[] getMessageSignature() { return messageSignature; }
	public void setMessageSignature(byte[] messageSignature) { this.messageSignature = messageSignature; }

	public byte[] getSignableBytes() {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
			dos.writeInt(type.ordinal());
			dos.writeInt(viewNumber);
			dos.writeInt(senderId);
			if (node != null && node.getHash() != null) dos.write(node.getHash());
			if (justify != null) {
				dos.writeInt(justify.getType().ordinal());
				dos.writeInt(justify.getViewNumber());
				if (justify.getNode() != null && justify.getNode().getHash() != null) {
					dos.write(justify.getNode().getHash());
				}
			}
			if (partialSignature != null) dos.write(partialSignature);
			dos.flush();
			return bos.toByteArray();
		} catch (IOException e) {
			return new byte[0];
		}
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
