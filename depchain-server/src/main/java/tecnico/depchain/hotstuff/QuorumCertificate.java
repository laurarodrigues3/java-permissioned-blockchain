package tecnico.depchain.hotstuff;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import tecnico.depchain.hotstuff.Message.MsgType;

/**
 * A Quorum Certificate aggregates (n-f) votes for a specific (type, view, node) tuple.
 *
 * <p>For Byzantine fault tolerance (Step 5), each vote carries an Ed25519 signature
 * over the canonical vote data (type, viewNumber, nodeHash). The QC stores these
 * individual signatures so any replica can independently verify the certificate.
 */
public class QuorumCertificate implements Serializable {
	private MsgType type;
	private int viewNumber;
	private TreeNode node;

	// replicaId → Ed25519 signature over (type, viewNumber, nodeHash)
	private Map<Integer, byte[]> signatures;

	// Threshold (k,n) signature over (type, viewNumber, nodeHash).
	// Created by the leader after collecting n-f individually verified votes.
	private byte[] thresholdSignature;

	public QuorumCertificate(MsgType type, int viewNumber, TreeNode node) {
		this.type = type;
		this.viewNumber = viewNumber;
		this.node = node;
		this.signatures = new HashMap<>();
	}

	/**
	 * Add a verified vote with its signature (Byzantine-tolerant path).
	 * The caller should verify the signature before calling this method.
	 */
	public void addVote(int replicaId, byte[] signature) {
		signatures.put(replicaId, signature);
	}

	public int getVoteCount() {
		return signatures.size();
	}

	public boolean hasQuorum(int quorumSize) {
		return signatures.size() >= quorumSize;
	}

	public Set<Integer> getVoterIds() {
		return signatures.keySet();
	}

	public Map<Integer, byte[]> getSignatures() {
		return signatures;
	}

	/**
	 * Verify this QC using the best available method:
	 * 1. BLS Threshold signature verification – O(1) mathematical pairing.
	 * 2. BLS Individual signatures (fallback)
	 * 3. Individual Ed25519 signatures (fallback for crash-only mode)
	 */
	public boolean verify(CryptoService crypto, ThresholdCrypto tc, int quorumSize) {
		if (node == null) return false;

		if (tc != null && thresholdSignature != null) {
			byte[] voteData = CryptoService.buildVoteData(type, viewNumber, node.getHash());
			if (tc.verifyThreshold(voteData, thresholdSignature)) {
				return true;
			}
		}

		if (tc != null) {
			return verifyIndividualBLS(tc, quorumSize);
		}

		return verifyIndividual(crypto, quorumSize);
	}

	private boolean verifyIndividualBLS(ThresholdCrypto tc, int quorumSize) {
		byte[] nodeHash = node.getHash();
		int validCount = 0;
		byte[] voteData = CryptoService.buildVoteData(type, viewNumber, nodeHash);

		for (var entry : signatures.entrySet()) {
			int voterId = entry.getKey();
			byte[] sig = entry.getValue();

			if (sig == null) continue;

			if (tc.verifyPartial(voterId, voteData, sig)) {
				validCount++;
			}
		}

		return validCount >= quorumSize;
	}

	/**
	 * Verify all stored individual Ed25519 signatures.
	 * Returns true if at least quorumSize signatures are cryptographically valid.
	 * Null signatures are NOT counted.
	 */
	private boolean verifyIndividual(CryptoService crypto, int quorumSize) {
		byte[] nodeHash = node.getHash();
		int validCount = 0;

		for (var entry : signatures.entrySet()) {
			int voterId = entry.getKey();
			byte[] sig = entry.getValue();

			if (sig == null) continue;

			if (crypto.verifyVote(voterId, type, viewNumber, nodeHash, sig)) {
				validCount++;
			}
		}

		return validCount >= quorumSize;
	}

	public void setThresholdSignature(byte[] sig) { this.thresholdSignature = sig; }
	public byte[] getThresholdSignature() { return thresholdSignature; }

	/**
	 * Verify the threshold signature against the shared public key.
	 */
	public boolean verifyThreshold(ThresholdCrypto tc, byte[] thresholdPublicKey) {
		if (thresholdSignature == null || node == null || thresholdPublicKey == null || tc == null) return false;
		byte[] voteData = CryptoService.buildVoteData(type, viewNumber, node.getHash());
		return tc.verifyThreshold(voteData, thresholdSignature);
	}

	public MsgType getType() { return type; }
	public int getViewNumber() { return viewNumber; }
	public TreeNode getNode() { return node; }

	public boolean matchingQC(MsgType type, int viewNumber) {
		return this.type == type && this.viewNumber == viewNumber;
	}
}
