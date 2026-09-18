import java.net.*;
import java.io.*;

public class UDPServer {

  public static void main(String args[]) {
    DatagramSocket aSocket = null;

    try {
      aSocket = new DatagramSocket(6789);
      byte[] buffer = new byte[1000];

      int L = 0;

      System.out.println("Servidor iniciado no porto 6789...");
      System.out.println("L inicial = " + L);
      System.out.println();

      while (true) {
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);

        aSocket.receive(request);

        String mensagem = new String(
            request.getData(),
            0,
            request.getLength()
        );

        System.out.println("Recebido: " + mensagem);

        String resposta;

        try {
          String[] partes = mensagem.split(",", 2);

          if (partes.length != 2) {
            resposta = "waitingfor," + (L + 1);
          } else {
            int N = Integer.parseInt(partes[0]);

            if (N == L + 1) {
              L = N;
              resposta = mensagem;
            } else {
              resposta = "waitingfor," + (L + 1);
            }
          }

        } catch (NumberFormatException e) {
          resposta = "waitingfor," + (L + 1);
        }

        System.out.println("Resposta: " + resposta);
        System.out.println("L = " + L);
        System.out.println();

        byte[] dadosResposta = resposta.getBytes();

        DatagramPacket reply = new DatagramPacket(
            dadosResposta,
            dadosResposta.length,
            request.getAddress(),
            request.getPort()
        );

        aSocket.send(reply);
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