package tecnico.depchain;

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
}
