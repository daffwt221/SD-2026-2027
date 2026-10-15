package rep01;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class MulticastReplica {
    static final String GROUP = System.getProperty("rep01.group", "230.0.0.1");
    static final int PORT = Integer.getInteger("rep01.port", 6789);

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            int size = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
            Long start = args.length > 2 ? Long.valueOf(args[2]) : null;
            ReplicaState state = new ReplicaState(id, size, start);
            socket.joinGroup(InetAddress.getByName(GROUP));
            socket.setSoTimeout(1000);
            System.out.println("Replica " + id + " a escuta em " + GROUP + ":" + PORT);
            byte[] buffer = new byte[Integer.getInteger("rep01.buffer", 1000)];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet); // bloqueia ate chegar um datagrama ou expirar o timeout
                } catch (SocketTimeoutException e) {
                    state.reportIfIdle();
                    continue;
                }
                long receivedAt = System.currentTimeMillis();
                String line = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                state.accept(line, receivedAt);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("ERRO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
