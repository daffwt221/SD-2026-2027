import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UDPServer {

  // Mensagens já entregues à aplicação, por ordem
  private static final List<String> deliveredMessages = new ArrayList<>();

  // Mensagens recebidas fora de ordem
  // Chave = número da mensagem
  // Valor = mensagem
  private static final Map<Integer, String> temporaryMessages = new HashMap<>();

  /**
   * Processes delivered messages
   * @return the last message processed in order
   */
  public static int processDeliveredMessages(
      int nLastMessageInOrder,
      int nCurrentMessage,
      String currentMessage) {

    // A mensagem recebida é exatamente a próxima esperada
    if (nCurrentMessage == nLastMessageInOrder + 1) {

      deliveredMessages.add(currentMessage);
      nLastMessageInOrder = nCurrentMessage;

      // Entrega em cascata:
      // enquanto já existir a mensagem seguinte na estrutura temporária
      while (temporaryMessages.containsKey(nLastMessageInOrder + 1)) {

        int nextMessageNumber = nLastMessageInOrder + 1;

        String nextMessage = temporaryMessages.remove(nextMessageNumber);

        deliveredMessages.add(nextMessage);

        nLastMessageInOrder = nextMessageNumber;
      }

    } else {

      // Mensagem fora de ordem:
      // fica guardada até poder ser entregue
      temporaryMessages.put(nCurrentMessage, currentMessage);
    }

    return nLastMessageInOrder;
  }

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

        DatagramPacket request =
            new DatagramPacket(buffer, buffer.length);

        aSocket.receive(request);

        String mensagem = new String(
            request.getData(),
            0,
            request.getLength()
        );

        System.out.println("Recebido: " + mensagem);

        String resposta;

        // Guarda o número de mensagens entregues antes
        // de processar este datagrama
        int deliveredBefore = deliveredMessages.size();

        try {

          String[] partes = mensagem.split(",", 2);

          if (partes.length != 2) {

            resposta = "waitingfor," + (L + 1);

          } else {

            int N = Integer.parseInt(partes[0]);

            int oldL = L;

            L = processDeliveredMessages(
                L,
                N,
                mensagem
            );

            // Se L mudou, a mensagem atual foi entregue
            if (L > oldL) {
              resposta = mensagem;
            } else {
              resposta = "waitingfor," + (L + 1);
            }
          }

        } catch (NumberFormatException e) {

          resposta = "waitingfor," + (L + 1);
        }

        /*
         * Descobrir quais mensagens foram entregues
         * neste passo.
         */
        List<String> deliveredThisStep =
            new ArrayList<>(
                deliveredMessages.subList(
                    deliveredBefore,
                    deliveredMessages.size()
                )
            );

        System.out.println("Resposta: " + resposta);
        System.out.println("L = " + L);
        System.out.println(
            "Estrutura temporaria = " + temporaryMessages
        );
        System.out.println(
            "Entregues neste passo = " + deliveredThisStep
        );
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