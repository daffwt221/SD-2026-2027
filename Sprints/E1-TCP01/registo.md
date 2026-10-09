# Registo E1-TCP01

## Falhas introduzidas

| Falha introduzida           | Lado onde surgiu | Exceção obtida                                    | Momento                                  | Conclusão                                                                                                                   |
|----------------------------|------------------|---------------------------------------------------|------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| Cliente sem servidor       | Cliente          | `ConnectException: Connection refused`            | Ligação em `new Socket(...)`              | A ligação não é estabelecida e o construtor não devolve um socket.                                                            |
| `Place` sem `Serializable`  | Ambos            | `NotSerializableException` e `WriteAbortedException` | Durante `writeObject()` / `readObject()` | Os objetos não nulos alcançáveis a partir de `Person`, através de campos serializados, têm de ser serializáveis.              |
| `serialVersionUID` diferente | Ambos           | `InvalidClassException` e `EOFException`           | InvalidClassException ocorreu no servidor em readObject() e EOFException no cliente em readUTF()                  | IDs iguais permitem ultrapassar a verificação de versão da classe, mas não garantem, por si só, a compatibilidade completa.  |
| `Person` num pacote diferente | Ambos          | `ClassNotFoundException` e `EOFException`          | No servidor, durante `readObject()`; no cliente, ao aguardar a resposta | O pacote faz parte do nome completo da classe. O servidor precisa de conseguir resolver a classe com esse nome. |

## Construções utilizadas

| Construção                                  | Onde a usei                                          | O que ficou a ser garantido                                                                                                                 | O que continua a não ser garantido                                                                                                      |
|---------------------------------------------|------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `ServerSocket` / `accept()`                  | `TCPServer`                                          | O servidor escuta num porto e, quando `accept()` termina com sucesso, obtém um `Socket` para comunicar com o cliente.                         | Que chegue uma mensagem válida ou que a aplicação a interprete corretamente.                                                             |
| `Connection extends Thread`                 | classe connection                                          | Ao chamar `start()` para cada ligação, os clientes podem ser processados concorrentemente. A espera de um cliente não bloqueia, por si só, os restantes. | Capacidade ilimitada, ordem de conclusão, ausência de erros ou processamento correto das mensagens.                             |
| `implements Serializable`                   | Nas classes dos objetos serializados                 | Permite que as instâncias participem na serialização nativa do Java.                                                                        | Que todos os objetos referenciados sejam serializáveis, que o destinatário tenha classes compatíveis ou que os valores sejam válidos e seguros. |
| `ObjectOutputStream` / `ObjectInputStream`   | `ObjectOutputStream`: `TCPClient`; `ObjectInputStream`: `Connection` | Quando as operações terminam com sucesso, `writeObject()` serializa o objeto e o seu grafo, e `readObject()` reconstrói-os no destinatário. | Confidencialidade, autenticidade do remetente, validade dos valores, disponibilidade de classes compatíveis ou conclusão da transmissão. |
| `serialVersionUID`                          | Nas classes serializadas                             | Define explicitamente o identificador de versão usado na verificação de compatibilidade durante a desserialização.                         | Compatibilidade entre classes de nomes ou pacotes diferentes, compatibilidade total do conteúdo, segurança ou ausência de duplicação.    |
| Referência para `Place`                     | Campo `place` da classe `Person`, no cliente e no servidor | Se o campo não for `static` e contiver um objeto, o `Place` referenciado é incluído automaticamente na serialização de `Person`. | Que `Place` seja serializável, que o campo não seja `null`, que os valores sejam válidos ou que exista uma classe compatível no servidor. |
## Reflexão crítica

### 1. TCP e problemas fora da transmissão

O TCP garantiu que os bytes chegaram ao servidor pela ordem correta e sem corrupção. No entanto, isso não garantiu que a aplicação os conseguisse interpretar. Quando o `serialVersionUID` era diferente, o servidor lançou `InvalidClassException`; quando a classe estava num pacote diferente, lançou `ClassNotFoundException`. Estas eram falhas de compatibilidade e interpretação da aplicação Java, não falhas de transmissão TCP.

### 2. Evolução da classe e `serialVersionUID`

Numa aplicação real, as classes compatíveis teriam de ser distribuídas pelo servidor e pelos vários clientes. Manter o mesmo `serialVersionUID` permite ultrapassar a verificação de versão, mas não garante que o objeto continue válido para a lógica atual. Por exemplo, um novo campo `String` recebe `null` ao desserializar um objeto antigo. Alterar o UID faz com que versões diferentes sejam rejeitadas, mas não atualiza os clientes, não resolve mudanças de pacote e não converte automaticamente modelos incompatíveis.

### 3. Riscos do grafo automático

Um grafo grande aumenta o volume transmitido e pode consumir mais largura de banda, memória e tempo de processamento. Também pode incluir informação que não devia ser enviada, como uma palavra-passe. A serialização apenas codifica os dados; não os encripta nem garante confidencialidade.

### 4. Custo de uma thread por ligação

Uma thread por ligação permite atender vários clientes concorrentemente: um cliente bloqueado não impede imediatamente os restantes. Em contrapartida, cada thread consome memória, tempo de CPU, escalonamento, mudanças de contexto e recursos do socket. Com dez mil clientes simultâneos, o servidor pode ficar lento ou esgotar recursos.

### 5. Situações em que preferiria UDP

Num jogo online, preferiria UDP para enviar a posição atual do jogador. É mais importante receber rapidamente a atualização mais recente do que retransmitir um pacote com uma posição anterior, porque isso aumentaria a latência e mostraria um estado que já deixou de ser atual.


O TCP garantiu que os bytes enviados chegaram ao servidor pela ordem correta e sem corrupção. No entanto, quando alterei o uuid verifiquei duaas coisas, a primeira é que o tcp verifica o id do objeto em ambas as stream e a segunda é que verifica o id completo da classe, e o tcp não podia resolver esse problema
