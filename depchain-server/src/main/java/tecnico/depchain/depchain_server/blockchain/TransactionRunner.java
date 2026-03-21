package tecnico.depchain.depchain_server.blockchain;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

public class TransactionRunner {
	private WorldUpdater updater;
	private Address minter;

	private final Wei BASE_FEE = Wei.of(21_000);

	public TransactionRunner(WorldUpdater updater, Address minter) {
		this.updater = updater;
		this.minter = minter;
	}

	public WorldUpdater getUpdater() {
		return updater;
	}

	public boolean executeTransaction(Transaction tx) {
		Account destination = updater.get(tx.to());
		if (destination.hasCode())
			return executeContract(tx);
		else
			return executeTransfer(tx);
	}

	private boolean executeTransfer(Transaction tx) {
		MutableAccount sender = updater.getAccount(tx.sender());
		MutableAccount receiver = updater.getAccount(tx.to());
		MutableAccount minterAccount = updater.getAccount(minter);

		Wei valueAndFee = tx.value().add(BASE_FEE);

		if (sender.getBalance().lessThan(valueAndFee))
		{
			return false;
		}

		transfer(sender, receiver, tx.value());
		transfer(sender, minterAccount, BASE_FEE);

		return true;
	}

	private boolean executeContract(Transaction tx) {
		MutableAccount sender = updater.getAccount(tx.sender());
		MutableAccount receiver = updater.getAccount(tx.to());
		MutableAccount minterAccount = updater.getAccount(minter);

		Wei valueAndFee = tx.value().add(BASE_FEE);

		if (sender.getBalance().lessThan(valueAndFee))
			return false;

		// Smart contract invocations can also transfer
		transfer(sender, receiver, tx.value());
		transfer(sender, minterAccount, BASE_FEE);

		// Actual execution
		return execute(tx.sender(), minterAccount.getCode(), tx.data(), tx.gasPrice(), tx.gasLimit());
	}

	private void transfer(MutableAccount src, MutableAccount dest, Wei amount) {
		src.setBalance(src.getBalance().subtract(amount));
		dest.setBalance(dest.getBalance().add(amount));
	}

	private boolean execute(Address sender, Bytes code, Bytes selectorArgs, Wei gasPrice, long gasLimit) {

		EVMExecutor executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);

		// Set tracer (for output reading)
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
		StandardJsonTracer tracer = new StandardJsonTracer(printStream, true, true, true, true);
		executor.tracer(tracer);

		// Set fees
		executor.baseFee(BASE_FEE);
		executor.gasLimit(gasLimit);
		executor.gasPriceGWei(gasPrice);

		// Set code, function and args
		executor.code(code);
		executor.callData(selectorArgs);

		// Other sets
		executor.sender(sender);
		executor.receiver(minter);
		executor.worldUpdater(updater);

		// Run actual contract code
		executor.execute();

		//REVIEW: What to do with return values?
		return true; //REVIEW: Does it ever not return true?
	}
}
