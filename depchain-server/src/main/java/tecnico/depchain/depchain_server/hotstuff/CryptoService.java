package tecnico.depchain.depchain_server.hotstuff;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;

import tecnico.depchain.depchain_server.hotstuff.Message.MsgType;

/**
 * Manages Ed25519 asymmetric key pairs and digital signatures for the HotStuff
 * protocol. Each replica holds its own private key and all replicas' public keys
 * (pre-distributed PKI as described in the enunciado).
 *
 * Vote signatures follow the paper: sign(type, viewNumber, nodeHash).
 */
public class CryptoService {
	private final int replicaId;
	private final PrivateKey privateKey;
	private final List<PublicKey> publicKeys;
	private final byte[] thresholdPublicKey;

	public CryptoService(int replicaId, KeyPair myKeyPair, List<PublicKey> allPublicKeys,
			byte[] thresholdPublicKey) {
		this.replicaId = replicaId;
		this.privateKey = myKeyPair.getPrivate();
		this.publicKeys = new ArrayList<>(allPublicKeys);
		this.thresholdPublicKey = thresholdPublicKey;
	}

	public CryptoService(int replicaId, KeyPair myKeyPair, List<PublicKey> allPublicKeys) {
		this(replicaId, myKeyPair, allPublicKeys, null);
	}

	/**
	 * Pre-generate key pairs for all replicas (used during testing).
	 */
	public static List<KeyPair> generateKeyPairs(int n) throws NoSuchAlgorithmException {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
		List<KeyPair> pairs = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			pairs.add(kpg.generateKeyPair());
		}
		return pairs;
	}

	/**
	 * Extract the list of public keys from a list of key pairs for distribution (used in testing).
	 */
	public static List<PublicKey> extractPublicKeys(List<KeyPair> keyPairs) {
		List<PublicKey> pubs = new ArrayList<>();
		for (KeyPair kp : keyPairs)
			pubs.add(kp.getPublic());
		return pubs;
	}

	/**
	 * Sign arbitrary data with this replica's Ed25519 private key.
	 */
	public byte[] sign(byte[] data) {
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initSign(privateKey);
			sig.update(data);
			return sig.sign();
		} catch (Exception e) {
			throw new RuntimeException("Ed25519 signing failed", e);
		}
	}


	public boolean verify(int senderId, byte[] data, byte[] signature) {
		if (senderId < 0 || senderId >= publicKeys.size() || signature == null)
			return false;
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initVerify(publicKeys.get(senderId));
			sig.update(data);
			return sig.verify(signature);
		} catch (Exception e) {
			return false;
		}
	}

	public static byte[] buildVoteData(MsgType type, int viewNumber, byte[] nodeHash) {
		int hashLen = (nodeHash != null) ? nodeHash.length : 0;
		ByteBuffer buf = ByteBuffer.allocate(1 + 4 + hashLen);
		buf.put((byte) type.ordinal());
		buf.putInt(viewNumber);
		if (nodeHash != null) buf.put(nodeHash);
		return buf.array();
	}

	/**
	 * Convenience: sign a vote tuple using this replica's key.
	 */
	public byte[] signVote(MsgType type, int viewNumber, byte[] nodeHash) {
		return sign(buildVoteData(type, viewNumber, nodeHash));
	}

	/**
	 * Convenience: verify a vote signature from a specific replica.
	 */
	public boolean verifyVote(int senderId, MsgType type, int viewNumber, byte[] nodeHash, byte[] signature) {
		return verify(senderId, buildVoteData(type, viewNumber, nodeHash), signature);
	}

	public int getReplicaId() { return replicaId; }
	public PublicKey getPublicKey() { return publicKeys.get(replicaId); }
	public PublicKey getPublicKey(int id) { return publicKeys.get(id); }
	public byte[] getThresholdPublicKey() { return thresholdPublicKey; }
}
