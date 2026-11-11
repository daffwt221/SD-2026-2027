# Validação da entrega

Execução em Windows, 11/11/2026, JDK 25.0.2, fontes compiladas com --release 11 e UTF-8; Kubo 0.39.0.

| Verificação | Resultado |
|---|---|
| Compilação dos três projetos | Passou |
| RaftState e verificadores, sem IPFS | 29 verificações passaram |
| Ensaios 9(a)–9(e) | 21 execuções; RaftCheck=0 e CausalCheck=0 em todas |
| Cinco falhas deliberadas | Executadas, uma variante por ensaio; resultados reais no relatório |
| Smoke final, através de ensaiar.ps1 e do daemon criado por preparar-ipfs.ps1 | RaftCheck=0, CausalCheck=0 |
| Lançador interativo | Arrancou os processos e terminou-os com o comando sair; portos livres no fim |
| RaftState fornecido | Texto igual ao bloco Java da ficha, normalizando as mudanças de linha |
| Classes comuns aos três projetos | Cópias idênticas verificadas por SHA256 |
| Documentação | Ligações locais verificadas |

O teste unitário genérico com G=5, executado sobre a variante de maioria errada, falhou na condição esperada. É um testemunho da fórmula errada; os processos do sistema da ficha mantêm G=3.

Os resultados de referência são os de [execucao-kubo039](execucao-kubo039). As variantes de persistência e lote antigo têm falhas esperadas nos verificadores; não fazem parte dos 21 ensaios normais. As outras variantes ilustram também os limites da deteção, descritos no [relatório](../RELATORIO.md).

O [smoke final](smoke-final/smoke/RaftCheck.txt) usa prazos mais curtos e não entra nas estatísticas da ficha. As fontes finais estão identificadas em [fontes-sha256.txt](fontes-sha256.txt), com CRLF normalizado para LF no cálculo dos hashes. A campanha de diagnóstico Kubo 0.43.1 e tentativas repetidas foram excluídas das medidas.

Não há confirmação de escritas por maioria, reenvio de leituras ou Pre-Vote. As perdas medidas são parte dos limites pedidos na ficha, não foram escondidas para obter verificadores com código 0.
