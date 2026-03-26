package tecnico.depchain.depchain_server.blockchain;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import tecnico.depchain.depchain_common.blockchain.Transaction;

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

	public WorldUpdater getUpdater() {
		return updater;
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
		// Statement: The execution order of these transactions should be based on transaction fees
		// FIXME: Risk of BFT fork. Since replicas reorder the block locally, if there are two transactions
		// with exactly the same gasPrice, the tie may be resolved differently across machines
		// (depending on the original arrival order in the mempool).
		// In a strict BFT system, we would use a deterministic tie-breaker (e.g., hash).
		// We assume this risk for simplicity, given that we only have a single Leader dictating the block.
		block.transactions().sort(
			java.util.Comparator.comparing((Transaction t) -> t.gasPrice()).reversed()
		);

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
