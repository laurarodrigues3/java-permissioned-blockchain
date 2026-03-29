package tecnico.depchain.depchain_server.blockchain;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import tecnico.depchain.depchain_common.blockchain.Transaction;

public class BlockRunner {
	private final WorldUpdater updater;
	private final Address minter;
	public static final long maxBlockGas = 15_000_000L; //15M is close to ethereum's average target

	public BlockRunner(WorldUpdater updater, Address minter) {
		this.updater = updater;
		this.minter = minter;
	}

	public WorldUpdater getUpdater() {
		return updater;
	}

	/**
	 * Runs all transactions in a block, discarding the effects.
	 * Does **NOT** flush NOR modify the world updater.
	 * @param blk Block to run
	 * @return true if all transactions succeeded
	 */
	public boolean dryRunBlock(Block blk) {
		WorldUpdater tempUpdater = updater.updater();
		BlockRunner tempRunner = new BlockRunner(tempUpdater, minter);
		return tempRunner.runBlock(blk);
	}

	/**
	 * Runs all transactions in a block.
	 * Does **NOT** flush the world updater.
	 * @param blk Block to run
	 * @return true if all transactions succeeded
	 */
	public boolean runBlock(Block blk) {
		TransactionRunner runner = new TransactionRunner(updater, minter);

		for (Transaction tx : blk.getTransactions()) {
			if (!runner.executeTransaction(tx))
				return false;
		}

		return true;
	}
}
