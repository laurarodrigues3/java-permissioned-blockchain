package tecnico.depchain.depchain_server.blockchain;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

public class EVM {
	private static EVM singleton = null;

	private SimpleWorld world;
	private WorldUpdater updater;

	private EVM() {
		//FIXME: Should read the state from a file or similar
		world = new SimpleWorld();
		updater = world.updater();
	}

	public static EVM getInstance() {
		if (singleton == null)
			singleton = new EVM();
		return singleton;
	}

	public void createEOA(Address address, Wei balance) {
		MutableAccount eoa = updater.createAccount(address);
		eoa.setNonce(0);
		eoa.setBalance(balance);
		updater.commit();
	}

	public void createCA(Address address, Bytes code) {
		MutableAccount ca = updater.createAccount(address);
		ca.setCode(code);
		ca.setNonce(1); //0 is contract creation
		ca.setBalance(Wei.ZERO);
		updater.commit();
	}

	public boolean executeBlock(Block block, Address minter, boolean commit) {
		TransactionRunner runner = new TransactionRunner(updater.updater(), minter);

		for (var tx : block.transactions())
			if (!runner.executeTransaction(tx))
				return false;

		if (commit)
			runner.getUpdater().commit();

		return true;
	}

	public boolean executeTransaction(Transaction tx, Address minter, boolean commit) {
		TransactionRunner runner = new TransactionRunner(updater.updater(), minter);
		if (!runner.executeTransaction(tx))
			return false;

		if (commit)
			runner.getUpdater().commit();

		return true;
	}
}
