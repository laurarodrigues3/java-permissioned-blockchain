package tecnico.depchain;

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

	public static Long longDigest(byte[] data)
	{
		byte[] digest = null;
		try {
			digest = MessageDigest.getInstance("MD5").digest(data);
		} catch (NoSuchAlgorithmException e)
		{
			return (long)0; //Won't happen
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
}
