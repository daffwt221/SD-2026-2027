package raft04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static raft04.AuditFiles.*;

/** Valida todos os ficheiros de nós e compara eventos lógicos sem contar réplicas três vezes. */
public final class CausalCheck {
    private CausalCheck() { }
    private static final class Event {
        final int partition;
        final SensorRecord record;
        Event(int partition,SensorRecord record){this.partition=partition;this.record=record;}
        String id(){return "p"+partition+"#"+record.getSeq()+"@"+record.getTerm();}
    }
    public static void main(String[] args) {
        if(args.length!=2){System.err.println("Uso: CausalCheck <N> <pasta>");System.exit(1);}
        try{System.exit(check(Integer.parseInt(args[0]),Path.of(args[1]),System.out));}
        catch(IOException|RuntimeException e){System.err.println("ERRO: "+e.getMessage());System.exit(1);}
    }

    static int check(int n,Path folder,PrintStream out)throws IOException {
        if(n<1||!Files.isDirectory(folder))throw new IllegalArgumentException("N/pasta inválidos");
        List<NodeLog> logs=nodes(n,folder);
        if(logs.isEmpty())throw new IllegalArgumentException("Nenhum ficheiro no-p*-n*.txt");
        Map<String,Event> unique=new LinkedHashMap<>();
        int localErrors=0;
        for(NodeLog log:logs) {
            SensorRecord previous=null;
            for(SensorRecord record:log.records) {
                VectorClock vector=record.getVector();
                if(vector.size()!=n) {
                    localErrors++;out.println("ERRO DIMENSAO: "+log.path+" seq="+record.getSeq());continue;
                }
                if(vector.get(log.partition)!=record.getSeq()) {
                    localErrors++;out.println("ERRO V[k]=seq: "+log.path+" seq="+record.getSeq()+" V="+vector);
                }
                if(previous!=null) {
                    if(record.getLamport()<=previous.getLamport()) {
                        localErrors++;out.println("ERRO L LOCAL: "+log.path+" seq="+record.getSeq());
                    }
                    for(int k=0;k<n;k++)if(vector.get(k)<previous.getVector().get(k)) {
                        localErrors++;out.println("ERRO V LOCAL: "+log.path+" seq="+record.getSeq()+" componente="+k);
                    }
                }
                previous=record;
                String id=log.partition+":"+record.getSeq()+":"+record.getTerm();
                Event old=unique.putIfAbsent(id,new Event(log.partition,record));
                if(old!=null&&!old.record.toLine().equals(record.toLine())) {
                    localErrors++;out.println("ERRO MESMO (seq,termo) COM CONTEUDOS DIFERENTES: "+id);
                }
            }
        }
        List<Event> events=new ArrayList<>(unique.values());
        long causal=0,concurrent=0,equal=0,lamportErrors=0;
        boolean verbose=Boolean.getBoolean("raft04.check.verbose");
        for(int i=0;i<events.size();i++)for(int j=i+1;j<events.size();j++) {
            Event a=events.get(i),b=events.get(j);
            if(a.partition==b.partition)continue;
            boolean less=false,greater=false;
            for(int k=0;k<n;k++){
                if(a.record.getVector().get(k)<b.record.getVector().get(k))less=true;
                if(a.record.getVector().get(k)>b.record.getVector().get(k))greater=true;
            }
            if(less&&!greater || greater&&!less) {
                causal++;
                Event first=less?a:b,last=less?b:a;
                if(verbose)out.println(first.id()+" -> "+last.id());
                if(first.record.getLamport()>=last.record.getLamport()) {
                    lamportErrors++;out.println("ERRO LAMPORT: "+first.id()+" -> "+last.id());
                }
            } else {
                concurrent++;
                if(!less&&!greater)equal++;
                if(verbose)out.println(a.id()+" || "+b.id());
            }
        }
        events.sort(Comparator.comparingLong((Event e)->e.record.getLamport())
                .thenComparingInt(e->e.partition).thenComparingLong(e->e.record.getTerm())
                .thenComparingLong(e->e.record.getSeq()));
        List<String> view=new ArrayList<>();
        for(Event event:events)view.add("p"+event.partition+";"+event.record.toLine());
        Files.write(folder.resolve("vista-lamport.txt"),view,StandardCharsets.UTF_8);
        out.println("ficheirosNos="+logs.size()+" N="+n);
        out.println("eventosLogicos="+events.size());
        out.println("errosLocais="+localErrors);
        out.println("paresCausais="+causal);
        out.println("paresConcorrentes="+concurrent);
        out.println("paresVetoresIguais="+equal);
        out.println("errosLamport="+lamportErrors);
        Properties summary=new Properties();
        summary.setProperty("eventos",Integer.toString(events.size()));
        summary.setProperty("errosLocais",Integer.toString(localErrors));
        summary.setProperty("errosLamport",Long.toString(lamportErrors));
        summary.setProperty("paresCausais",Long.toString(causal));
        summary.setProperty("paresConcorrentes",Long.toString(concurrent));
        try(Writer writer=Files.newBufferedWriter(folder.resolve("causalcheck-resumo.properties"),StandardCharsets.UTF_8)){
            summary.store(writer,"Analise dos registos finais, sem duplicar replicas");
        }
        boolean valid=localErrors==0&&lamportErrors==0;
        out.println(valid?"RESULTADO: VALIDO.":"RESULTADO: FALHOU.");
        return valid?0:2;
    }
}
