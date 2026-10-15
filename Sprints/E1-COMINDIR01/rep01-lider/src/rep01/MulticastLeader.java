package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class MulticastLeader {
    static final String GROUP = System.getProperty("rep01.group", "230.0.0.1");
    static final int PORT = Integer.getInteger("rep01.port", 6789);

    public static void main(String[] args) {
        try (MulticastSocket socket = new MulticastSocket();
                Scanner sc = new Scanner(System.in)) {
            String mode = args.length > 0 ? args[0] : null;
            while (mode == null) {
                System.out.println("Escolhe o modo:");
                System.out.println("1 - Automático");
                System.out.println("2 - Manual");
                System.out.print("Opção: ");
                if (!sc.hasNextLine()) return;
                switch (sc.nextLine().trim()) {
                    case "1":
                        mode = "automatico";
                        break;
                    case "2":
                        mode = "teste-manual";
                        break;
                    default:
                        System.out.println("Escolhe 1 ou 2.");
                }
            }
            InetAddress group = InetAddress.getByName(GROUP);
            socket.setTimeToLive(1); // o datagrama não sai da rede local
            if (mode.equals("teste-manual")) {
                runManual(socket, group, sc);
                return;
            }
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq(); // continua a numeração após reinício
            boolean testOrder = mode.equals("teste-ordem");
            if (testOrder && seq != 0) {
                System.out.println("O teste de ordem requer um lider.txt novo, numa pasta de teste.");
                return;
            }
            SensorRecord delayed = null; // mantém o registo 1 entre leituras da CLI
            System.out.println("Líder pronto. Formato: <sensor> <temperatura> | rajada <n> | sair");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine())
                    break; // bloqueia à espera do utilizador
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair"))
                    break;
                String[] p = line.split("\\s+");
                if (p[0].equalsIgnoreCase("rajada")) {
                    int count;
                    try {
                        if (p.length != 2) throw new NumberFormatException();
                        count = Integer.parseInt(p[1]);
                        if (count <= 0 || seq > Long.MAX_VALUE - count) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        System.out.println("Formato: rajada <n inteiro positivo>");
                        continue;
                    }
                    long firstSeq = seq + 1;
                    long firstSent = 0;
                    long lastSent = 0;
                    long started = System.nanoTime();
                    for (int i = 0; i < count; i++) {
                        SensorRecord record = SensorRecord.now(++seq, "SIM",
                                ThreadLocalRandom.current().nextDouble(15.0, 30.0));
                        file.append(record);
                        byte[] message = record.toLine().getBytes(StandardCharsets.UTF_8);
                        long sendingAt = System.currentTimeMillis();
                        socket.send(new DatagramPacket(message, message.length, group, PORT));
                        if (i == 0) firstSent = sendingAt;
                        lastSent = sendingAt;
                    }
                    double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
                    System.out.printf(java.util.Locale.ROOT,
                            "RAJADA seq=%d..%d enviados=%d primeiroEnvio=%d ultimoEnvio=%d intervaloEnvioMs=%d duracaoMs=%.3f debitoRegistosS=%.3f%n",
                            firstSeq, seq, count, firstSent, lastSent, lastSent - firstSent,
                            elapsedMs, count * 1000.0 / elapsedMs);
                    continue;
                }
                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }
                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                file.append(r); // 1) escreve localmente
                if (testOrder && r.getSeq() == 1) {
                    delayed = r;
                    System.out.println("Registado localmente; envio adiado: " + r.toLine());
                    continue;
                }
                byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(m, m.length, group, PORT)); // 2) propaga para o grupo
                System.out.println("Registado e enviado: " + r.toLine());
                if (testOrder && r.getSeq() == 3 && delayed != null) {
                    byte[] delayedBytes = delayed.toLine().getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(delayedBytes, delayedBytes.length, group, PORT));
                    System.out.println("Enviado após o 3: " + delayed.toLine());
                    delayed = null;
                }
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }

    // Ferramenta temporária: injeta datagramas para testar a réplica.
    // Não altera lider.txt nem o contador usado no modo automático.
    private static void runManual(MulticastSocket socket, InetAddress group, Scanner sc) throws IOException {
        System.out.println("Manual | Formato: <seq> <sensor> <temperatura> | sair");
        while (true) {
            System.out.print("> ");
            if (!sc.hasNextLine()) return;
            String line = sc.nextLine().trim();
            if (line.equalsIgnoreCase("sair")) return;
            String[] parts = line.split("\\s+");
            if (parts.length != 3) {
                System.out.println("Formato: <seq> <sensor> <temperatura>");
                continue;
            }
            long seq;
            double temperature;
            try {
                seq = Long.parseLong(parts[0]);
                temperature = Double.parseDouble(parts[2]);
                if (seq < 1) {
                    System.out.println("O seq deve ser positivo.");
                    continue;
                }
            } catch (NumberFormatException e) {
                System.out.println("Seq ou temperatura inválidos.");
                continue;
            }
            SensorRecord record = SensorRecord.now(seq, parts[1], temperature);
            byte[] message = record.toLine().getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(message, message.length, group, PORT));
            System.out.println("Enviado: " + record.toLine());
        }
    }
}
