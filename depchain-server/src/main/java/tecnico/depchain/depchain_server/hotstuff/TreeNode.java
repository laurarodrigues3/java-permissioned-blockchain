package tecnico.depchain.depchain_server.hotstuff;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;

import tecnico.depchain.depchain_common.DepchainUtils;
import tecnico.depchain.depchain_server.blockchain.Block;

public class TreeNode implements Serializable {
	private byte[] parentHash;
	private Block blk;
	private byte[] ownHash;

	// Transient: not serialized, used for in-memory tree traversal
	private transient TreeNode parent;

	public TreeNode(TreeNode parent, Block blk) {
		this.parent = parent;
		this.parentHash = parent != null ? parent.getHash() : new byte[32];
		this.blk = blk;

		calculateHash();
	}

	public TreeNode(byte[] parentHash, Block blk) {
		this.parent = null;
		this.parentHash = parentHash;
		this.blk = blk;

		calculateHash();
	}

	private void calculateHash()
	{
		byte[] txBytes = blk.serialize();
		ByteBuffer buf = ByteBuffer.allocate(parentHash.length + txBytes.length);
		buf.put(parentHash);
		buf.put(txBytes);
		ownHash = DepchainUtils.sha256(buf.array());
		System.err.println("TreeNode Hash Calc: parentHash=" + Arrays.toString(parentHash) + " blk=" + blk + " ownHash=" + Arrays.toString(ownHash));
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
	public Block getBlock() { return blk; }
	public byte[] getHash() { return ownHash; }
}
