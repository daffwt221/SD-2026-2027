import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class UDPClient {

  public static void main(String[] args) {
    try (DatagramSocket aSocket = new DatagramSocket();
         BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {

      InetAddress aHost = InetAddress.getByName("localhost");
      int serverPort = 6789;

      System.out.println("Escolha o modo:");
      System.out.println("1 - Automatico");
      System.out.println("2 - Manual");
      System.out.print("Modo: ");
      String modo = in.readLine();

      if (!"1".equals(modo) && !"2".equals(modo)) {
        System.out.println("Modo invalido. Escolha 1 ou 2.");
        return;
      }

      int numeroAutomatico = 1;
      while (true) {
        int N;
        if ("1".equals(modo)) {
          N = numeroAutomatico;
        } else {
          System.out.print("Numero da mensagem (ou 'sair'): ");
          String entrada = in.readLine();
          if (entrada == null || entrada.equalsIgnoreCase("sair")) {
            break;
          }
          try {
            N = Integer.parseInt(entrada.trim());
          } catch (NumberFormatException e) {
            System.out.println("Numero invalido. Introduza um inteiro.");
            continue;
          }
        }

        System.out.print("Mensagem (ou 'sair'): ");
        String mensagem = in.readLine();
        if (mensagem == null || mensagem.equalsIgnoreCase("sair")) {
          break;
        }

        String mensagemCompleta = N + "," + mensagem;
        byte[] m = mensagemCompleta.getBytes(StandardCharsets.UTF_8);
        DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
        aSocket.send(request);

        byte[] buffer = new byte[1000];
        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
        aSocket.receive(reply);
        String resposta = new String(
            reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);

        if (resposta.startsWith("waitingfor,")) {
          System.out.println("Servidor: " + resposta);
        } else {
          System.out.println("Echo: " + resposta);
          if ("1".equals(modo)) {
            numeroAutomatico++;
          }
        }
      }
    } catch (SocketException e) {
      System.out.println("Socket: " + e.getMessage());
    } catch (IOException e) {
      System.out.println("IO: " + e.getMessage());
    }
  }
}
