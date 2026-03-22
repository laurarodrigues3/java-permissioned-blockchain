package tecnico.depchain.depchain_server.blockchain;

import java.io.Serializable;

import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.datatypes.Address;

public abstract class AccountState implements Serializable {
	private final Address address;
	private Wei balance;
	private BigInteger nonce;

	public AccountState(Address address, Wei balance, BigInteger nonce) {
		this.address = address;
		this.balance = balance;
		this.nonce = nonce;
	}

	public Address getAddress() {
		return address;
	}

	public Wei getBalance() {
		return balance;
	}

	public void setBalance(Wei newBalance) {
		balance = newBalance;
	}

	public BigInteger getNonce() {
		return nonce;
	}

	public void bumpNonce() {
		nonce = nonce.add(BigInteger.ONE);
	}
}
