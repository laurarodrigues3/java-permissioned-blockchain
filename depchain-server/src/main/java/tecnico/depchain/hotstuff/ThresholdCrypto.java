package tecnico.depchain.hotstuff;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unisa.dia.gas.jpbc.Element;
import it.unisa.dia.gas.jpbc.Field;
import it.unisa.dia.gas.jpbc.Pairing;
import it.unisa.dia.gas.plaf.jpbc.pairing.PairingFactory;
import it.unisa.dia.gas.plaf.jpbc.pairing.a.TypeACurveGenerator;
import it.unisa.dia.gas.plaf.jpbc.pairing.parameters.PropertiesParameters;

/**
 * Implements BLS Threshold Signatures using the JPBC (Java Pairing-Based Cryptography) library.
 * This is a non-interactive scheme requiring only 1 round of communication.
 * Validations are mathematically robust and suitable for the Basic HotStuff protocol.
 */
public class ThresholdCrypto {
	private final int replicaId;
	private final int threshold;
	private final int numReplicas;

	private final Pairing pairing;
	private final Element generator; // g in G1
	private final Element globalPublicKey; // PK = g^s in G1
	private final Element myPrivateShare; // sk_i in Zr

	private final Map<Integer, Element> publicShares; // PK_j = g^{sk_j} in G1

	public ThresholdCrypto(int replicaId, int threshold, int numReplicas,
			String pairingParamsStr, byte[] generatorBytes, byte[] globalPublicKeyBytes,
			byte[] myPrivateShareBytes, Map<Integer, byte[]> publicSharesBytes) {
		this.replicaId = replicaId;
		this.threshold = threshold;
		this.numReplicas = numReplicas;

		PropertiesParameters params = new PropertiesParameters();
		try {
			params.load(new ByteArrayInputStream(pairingParamsStr.getBytes()));
		} catch (Exception e) {
			throw new RuntimeException("Failed to load pairing params", e);
		}
		this.pairing = PairingFactory.getPairing(params);
		Field<?> g1 = pairing.getG1();
		Field<?> zr = pairing.getZr();

		this.generator = g1.newElementFromBytes(generatorBytes).getImmutable();
		this.globalPublicKey = g1.newElementFromBytes(globalPublicKeyBytes).getImmutable();
		this.myPrivateShare = zr.newElementFromBytes(myPrivateShareBytes).getImmutable();

		this.publicShares = new HashMap<>();
		if (publicSharesBytes != null) {
			for (Map.Entry<Integer, byte[]> entry : publicSharesBytes.entrySet()) {
				this.publicShares.put(entry.getKey(), g1.newElementFromBytes(entry.getValue()).getImmutable());
			}
		}
	}

	private Element hashToG1(byte[] data) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(data);
			return pairing.getG1().newElementFromHash(hash, 0, hash.length).getImmutable();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Creates a BLS partial signature over the given data.
	 * sig_i = H(m)^{sk_i}
	 */
	public byte[] signPartial(byte[] data) {
		Element h = hashToG1(data);
		Element sig = h.powZn(myPrivateShare).getImmutable();
		return sig.toBytes();
	}

	/**
	 * Verify an individual partial signature from a sender.
	 * e(sig_i, g) == e(H(m), pk_i)
	 */
	public boolean verifyPartial(int senderId, byte[] data, byte[] signatureBytes) {
		if (!publicShares.containsKey(senderId)) return false;
		try {
			Element h = hashToG1(data);
			Element sig = pairing.getG1().newElementFromBytes(signatureBytes);
			Element pk = publicShares.get(senderId);

			Element e1 = pairing.pairing(sig, generator);
			Element e2 = pairing.pairing(h, pk);
			return e1.isEqual(e2);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Aggregates t valid partial signatures into a single threshold signature via Lagrange interpolation.
	 */
	public byte[] aggregateShares(Map<Integer, byte[]> partialSigs) {
		if (partialSigs.size() < threshold) {
			throw new IllegalArgumentException("Not enough signatures for threshold");
		}

		Field<?> zr = pairing.getZr();
		Element aggregatedSig = pairing.getG1().newOneElement();

		// Use exactly 'threshold' signatures for standard Lagrange interpolation
		List<Integer> S = new ArrayList<>();
		int count = 0;
		for (Integer id : partialSigs.keySet()) {
			S.add(id);
			count++;
			if (count == threshold) break;
		}

		for (int i : S) {
			Element lambda = zr.newOneElement();
			int xi = i + 1; // Evaluated at x = id + 1
			for (int j : S) {
				if (i != j) {
					int xj = j + 1;
					// lambda_i = product (0 - xj) / (xi - xj)
					Element num = zr.newElement(-xj);
					Element den = zr.newElement(xi - xj);
					den.invert();
					num.mul(den);
					lambda.mul(num);
				}
			}

			Element sig_i = pairing.getG1().newElementFromBytes(partialSigs.get(i));
			Element term = sig_i.powZn(lambda);
			aggregatedSig.mul(term);
		}

		return aggregatedSig.getImmutable().toBytes();
	}

	/**
	 * Global verification of the threshold signature in O(1) pairings.
	 * e(sig_{global}, g) == e(H(m), PK_{global})
	 */
	public boolean verifyThreshold(byte[] data, byte[] thresholdSignatureBytes) {
		if (data == null || thresholdSignatureBytes == null) return false;
		try {
			Element h = hashToG1(data);
			Element sig = pairing.getG1().newElementFromBytes(thresholdSignatureBytes);

			Element e1 = pairing.pairing(sig, generator);
			Element e2 = pairing.pairing(h, globalPublicKey);
			return e1.isEqual(e2);
		} catch (Exception e) {
			return false;
		}
	}

	// ==============================================
	// Trusted Dealer Setup Methods (for testing / init)
	// ==============================================

	public static class DealerParams implements Serializable {
		public String pairingParamsStr;
		public byte[] generator;
		public byte[] globalPublicKey;
		public Map<Integer, byte[]> privateShares;
		public Map<Integer, byte[]> publicShares;
	}

	public static DealerParams generateParams(int threshold, int numReplicas) {
		TypeACurveGenerator cg = new TypeACurveGenerator(160, 512);
		PropertiesParameters params = (PropertiesParameters) cg.generate();
		Pairing pairing = PairingFactory.getPairing(params);

		Field<?> g1 = pairing.getG1();
		Field<?> zr = pairing.getZr();

		Element generator = g1.newRandomElement().getImmutable();

		Element[] coefs = new Element[threshold];
		for (int i = 0; i < threshold; i++) {
			coefs[i] = zr.newRandomElement().getImmutable();
		}

		Element globalSecretKey = coefs[0];
		Element globalPublicKey = generator.powZn(globalSecretKey).getImmutable();

		Map<Integer, byte[]> privateShares = new HashMap<>();
		Map<Integer, byte[]> publicShares = new HashMap<>();

		for (int i = 0; i < numReplicas; i++) {
			Element x = zr.newElement(i + 1);
			Element y = zr.newZeroElement();
			for (int k = threshold - 1; k >= 0; k--) {
				y.mul(x).add(coefs[k]);
			}
			Element sk_i = y.getImmutable();
			Element pk_i = generator.powZn(sk_i).getImmutable();

			privateShares.put(i, sk_i.toBytes());
			publicShares.put(i, pk_i.toBytes());
		}

		DealerParams res = new DealerParams();
		res.pairingParamsStr = params.toString();
		res.generator = generator.toBytes();
		res.globalPublicKey = globalPublicKey.toBytes();
		res.privateShares = privateShares;
		res.publicShares = publicShares;
		return res;
	}
	
	public int getReplicaId() { return replicaId; }
	public int getThreshold() { return threshold; }
}
