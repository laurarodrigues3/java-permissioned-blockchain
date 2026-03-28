package tecnico.depchain.depchain_server.blockchain;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import tecnico.depchain.depchain_common.blockchain.Transaction;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Responsible for serializing and deserializing {@link Block} objects to/from JSON,
 * matching the genesis block format required by the DepChain specification.
 *
 * Output format example:
 * <pre>
 * {
 *   "block_hash": "0xabc...",
 *   "previous_block_hash": "0xdef...",
 *   "transactions": [ ... ],
 *   "state": {
 *     "0x1111...": { "balance": "100000", "nonce": 0, "codeHash": null }
 *   }
 * }
 * </pre>
 */
public class BlockPersister {

    private static final Gson GSON = createGson();

    private final Path storageDirectory;

    /**
     * @param storageDirectory Directory where block JSON files will be saved.
     *                         Created automatically if it does not exist.
     */
    public BlockPersister(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
    }

    /**
     * Persists a block to disk as {@code block_<blockNumber>.json}.
     *
     * @param block       The block to save
     * @param blockNumber The sequential block number (0 = genesis, 1, 2, ...)
     * @return The path of the created file
     */
    public Path saveBlock(Block block, int blockNumber) throws IOException {
        Files.createDirectories(storageDirectory);
        Path filePath = storageDirectory.resolve("block_" + blockNumber + ".json");

        try (Writer writer = new BufferedWriter(new FileWriter(filePath.toFile()))) {
            GSON.toJson(block, writer);
        }
        return filePath;
    }

    /**
     * Loads a block from a JSON file.
     *
     * @param blockNumber The block number to load
     * @return The deserialized Block
     */
    public Block loadBlock(int blockNumber) throws IOException {
        Path filePath = storageDirectory.resolve("block_" + blockNumber + ".json");
        try (Reader reader = new BufferedReader(new FileReader(filePath.toFile()))) {
            return GSON.fromJson(reader, Block.class);
        }
    }

    /**
     * Loads a block from an arbitrary file path.
     */
    public static Block loadBlockFromFile(String filePath) throws IOException {
        try (Reader reader = new BufferedReader(new FileReader(filePath))) {
            return GSON.fromJson(reader, Block.class);
        }
    }

    /**
     * Serializes a Block to a JSON string (useful for debugging/testing).
     */
    public static String toJson(Block block) {
        return GSON.toJson(block);
    }

    // ── Gson Configuration ──────────────────────────────────────────────

    private static Gson createGson() {
        return new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerTypeAdapter(Address.class, new AddressAdapter())
                .registerTypeAdapter(Wei.class, new WeiAdapter())
                .registerTypeAdapter(Bytes.class, new BytesAdapter())
                .registerTypeAdapter(BigInteger.class, new BigIntegerAdapter())
                .registerTypeAdapter(Transaction.class, new TransactionSerializer())
                .registerTypeAdapter(Transaction.class, new TransactionDeserializer())
                .create();
    }

    // ── Type Adapters for Besu types ────────────────────────────────────

    /** Serializes/deserializes Address as "0x..." hex string. */
    private static class AddressAdapter extends TypeAdapter<Address> {
        @Override
        public void write(JsonWriter out, Address value) throws IOException {
            out.value(value != null ? value.toHexString() : null);
        }
        @Override
        public Address read(JsonReader in) throws IOException {
            String hex = in.nextString();
            return hex != null ? Address.fromHexString(hex) : null;
        }
    }

    /** Serializes/deserializes Wei as decimal string of its BigInteger value. */
    private static class WeiAdapter extends TypeAdapter<Wei> {
        @Override
        public void write(JsonWriter out, Wei value) throws IOException {
            out.value(value != null ? value.toBigInteger().toString() : null);
        }
        @Override
        public Wei read(JsonReader in) throws IOException {
            String val = in.nextString();
            return val != null ? Wei.of(new BigInteger(val)) : Wei.ZERO;
        }
    }

    /** Serializes/deserializes Bytes as "0x..." hex string. */
    private static class BytesAdapter extends TypeAdapter<Bytes> {
        @Override
        public void write(JsonWriter out, Bytes value) throws IOException {
            out.value(value != null ? value.toHexString() : null);
        }
        @Override
        public Bytes read(JsonReader in) throws IOException {
            String hex = in.nextString();
            return hex != null ? Bytes.fromHexString(hex) : Bytes.EMPTY;
        }
    }

    /** Serializes BigInteger as a plain string. */
    private static class BigIntegerAdapter extends TypeAdapter<BigInteger> {
        @Override
        public void write(JsonWriter out, BigInteger value) throws IOException {
            out.value(value != null ? value.toString() : null);
        }
        @Override
        public BigInteger read(JsonReader in) throws IOException {
            String val = in.nextString();
            return val != null ? new BigInteger(val) : BigInteger.ZERO;
        }
    }

    /**
     * Custom serializer for Transaction record. Maps fields to genesis-compatible JSON:
     * { "from": "0x...", "to": "0x..." or null, "gasLimit": 5000000, "gasPrice": 10,
     *   "value": "0", "data": "0x...", "nonce": "0" }
     */
    private static class TransactionSerializer implements JsonSerializer<Transaction> {
        @Override
        public JsonElement serialize(Transaction tx, java.lang.reflect.Type typeOfSrc,
                                     JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("nonce", tx.nonce() != null ? tx.nonce().toString() : null);
            obj.addProperty("from", tx.from() != null ? tx.from().toHexString() : null);
            if (tx.to() != null) {
                obj.addProperty("to", tx.to().toHexString());
            } else {
                obj.add("to", JsonNull.INSTANCE);
            }
            obj.addProperty("gasLimit", tx.gasLimit());
            obj.addProperty("gasPrice", tx.gasPrice() != null ? tx.gasPrice().toBigInteger().toString() : "0");
            obj.addProperty("value", tx.value() != null ? tx.value().toBigInteger().toString() : "0");
            obj.addProperty("data", tx.data() != null ? tx.data().toHexString() : "");
            return obj;
        }
    }

    /**
     * Custom deserializer for Transaction record. Reads genesis-compatible JSON format.
     */
    private static class TransactionDeserializer implements JsonDeserializer<Transaction> {
        @Override
        public Transaction deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                       JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            BigInteger nonce = obj.has("nonce") && !obj.get("nonce").isJsonNull()
                    ? new BigInteger(obj.get("nonce").getAsString()) : BigInteger.ZERO;
            Address from = obj.has("from") && !obj.get("from").isJsonNull()
                    ? Address.fromHexString(obj.get("from").getAsString()) : null;
            Address to = obj.has("to") && !obj.get("to").isJsonNull()
                    ? Address.fromHexString(obj.get("to").getAsString()) : null;
            long gasLimit = obj.has("gasLimit") ? obj.get("gasLimit").getAsLong() : 0;
            Wei gasPrice = obj.has("gasPrice") && !obj.get("gasPrice").isJsonNull()
                    ? Wei.of(new BigInteger(obj.get("gasPrice").getAsString())) : Wei.ZERO;
            Wei value = obj.has("value") && !obj.get("value").isJsonNull()
                    ? Wei.of(new BigInteger(obj.get("value").getAsString())) : Wei.ZERO;
            Bytes data = obj.has("data") && !obj.get("data").isJsonNull()
                    ? Bytes.fromHexString(obj.get("data").getAsString()) : Bytes.EMPTY;

            return new Transaction(
                    nonce, from, to, gasPrice,
                    Wei.ZERO, Wei.ZERO, // maxPriorityFeePerGas, maxFeePerGas (not used in genesis format)
                    gasLimit, value, data
            );
        }
    }
}
