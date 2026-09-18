import java.net.*;
import java.io.*;

public class UDPClient {

  public static void main(String args[]) {
    DatagramSocket aSocket = null;

    try {
      aSocket = new DatagramSocket();

      BufferedReader in = new BufferedReader(
          new InputStreamReader(System.in)
      );

      InetAddress aHost = InetAddress.getByName("localhost");
      int serverPort = 6789;

      System.out.println("Escolha o modo:");
      System.out.println("1 - Automatico");
      System.out.println("2 - Manual");
      System.out.print("Modo: ");

      String modo = in.readLine();

      int numeroAutomatico = 1;

      while (true) {

        int N;

        if (modo.equals("1")) {
          N = numeroAutomatico;
        } else {
          System.out.print("Numero da mensagem: ");
          N = Integer.parseInt(in.readLine());
        }

        System.out.print("Mensagem: ");
        String mensagem = in.readLine();

        if (mensagem == null || mensagem.equalsIgnoreCase("sair")) {
          break;
        }

        String mensagemCompleta = N + "," + mensagem;

        byte[] m = mensagemCompleta.getBytes();

        DatagramPacket request =
            new DatagramPacket(m, m.length, aHost, serverPort);

        aSocket.send(request);

        byte[] buffer = new byte[1000];

        DatagramPacket reply =
            new DatagramPacket(buffer, buffer.length);

        aSocket.receive(reply);

        String resposta = new String(
            reply.getData(),
            0,
            reply.getLength()
        );

        if (resposta.startsWith("waitingfor,")) {
          System.out.println("Servidor: " + resposta);
        } else {
          System.out.println("Echo: " + resposta);

          if (modo.equals("1")) {
            numeroAutomatico++;
          }
        }
      }

    } catch (SocketException e) {
      System.out.println("Socket: " + e.getMessage());
    } catch (IOException e) {
      System.out.println("IO: " + e.getMessage());
    } finally {
      if (aSocket != null)
        aSocket.close();
    }
  }
}