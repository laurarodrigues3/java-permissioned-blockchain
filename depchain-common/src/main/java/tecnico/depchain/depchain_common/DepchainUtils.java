package tecnico.depchain.depchain_common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class DepchainUtils {

	public static void sleep(long millis)
	{
		try
		{
			Thread.sleep(millis);
		}
		catch (InterruptedException e)
		{
			// Ignore
		}
	}

	//64-bit digest (truncation of MD5)
	public static Long longDigest(byte[] data) {
		byte[] digest = null;
		try {
			digest = MessageDigest.getInstance("MD5").digest(data);
		} catch (NoSuchAlgorithmException e)
		{
			return (long)0; //Won't happen (we hope)
		}

		return (long)digest[0] |
			digest[1] << 8 |
			digest[2] << 16 |
			digest[3] << 24 |
			digest[4] << 32 |
			digest[5] << 40 |
			digest[6] << 48 |
			digest[7] << 56;
	}

	public static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		}
		catch (NoSuchAlgorithmException e) {
			return new byte[32]; //Won't happen
		}
	}

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
