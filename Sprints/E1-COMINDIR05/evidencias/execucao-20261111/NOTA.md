# Campanha de diagnóstico com Kubo 0.43.1

Esta campanha foi interrompida em `9c-2`. O produtor publicou 148 das 300 leituras antes de a API responder HTTP 500 com `validation ignored`. O ensaio incompleto não é uma medição de perdas do algoritmo: as restantes leituras nem chegaram a ser publicadas.

O arquivo integral da tentativa fica localmente em `resultados/diagnostico-kubo043`, excluído do Git. Aqui conservam-se a consola e o registo do produtor que documentam a interrupção. Nenhum ensaio dessa tentativa entra nas tabelas finais. A campanha de referência foi repetida em `../execucao-kubo039`, com Kubo 0.39.0, a mesma carga e sem acrescentar reenvios ao produtor.

Diagnóstico: Kubo 0.43.1 introduz um validador de números de sequência PubSub que pode ignorar publicações concorrentes do mesmo daemon. A relação com a concorrência neste ensaio é uma inferência; o HTTP 500 e a interrupção estão nos ficheiros de consola. Os nove processos partilham um daemon local, pelo que partilham a identidade PubSub.

Fontes primárias: [configuração PubSub 0.43.1](https://github.com/ipfs/kubo/blob/v0.43.1/core/node/libp2p/pubsub.go), [validador](https://github.com/libp2p/go-libp2p-pubsub/blob/v0.17.0/validation_builtin.go), [configuração 0.39.0](https://github.com/ipfs/kubo/blob/v0.39.0/core/node/libp2p/pubsub.go).
