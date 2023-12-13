import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QuicClientThreadManager {
    public static void main(String[] args) {
        if (args.length > 0) {
            Config.serverIP = args[0];
            Config.toPort = Integer.parseInt(args[1]);
            Config.threadNumber = Integer.parseInt(args[2]);
        }
        ExecutorService executor = Executors.newCachedThreadPool();
        for (int i = 0; i < Config.threadNumber; i++)
            executor.execute(new QuicClient(i));
        executor.shutdown();
    }
}
