package tecnico.depchain.hotstuff;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;

import tecnico.depchain.DepchainUtils;

public class TreeNode implements Serializable {
	private byte[] parentHash;
	private String command;
	private byte[] ownHash;

	// Transient: not serialized, used for in-memory tree traversal
	private transient TreeNode parent;

	public TreeNode(TreeNode parent, String command) {
		this.parent = parent;
		this.parentHash = parent != null ? parent.getHash() : new byte[32];
		this.command = command;

		calculateHash();
	}

	public TreeNode(byte[] parentHash, String command) {
		this.parent = null;
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

	/**
	 * Check if this node's branch extends from the given ancestor node.
	 * Walks up the parent chain looking for a matching hash.
	 */
	public boolean extendsFrom(TreeNode ancestor) {
		if (ancestor == null) return true;

		TreeNode current = this;
		while (current != null) {
			if (Arrays.equals(current.getHash(), ancestor.getHash()))
				return true;
			current = current.parent;
		}
		return false;
	}

	public void setParent(TreeNode parent) { this.parent = parent; }
	public TreeNode getParent() { return parent; }
	public byte[] getParentHash() { return parentHash; }
	public String getCommand() { return command; }
	public byte[] getHash() { return ownHash; }
}
