package tecnico.depchain.depchain_server.blockchain;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.datatypes.Address;

public class CAState extends AccountState {
	private final String codeHash;
	private String storageHash;

	public CAState(Address address, Wei balance, BigInteger nonce, String codeHash, String storageHash) {
		super(address, balance, nonce);
		this.codeHash = codeHash;
		this.storageHash = storageHash;
	}

	public String getCodeHash() {
		return codeHash;
	}

	public String getStorageHash() {
		return storageHash;
	}

	public void setStorageHash(String newHash) {
		storageHash = newHash;
	}
}
