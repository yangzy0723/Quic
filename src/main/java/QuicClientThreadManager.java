import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QuicClientThreadManager {
    public static void main(String[] args) {
        ExecutorService executor = Executors.newCachedThreadPool();
        for (int i = 0; i < Config.threadNumber; i++)
            executor.execute(new QuicClient(i));
        executor.shutdown();
    }
}
