package tecnico.depchain.depchain_server.blockchain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.apache.tuweni.bytes.Bytes;

import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.EvmSpecVersion;

import java.math.BigInteger;

public class GenesisLoaderTest {

    @Test
    public void testGenesisInitializationAndContractCall() throws Exception {
        // Setup: arrancar EVM e carregar o Genesis Block
        String genesisPath = "../genesis.json";
        EVM evm = EVM.getInstance();
        
        // A invocação já vai ler o json e popular o WorldUpdater
        // (Nota: Se tentarem correr o test múltiplas vezes na mesma execução JVM, o EVM Singleton mantém estado)
        GenesisLoader.loadGenesis(genesisPath);

        // 1. Assert do Saldo Nativo
        Address adminAddress = Address.fromHexString("0x1111111111111111111111111111111111111111");
        Wei currentBalance = evm.getUpdater().getAccount(adminAddress).getBalance();
        
        // Ao executar o deploy com o TransactionRunner, o custo de gás foi descontado ao saldo inicial.
        assertNotNull(evm.getUpdater().getAccount(adminAddress), "A conta Admin deve existir na EVM.");
        assertTrue(currentBalance.compareTo(Wei.ZERO) > 0, "O Admin deveria ter saldo positivo de DepCoin.");
        System.out.println("Saldo de DepCoin (Wei) do Admin apos o deploy e custos de gas: " + currentBalance.toBigInteger());

        // 2. Recuperar Endereços dos Contratos
        // Na EVM, address = keccak256(rlp(sender, nonce))
        // Como o AccessControl foi removido, a ISTCoin volta a ser a transação nonce 0!
        Address istCoinAddress = Address.contractAddress(adminAddress, 0L);

        System.out.println("\n--- Enderecos Recuperados ---");
        System.out.println("IST Coin:       " + istCoinAddress.toHexString());
        
        // Validar que os contratos foram mesmo implantados e contêm código
        assertTrue(evm.getUpdater().getAccount(istCoinAddress).hasCode(), "IST Coin deve ter código implantado.");

        // 3. A Prova dos 9 (Smart Contract Call - balanceOf)
        // keccak256("balanceOf(address)")[0..3] = 70a08231
        String selector = "70a08231";
        String paddedAddress = String.format("%64s", adminAddress.toUnprefixedHexString()).replace(' ', '0');
        Bytes callData = Bytes.fromHexString(selector + paddedAddress);

        // EVM Call sem criar transação formal (sem gastar gás)
        EVMExecutor executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        
        // Tracer manual para recolher o return data do balanceOf
        class ReturnDataTracer implements org.hyperledger.besu.evm.tracing.OperationTracer {
            private Bytes outputData = Bytes.EMPTY;
            @Override
            public void traceContextExit(org.hyperledger.besu.evm.frame.MessageFrame frame) {
                if (frame.getMessageFrameStack().isEmpty() || frame.getDepth() == 0) {
                    this.outputData = frame.getOutputData();
                }
            }
            public Bytes getOutputData() { return outputData; }
        }
        
        ReturnDataTracer tracer = new ReturnDataTracer();
        executor.tracer(tracer);
        executor.baseFee(Wei.ZERO);
        executor.gasLimit(1000000L); // Gás fictício suficiente para execução de leitura
        executor.gasPriceGWei(Wei.ZERO);
        
        executor.sender(adminAddress);
        executor.receiver(istCoinAddress);
        // Colocamos o código do contrato nativo no executor
        executor.code(evm.getUpdater().getAccount(istCoinAddress).getCode());
        executor.callData(callData);
        // Usamos um snapshot (updater.updater) para evitar escritas permanentes ao mundo
        executor.worldUpdater(evm.getUpdater().updater());
        
        // Executar
        executor.execute();

        // Extrair e avaliar o resultado
        Bytes output = tracer.getOutputData();
        assertNotNull(output, "O output da chamada não deve ser nulo.");
        assertFalse(output.isEmpty(), "A EVM deveria ter retornado bytes na chamada de balanceOf.");

        BigInteger tokenBalance = new BigInteger(1, output.toArray());
        
        // 100 milhões de IST Coins com 2 casas decimais = 100,000,000 * 10^2 = 10,000,000,000
        BigInteger expectedTokens = new BigInteger("10000000000");

        System.out.println("\n--- Resultado da leitura EVM ---");
        System.out.println("IST Coin BalanceOf(" + adminAddress.toHexString() + "): " + tokenBalance);

        assertEquals(expectedTokens, tokenBalance, "O número de IST Coins retornados deve ser exatamente 10000000000.");
    }
}
