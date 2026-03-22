package tecnico.depchain.depchain_server.blockchain;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.datatypes.Address;

public class EOAState extends AccountState {
	public EOAState(Address address, Wei balance, BigInteger nonce) {
		super(address, balance, nonce);
	}
}
