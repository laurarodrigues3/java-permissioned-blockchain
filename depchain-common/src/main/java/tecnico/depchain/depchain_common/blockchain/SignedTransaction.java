package tecnico.depchain.depchain_common.blockchain;

import java.io.Serializable;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

public record SignedTransaction(
	Transaction tx,
	byte[] signature ) implements Serializable {

	public static SignedTransaction signTansaction(Transaction tx, PrivateKey key)
	{
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initSign(key);
			sig.update(tx.serialize());
			byte[] signature = sig.sign();
			return new SignedTransaction(tx, signature);
		} catch (Exception e) {
			throw new RuntimeException("Failed to sign TransactionMessage", e);
		}
	}

	public boolean verify(PublicKey key) {
		if (this.signature == null) return false;
		try {
			Signature sig = Signature.getInstance("Ed25519");
			sig.initVerify(key);
			sig.update(tx.serialize());
			return sig.verify(this.signature);
		} catch (Exception e) {
			return false;
		}
	}
}
