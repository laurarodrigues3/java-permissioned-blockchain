package tecnico.depchain.depchain_server.hotstuff;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import tecnico.depchain.depchain_common.KeyGenUtil;

/**
 * Test helper that reads (or generates) Ed25519 key pairs from a
 * config.properties file, rehydrating X.509 public keys and PKCS#8
 * private keys from their Base64 representations.
 *
 * This avoids calling CryptoService.generateKeyPairs() in tests and
 * validates the same key-serialisation path used by real replicas.
 */
public final class TestKeyHelper {

	private static final String CONFIG_FILE_NAME = "config.properties";

	/**
	 * Returns the path to the test-resources config.properties, which lives
	 * at {@code depchain-server/src/test/resources/config.properties}.
	 */
	private static Path configPath() {
		// Try to resolve relative to the working directory (which Maven sets
		// to the module root, i.e. depchain-server/).
		Path candidate = Paths.get("src", "test", "resources", CONFIG_FILE_NAME);
		if (Files.exists(candidate)) {
			return candidate;
		}
		// Fallback: resolve from project root (useful when CWD is the repo root).
		candidate = Paths.get("depchain-server", "src", "test", "resources", CONFIG_FILE_NAME);
		return candidate;
	}

	/**
	 * Reads {@code n} member key pairs from the test config.properties file.
	 * If the file does not exist it is generated automatically via
	 * {@link KeyGenUtil#main(String[])}.
	 *
	 * @param n number of member (replica) key pairs required
	 * @return list of {@link KeyPair} objects in member-id order (0 .. n-1)
	 */
	public static List<KeyPair> readKeysFromTestConfig(int n) throws Exception {
		Path cfgPath = configPath();

		// Ensure parent directories exist
		if (cfgPath.getParent() != null) {
			Files.createDirectories(cfgPath.getParent());
		}

		// Generate the file if it is missing or if it has fewer members than requested
		if (!Files.exists(cfgPath) || memberCountInFile(cfgPath) < n) {
			KeyGenUtil.main(new String[]{
					String.valueOf(n),
					"1",
					cfgPath.toAbsolutePath().toString()
			});
		}

		// Load properties
		Properties props = new Properties();
		try (InputStream is = new FileInputStream(cfgPath.toFile())) {
			props.load(is);
		}

		int memberCount = Integer.parseInt(props.getProperty("member.count"));
		if (memberCount < n) {
			throw new IllegalStateException(
					"config.properties has only " + memberCount + " members but " + n + " were requested");
		}

		KeyFactory kf = KeyFactory.getInstance("Ed25519");
		List<KeyPair> keyPairs = new ArrayList<>(n);

		for (int i = 0; i < n; i++) {
			String pubB64 = props.getProperty("member." + i + ".publickey");
			String privB64 = props.getProperty("node." + i + ".privatekey");

			if (pubB64 == null || privB64 == null) {
				throw new IllegalStateException(
						"Missing key entry for member " + i + " in " + cfgPath);
			}

			PublicKey pub = kf.generatePublic(
					new X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)));
			PrivateKey priv = kf.generatePrivate(
					new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)));

			keyPairs.add(new KeyPair(pub, priv));
		}

		return keyPairs;
	}

	/**
	 * Extract just the public keys from a list of key pairs (convenience
	 * mirror of {@link CryptoService#extractPublicKeys}).
	 */
	public static List<PublicKey> extractPublicKeys(List<KeyPair> keyPairs) {
		List<PublicKey> pubs = new ArrayList<>(keyPairs.size());
		for (KeyPair kp : keyPairs) {
			pubs.add(kp.getPublic());
		}
		return pubs;
	}

	// --- internal helpers ---------------------------------------------------

	private static int memberCountInFile(Path cfgPath) {
		try (InputStream is = new FileInputStream(cfgPath.toFile())) {
			Properties p = new Properties();
			p.load(is);
			String val = p.getProperty("member.count");
			return val == null ? 0 : Integer.parseInt(val);
		} catch (IOException | NumberFormatException e) {
			return 0;
		}
	}

	private TestKeyHelper() { /* utility class */ }
}
