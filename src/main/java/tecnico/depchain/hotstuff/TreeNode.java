package tecnico.depchain.hotstuff;

import java.io.Serializable;
import java.nio.ByteBuffer;

import tecnico.depchain.DepchainUtils;

public class TreeNode implements Serializable {
	private byte[] parentHash;
	private String command;
	private byte[] ownHash;

	public TreeNode(TreeNode parent, String command) {
		this.parentHash = parent != null ? parent.getHash() : new byte[32];
		this.command = command;

		calculateHash();
	}

	public TreeNode(byte[] parentHash, String command) {
		this.parentHash = parentHash;
		this.command = command;

		calculateHash();
	}

	private void calculateHash()
	{
		byte[] cmdBytes = command.getBytes();
		ByteBuffer buf = ByteBuffer.allocate(parentHash.length + cmdBytes.length);
		buf.put(parentHash);
		buf.put(cmdBytes);
		ownHash = DepchainUtils.sha256(buf.array());
	}

	public byte[] getParentHash() { return parentHash; }
	public String getCommand() { return command; }
	public byte[] getHash() { return ownHash; }
}
