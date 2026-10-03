import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UDPServer {

  // Lista de rececao: mensagens efetivamente entregues, pela ordem correta.
  private static final List<String> deliveredMessages = new ArrayList<>();

  // Estrutura temporaria: numero da mensagem -> mensagem completa.
  // Conforme o algoritmo pedido, guarda qualquer N diferente de L+1,
  // incluindo duplicados antigos (limitacao a analisar na reflexao critica).
  private static final Map<Integer, String> temporaryMessages = new HashMap<>();

  /**
   * Processes delivered messages
   * @return the last message processed in order
   */
  public static int processDeliveredMessages(
      int nLastMessageInOrder,
      int nCurrentMessage,
      String currentMessage) {

    if (nCurrentMessage == nLastMessageInOrder + 1) {
      // Mensagem na ordem esperada: entregar.
      deliveredMessages.add(currentMessage);
      nLastMessageInOrder = nCurrentMessage;

      // Entrega em cascata de mensagens consecutivas previamente retidas.
      while (temporaryMessages.containsKey(nLastMessageInOrder + 1)) {
        int nextMessageNumber = nLastMessageInOrder + 1;
        String nextMessage = temporaryMessages.remove(nextMessageNumber);
        deliveredMessages.add(nextMessage);
        nLastMessageInOrder = nextMessageNumber;
      }
    } else {
      // Especificacao do exercicio: qualquer N != L+1 e armazenado.
      // Para mensagens repetidas na estrutura temporaria, a mesma chave
      // e substituida (comportamento normal do HashMap).
      temporaryMessages.put(nCurrentMessage, currentMessage);
    }

    return nLastMessageInOrder;
  }

  public static void main(String[] args) {
    try (DatagramSocket aSocket = new DatagramSocket(6789)) {
      int L = 0;

      System.out.println("Servidor iniciado no porto 6789...");
      System.out.println("L inicial = " + L);
      System.out.println();

      while (true) {
        byte[] buffer = new byte[1000];
        DatagramPacket request = new DatagramPacket(buffer, buffer.length);
        aSocket.receive(request);

        String mensagem = new String(
            request.getData(), 0, request.getLength(), StandardCharsets.UTF_8);
        System.out.println("Recebido: " + mensagem);

        int deliveredBefore = deliveredMessages.size();
        String resposta;

        try {
          String[] partes = mensagem.split(",", 2);
          if (partes.length != 2) {
            resposta = "waitingfor," + (L + 1);
          } else {
            int N = Integer.parseInt(partes[0]);
            int oldL = L;
            L = processDeliveredMessages(L, N, mensagem);
            resposta = L > oldL ? mensagem : "waitingfor," + (L + 1);
          }
        } catch (NumberFormatException e) {
          // Numero invalido: nao terminar e nao alterar L.
          resposta = "waitingfor," + (L + 1);
        }

        List<String> deliveredThisStep = new ArrayList<>(
            deliveredMessages.subList(deliveredBefore, deliveredMessages.size()));

        System.out.println("Resposta: " + resposta);
        System.out.println("L = " + L);
        System.out.println("Estrutura temporaria = " + temporaryMessages);
        System.out.println("Entregues neste passo = " + deliveredThisStep);
        // O ponto 11 pede a lista completa de rececao.
        System.out.println("Lista de rececao completa = " + deliveredMessages);
        System.out.println();

        byte[] dadosResposta = resposta.getBytes(StandardCharsets.UTF_8);
        DatagramPacket reply = new DatagramPacket(
            dadosResposta, dadosResposta.length,
            request.getAddress(), request.getPort());
        aSocket.send(reply);
      }
    } catch (SocketException e) {
      System.out.println("Socket: " + e.getMessage());
    } catch (IOException e) {
      System.out.println("IO: " + e.getMessage());
    }
  }
}
