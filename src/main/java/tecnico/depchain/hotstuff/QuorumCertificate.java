package tecnico.depchain.hotstuff;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import tecnico.depchain.hotstuff.Message.MsgType;

public class QuorumCertificate implements Serializable {
	private MsgType type;
	private int viewNumber;
	private TreeNode node;
	private Set<Integer> voterIds;

	public QuorumCertificate(MsgType type, int viewNumber, TreeNode node) {
		this.type = type;
		this.viewNumber = viewNumber;
		this.node = node;
		this.voterIds = new HashSet<>();
	}

	public void addVoter(int replicaId) {
		voterIds.add(replicaId);
	}

	public int getVoteCount() {
		return voterIds.size();
	}

	public boolean hasQuorum(int quorumSize) {
		return voterIds.size() >= quorumSize;
	}

	public MsgType getType() { return type; }
	public int getViewNumber() { return viewNumber; }
	public TreeNode getNode() { return node; }
	public Set<Integer> getVoterIds() { return voterIds; }

	public boolean matchingQC(MsgType type, int viewNumber) {
		return this.type == type && this.viewNumber == viewNumber;
	}
}
