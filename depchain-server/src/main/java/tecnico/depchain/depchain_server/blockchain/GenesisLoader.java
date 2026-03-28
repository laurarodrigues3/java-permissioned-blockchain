package tecnico.depchain.depchain_server.blockchain;

import com.google.gson.Gson;

import tecnico.depchain.depchain_common.blockchain.Transaction;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

public class GenesisLoader {

    public static class GenesisAccount {
        public String balance;
        public int nonce;
    }

    public static class GenesisTransaction {
        public String from;
        public String to; // can be null
        public long gasLimit;
        public long gasPrice;
        public String value;
        public String data;
    }

    public static class GenesisFile {
        public String block_hash;
        public String previous_block_hash;
        public Map<String, GenesisAccount> state;
        public List<GenesisTransaction> transactions;
    }

    public static void loadGenesis(String filePath) throws IOException {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(filePath)) {
            GenesisFile genesis = gson.fromJson(reader, GenesisFile.class);
            EVM evm = EVM.getInstance();

            // 1. Carregar o Estado Inicial (EOAs)
            if (genesis.state != null) {
                for (Map.Entry<String, GenesisAccount> entry : genesis.state.entrySet()) {
                    Address address = Address.fromHexString(entry.getKey());
                    Wei balance = Wei.of(new BigInteger(entry.getValue().balance));
                    evm.createEOA(address, balance);
                }
            }

            if (genesis.transactions == null || genesis.transactions.isEmpty()) {
                System.out.println("Sem transacoes no Genesis JSON.");
                return;
            }

            // 2. Iterar e Executar Transações de Deploy ou Transfer
            long currentNonce = 0;
            for (GenesisTransaction tx : genesis.transactions) {
                Address sender = Address.fromHexString(tx.from);
                TransactionRunner runner = new TransactionRunner(evm.getUpdater(), sender);

                Transaction t = new Transaction(
                        BigInteger.valueOf(currentNonce++),
                        sender,
                        tx.to != null ? Address.fromHexString(tx.to) : null,
                        Wei.of(tx.gasPrice), // gasPrice
                        Wei.ZERO, Wei.ZERO, // fees
                        tx.gasLimit,
                        Wei.of(new BigInteger(tx.value)),
                        Bytes.fromHexString(tx.data)
                );

                if (tx.to == null) { // Deploy Contract
                    Address contractAddress = runner.executeContractCreation(t);
                    if (contractAddress == null) {
                        throw new RuntimeException("Falha ao criar o contrato no Genesis");
                    }
                    System.out.println("Contrato gerado no endereco: " + contractAddress.toHexString());
                } else { // Normal execution
                    if (!runner.executeTransaction(t)) {
                        throw new RuntimeException("Falha ao executar transacao no Genesis");
                    }
                }
                runner.getUpdater().commit();
            }
        }
    }
}
