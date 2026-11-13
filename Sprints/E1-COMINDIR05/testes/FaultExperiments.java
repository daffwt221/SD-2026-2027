package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import com.sun.net.httpserver.HttpServer;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import static raft04.RaftExperiments.*;

/** Cada mutação é compilada numa pasta distinta. As fontes entregues nunca são alteradas. */
public final class FaultExperiments {
    private static Path variants;

    /** Só no ensaio: atrasa a entrega de VOTO ao candidato, sem alterar mensagens. */
    private static final class VoteGate implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers=Executors.newCachedThreadPool();
        final HttpClient http=HttpClient.newHttpClient();
        final CountDownLatch release=new CountDownLatch(1);
        final AtomicBoolean waiting=new AtomicBoolean();
        final List<InputStream> streams=new CopyOnWriteArrayList<>();
        VoteGate()throws IOException {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(workers);
            server.createContext("/",exchange->{
                try {
                    URI target=URI.create(api.substring(0,api.indexOf("/api/"))+exchange.getRequestURI());
                    byte[] body=exchange.getRequestBody().readAllBytes();
                    HttpRequest.Builder request=HttpRequest.newBuilder(target).POST(HttpRequest.BodyPublishers.ofByteArray(body));
                    String contentType=exchange.getRequestHeaders().getFirst("Content-Type");
                    if(contentType!=null)request.header("Content-Type",contentType);
                    HttpResponse<InputStream> response=http.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
                    streams.add(response.body());
                    exchange.sendResponseHeaders(response.statusCode(),0);
                    try(InputStream in=response.body();OutputStream out=exchange.getResponseBody()) {
                        if(exchange.getRequestURI().getPath().endsWith("/pubsub/sub")) {
                            BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
                            for(String line;(line=reader.readLine())!=null;) {
                                Matcher matcher=Pattern.compile("\"data\"\\s*:\\s*\"([^\"]+)\"").matcher(line);
                                if(matcher.find()) {
                                    String encoded=matcher.group(1);
                                    String decoded=new String(Base64.getUrlDecoder().decode(encoded.substring(1)),StandardCharsets.UTF_8);
                                    if(decoded.equals("VOTO;1;1;2;S")) {waiting.set(true);release.await();}
                                }
                                out.write((line+"\n").getBytes(StandardCharsets.UTF_8));out.flush();
                            }
                        } else in.transferTo(out);
                    } finally {streams.remove(response.body());}
                } catch(InterruptedException e){Thread.currentThread().interrupt();}
                catch(IOException|RuntimeException e){/* Cliente terminado pelo próprio ensaio. */}
                finally {exchange.close();}
            });
            server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/api/v0";}
        @Override public void close(){release.countDown();for(InputStream in:streams)try{in.close();}catch(IOException ignored){}server.stop(0);workers.shutdownNow();}
    }

    private static String once(String source,String before,String after) {
        require(source.indexOf(before)>=0&&source.indexOf(before)==source.lastIndexOf(before),
                "Mutação tem de corresponder exatamente uma vez: "+before);
        return source.replace(before,after);
    }
    private static String variant(String name)throws Exception {
        Path folder=variants.resolve(name),src=folder.resolve("src/raft04"),out=folder.resolve("out");
        Files.createDirectories(src);Files.createDirectories(out);
        List<String> sources=new ArrayList<>();
        try(var files=Files.list(root.resolve("raft04-no/src/raft04"))) {
            for(Path path:(Iterable<Path>)files.filter(p->p.toString().endsWith(".java"))::iterator) {
                String code=Files.readString(path,StandardCharsets.UTF_8).replace("\r\n","\n");
                if(path.getFileName().toString().equals("RaftState.java")) {
                    if(name.equals("quorum"))code=once(code,"votes.size() <= groupSize / 2","votes.size() < groupSize / 2");
                    if(name.equals("memoria")) {
                        int begin=code.indexOf("    private void persist() {");
                        int end=code.indexOf("    // termo superior observado:",begin);
                        require(begin>=0&&end>begin,"Método persist não encontrado");
                        code=code.substring(0,begin)+"    private void persist() { /* FALHA: só memória. */ }\n\n"+code.substring(end);
                    }
                    if(name.equals("desatualizado"))code=once(code,
                            "boolean upToDate = cLastTerm > myLastTerm\n                || (cLastTerm == myLastTerm && cLastSeq >= myLastSeq);",
                            "boolean upToDate = true; // FALHA: ignora o histórico do candidato.");
                }
                if(path.getFileName().toString().equals("PartitionNode.java")) {
                    if(name.equals("lote-antigo"))code=once(code,"if (!accepted) {",
                            "if (!accepted && !(raft.role() == RaftState.Role.SEGUIDOR && batch.getTerm() < raft.term())) {");
                    if(name.equals("relogios-zero")) {
                        code=once(code,"lamport = last == null ? 0 : last.getLamport();","lamport = 0; // FALHA");
                        code=once(code,"vector = last == null ? new VectorClock(n) : last.getVector();","vector = new VectorClock(n); // FALHA");
                    }
                }
                Path target=src.resolve(path.getFileName());
                Files.writeString(target,code,StandardCharsets.UTF_8);sources.add(target.toString());
            }
        }
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        require(compiler!=null,"Executar com JDK, não só JRE");
        List<String> args=new ArrayList<>(List.of("--release","11","-encoding","UTF-8","-d",out.toString()));
        args.addAll(sources);
        require(compiler.run(null,null,null,args.toArray(new String[0]))==0,"Compilação da variante "+name);
        return out.toString();
    }
    private static Context context(String name,String cp)throws Exception {
        Context c=new Context("10-"+name);c.nodeClasspath=cp;c.meta.setProperty("mutacao",name);return c;
    }
    private static void conclusion(Context c,String result)throws IOException {
        c.meta.setProperty("observacao",result);
        Files.writeString(c.folder.resolve("observacao.txt"),result+"\n",StandardCharsets.UTF_8);
    }
    private static void quorum(String cp)throws Exception {
        try(Context c=context("quorum",cp)) {
            c.allNodes(501);c.waitLeaders();c.pp(true);
            c.reading("S02 24.01");
            await(()->{for(int i=1;i<=3;i++)if(c.seq(1,i)<1)return false;return true;},10000,"prefixo replicado");
            Beat old=c.beat(1);int other=old.node==1?2:1;int alone=6-old.node-other;
            c.stop(1,old.node,true);c.stop(1,other,true);
            long cut=System.currentTimeMillis();c.until(cut+16000);
            require(c.beat(1).term==old.term,"Não se esperava nova liderança com um nó");
            require(c.events(1,alone).contains(";CANDIDATO;"),"O sobrevivente tentou eleição");
            c.nodeClasspath+=File.pathSeparator+root.resolve("testes/out");
            Process generic=c.start("unit-G5","no","RaftStateTest",List.of(c.folder.toString()));
            require(generic.waitFor(20,TimeUnit.SECONDS)&&generic.exitValue()!=0,"Teste genérico devia detetar a maioria errada");
            require(text(c.folder.resolve("consola-unit-G5.txt")).contains("maioria incorreta para grupo de cinco"),"Falha genérica deve ter a causa esperada");
            conclusion(c,"O limiar errado não se manifestou neste G=3: onVote só é chamado para um voto remoto; nesse momento já há voto próprio e remoto (2). Um nó sozinho não chama onVote. O teste adicional da classe com G=5 falhou como esperado, pois a variante elege com 2 em vez de 3. G=5 é só um teste unitário genérico; o sistema da ficha continua G=3.");
            c.finish(false);
        }
    }

    private static void memory(String cp)throws Exception {
        try(Context c=context("memoria",cp);VoteGate gate=new VoteGate()) {
            for(int p:new int[]{0,2})for(int i=1;i<=3;i++)c.node(p,i);
            c.pp(false);
            c.options.addAll(List.of("-Draft04.api="+gate.url(),"-Draft04.eleicaoMin=6000","-Draft04.eleicaoMax=6000"));
            c.node(1,2);c.options.clear();
            c.options.addAll(List.of("-Draft04.eleicaoMin=12000","-Draft04.eleicaoMax=12000"));c.node(1,1);c.options.clear();
            await(gate.waiting::get,20000,"VOTO concedido retido antes de chegar ao candidato");
            require(c.beat(1)==null,"Falha deve ocorrer antes da eleição terminar");
            long stopped=c.stop(1,1,true);
            for(String event:c.events(1,1).split("\\R"))if(event.contains("candidato=n2;concedido"))
                c.meta.setProperty("atrasoVotoAteFalhaMs",""+(stopped-Long.parseLong(event.split(";")[0])));
            c.meta.setProperty("votoRetidoDuranteReinicio","true");
            c.options.addAll(List.of("-Draft04.eleicaoMin=12000","-Draft04.eleicaoMax=12000"));c.node(1,1);c.options.clear();
            await(()->c.events(1,1).lastIndexOf("arranque=true;termo=0")>0,10000,"rearranque perdeu termo e voto");
            gate.release.countDown();
            await(()->c.beat(1)!=null,10000,"voto anterior chega ao candidato após reinício do eleitor");
            Beat old=c.beat(1);require(old.node==2&&old.term==1,"Primeiro líder n2 no termo 1");
            c.isolate(1,2);
            c.reading("S02 24.101");
            await(()->c.seq(1,2)==1,5000,"leitura só no líder inicial");
            c.options.addAll(List.of("-Draft04.eleicaoMin=6000","-Draft04.eleicaoMax=6000"));c.node(1,3);c.options.clear();
            await(()->c.events(1,3).contains(";LIDER;p1;termo=1"),20000,"segunda eleição no mesmo termo");
            c.reading("S05 24.102");Thread.sleep(2500);
            c.meta.setProperty("liderAntigo",""+old.node);c.meta.setProperty("termoAntigo",""+old.term);
            conclusion(c,"Durante a eleição, um proxy local reteve sem alterar o VOTO real de n1 para n2. n1 foi terminado e reiniciado antes de n2 receber o voto; o atraso está medido. Libertou-se o voto, n2 foi eleito e isolado, e arrancou n3. Sem persistência, n1 votou em n3 no mesmo termo 1, elegendo dois líderes. Prazos controlados de 6/12 s apenas neste testemunho; a partição final preserva os dois históricos.");
            c.finish(false);
        }
    }

    private static Beat failoverPrefix(Context c)throws Exception {
        c.allNodes(503);c.waitLeaders();c.pp(true);
        Beat old=c.beat(1);c.meta.setProperty("liderAnterior",""+old.node);
        c.reading("S02 20.1");c.reading("S05 23.7");
        await(()->{for(int i=1;i<=3;i++)if(c.seq(1,i)<2)return false;return true;},10000,"duas leituras replicadas");
        long hb=c.beat(1).time;await(()->c.beat(1).time>hb,5000,"janela do lote");
        c.reading("S02 20.4");await(()->c.seq(1,old.node)==3,5000,"terceira leitura local");
        c.stop(1,old.node,true);c.reading("S05 23.9");
        await(()->c.beat(1).term>old.term,30000,"novo líder");
        return old;
    }

    private static long stateTerm(Context c,int p,int id) {
        try{return Long.parseLong(text(c.folder.resolve("estado-p"+p+"-n"+id+".txt")).strip().split(";")[0]);}
        catch(RuntimeException e){return -1;}
    }
    private static void stale(String cp)throws Exception {
        try(Context c=context("desatualizado",cp)) {
            Beat old=failoverPrefix(c),fresh=c.beat(1);
            c.reading("S02 20.6");
            int follower=6-old.node-fresh.node;
            await(()->c.seq(1,fresh.node)==3&&c.seq(1,follower)==3,10000,"nova leitura replicada");
            c.stop(1,fresh.node,false);c.stop(1,follower,false);
            Files.copy(c.folder.resolve("no-p1-n"+old.node+".txt"),c.folder.resolve("testemunha-desatualizado.txt"));
            Files.copy(c.folder.resolve("no-p1-n"+follower+".txt"),c.folder.resolve("testemunha-atualizado.txt"));
            c.node(1,old.node);
            await(()->stateTerm(c,1,old.node)>fresh.term,40000,"candidato antigo sobe de termo sem maioria");
            c.node(1,follower);
            await(()->c.beat(1).term>fresh.term&&c.beat(1).node==old.node,30000,"candidato desatualizado eleito");
            c.node(1,fresh.node);c.converge();Thread.sleep(2200);
            conclusion(c,"Repetiu-se a figura e guardaram-se os dois históricos. Depois, as duas réplicas atualizadas foram paradas; o antigo líder avançou o termo sozinho e uma réplica voltou. Sem upToDate, o candidato com último registo de termo antigo obteve o voto e impôs esse histórico, apagando a leitura 20.6 já replicada.");
            c.finish(false);
        }
    }
    private static void oldBatch(String cp)throws Exception {
        try(Context c=context("lote-antigo",cp)) {
            networkScenario(c);
            long next=c.seq(1,c.beat(1).node)+1;
            c.reading("S02 24.401");
            await(()->{for(int id=1;id<=3;id++)if(c.seq(1,id)<next)return false;return true;},10000,"sonda replicada antes de repetir o lote antigo");
            c.converge();
            String captured=text(c.folder.resolve("lote-antigo.txt"));
            try(IpfsPubSub pub=new IpfsPubSub(api)) {
                pub.publish(Wire.topicData(1),captured);
            }
            await(()->{
                for(int i=1;i<=3;i++)for(String line:c.events(1,i).split("\\R"))
                    if(line.contains(";RECEBIDO;")&&line.contains("origem=LOTE")) {
                        int t=line.indexOf(";termo="),a=line.indexOf(";atual=");
                        if(t>=0&&a>=0)try {
                            long term=Long.parseLong(line.substring(t+7).split(";")[0]);
                            long current=Long.parseLong(line.substring(a+7).split(";")[0]);
                            if(term<current)return true;
                        }catch(NumberFormatException ignored){}
                    }
                return false;
            },10000,"aplicação do lote obsoleto repetido");
            c.converge();Thread.sleep(2200);
            conclusion(c,"Repetiu-se 9(d), incluindo os 60 s de isolamento do seguidor. Depois, publicou-se uma cópia real do LOTE guardado antes do corte, para representar uma mensagem atrasada. Os seguidores aplicaram o termo antigo; o próximo heartbeat atual pode reparar o estado, mas não apaga a violação registada.");
            c.finish(false);
        }
    }
    private static void clocks(String cp)throws Exception {
        try(Context c=context("relogios-zero",cp)) {
            Beat old=failoverPrefix(c),fresh=c.beat(1);
            c.reading("S02 20.6");
            await(()->c.events(1,fresh.node).contains("relogios inconsistentes"),10000,"guarda de relógios deteta reinício a zero");
            c.node(1,old.node);c.converge();
            conclusion(c,"O novo líder tinha seq=2, mas V[p1] foi reiniciado a 0. A próxima leitura tentaria seq=3 e V[p1]=1; a guarda já existente recusou-a antes da gravação. CausalCheck pode passar porque o registo inválido não chegou ao disco. A leitura extra perde-se e ERRO explica a causa.");
            c.finish(false);
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length<3||args.length>4)throw new IllegalArgumentException("FaultExperiments <all|quorum|memoria|desatualizado|lote-antigo|relogios-zero> <raiz> <resultados> [api]");
        root=Path.of(args[1]).toAbsolutePath().normalize();base=Path.of(args[2]).toAbsolutePath().normalize();
        api=args.length==4?args[3]:"http://127.0.0.1:5001/api/v0";
        variants=root.resolve("resultados/variantes-"+System.currentTimeMillis());Files.createDirectories(base);
        try(RunLock lock=new RunLock()) {
        for(String name:List.of("quorum","memoria","desatualizado","lote-antigo","relogios-zero")) {
            if(!args[0].equals("all")&&!args[0].equals(name))continue;
            String cp=variant(name);
            if(name.equals("quorum"))quorum(cp);
            if(name.equals("memoria"))memory(cp);
            if(name.equals("desatualizado"))stale(cp);
            if(name.equals("lote-antigo"))oldBatch(cp);
            if(name.equals("relogios-zero"))clocks(cp);
        }
        System.out.println("FALHAS_TERMINADAS; fontes originais preservadas");
        }
    }
}
